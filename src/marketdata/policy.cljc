(ns marketdata.policy
  "MarketDataGovernor — the independent compliance layer that earns the
  MarketData-LLM the right to ingest, publish or resolve a dispute. The LLM
  has no notion of provenance licensing, print-tolerance, halt state or a
  subscriber's disclosure entitlement, so this MUST be a separate system
  able to *reject* a proposal and fall back to HOLD (ingest/publish/resolve
  nothing) — this actor's analog of `cloud-itonami-isic-8291`'s
  DisclosureGovernor and robotaxi's Minimal Risk Condition.

  Seven checks, in priority order. The first four are HARD violations: a
  human approver CANNOT override them. The last three are SOFT/always-
  escalate: they route to a human, who may approve.

    1. rbac                  — does actor-role have permission for op?
    2. tolerance-gate         — does an ingested price fall within tolerance
                                of the last known-good quote? (fat-finger /
                                stale-feed defense; this actor's analog of
                                8291's scope-gate)
    3. source-provenance-gate — does the quote/series cite an allowed
                                provenance class, and — for
                                `:licensed-operator-feed` — an ACTIVE,
                                asset-class-covering feed-license?
    4. licensed-disclosure    — is there an active subscriber contract, and
                                does the requested column set stay within
                                its tier?
    5. confidence floor       — LLM confidence below threshold → escalate.
    6. halted-instrument gate — the instrument is halted or circuit-broken
                                → always escalate, regardless of confidence.
    7. correction requests    — a data-quality dispute NEVER auto-resolves,
                                at any confidence, any phase."
  (:require [clojure.set :as set]
            [marketdata.facts :as facts]
            [marketdata.store :as store]))

;; ───────────────────────── policy tables ─────────────────────────

(def tolerance-pct
  "Maximum fractional deviation an ingested price may have from the last
  known-good quote before the tolerance-gate rejects it outright. This is a
  HARD ceiling — no confidence score, however high, waives it. A legitimate
  large move (e.g. after a halt resumes) still requires a human via the
  halted-instrument gate, not a bypass of this check."
  0.15)

(def confidence-floor 0.6)

(def permissions
  "actor-role → set of operations it may perform."
  {:feed-operator         #{:quote/ingest :series/derive}
   :data-quality-officer  #{:quote/ingest :series/derive :correction/request}
   :subscriber            #{:disclosure/query}})

(def tier-columns
  "For `:disclosure/query` — the columns each licensed subscriber tier may
  see. Anything beyond this is over-disclosure (licensed-disclosure
  violation), the market-data analog of `cloud-itonami-isic-8291`'s
  disclosure-minimization tiers."
  (let [base #{:instrument-id :symbol :asset-class :price :currency :as-of}
        pro-extra #{:series}
        inst-extra #{:raw-source}]
    {:tier/basic         base
     :tier/pro           (into base pro-extra)
     :tier/institutional (into base (into pro-extra inst-extra))}))

;; ───────────────────────── checks ─────────────────────────

(defn- rbac-violations [{:keys [op]} {:keys [actor-role]}]
  (when-not (contains? (get permissions actor-role #{}) op)
    [{:rule :rbac :detail (str actor-role " は " op " の権限を持たない")}]))

(defn- tolerance-violations
  "Only `:quote/ingest` asserts a new price. `:series/derive` aggregates
  already-committed quotes, so it is not re-checked here."
  [{:keys [op]} proposal st]
  (when (= op :quote/ingest)
    (let [{:keys [instrument-id]} (:value proposal)
          new-price (get-in proposal [:value :price])
          prior     (:price (store/quote* st instrument-id))]
      (when (and prior new-price)
        (let [dev (/ (double (abs (- new-price prior))) (double prior))]
          (when (> dev tolerance-pct)
            [{:rule :tolerance-gate
              :detail (str "価格が許容乖離を超過: prior=" prior " new=" new-price
                           " dev=" (double dev) " > " tolerance-pct)}]))))))

(defn- source-provenance-violations
  "Only `:quote/ingest` and `:series/derive` assert new provenance, so only
  those two ops are checked here. A missing source, a `:class` outside
  `marketdata.facts/allowed-source-classes`, or a `:licensed-operator-feed`
  citation whose `:license-id` does not resolve to an ACTIVE feed-license
  covering the instrument's asset-class, is a HARD rejection regardless of
  the LLM's stated confidence."
  [{:keys [op]} proposal st]
  (when (contains? #{:quote/ingest :series/derive} op)
    (let [src (:source proposal)
          instrument-id (get-in proposal [:value :instrument-id])
          inst (store/instrument st instrument-id)]
      (cond
        (or (nil? src) (not (facts/class-allowed? (:class src))))
        [{:rule :source-provenance-gate
          :detail (str "出典が無いか許可された出典クラスでない: " (pr-str src))}]

        (facts/licensed-feed-class? (:class src))
        (let [lic (store/feed-license st (:license-id src))]
          (when (or (nil? lic) (not (:active? lic))
                    (not (contains? (:asset-classes lic) (:asset-class inst))))
            [{:rule :source-provenance-gate
              :detail (str "有効な feed-license が無いかアセットクラス対象外: "
                           "license-id=" (:license-id src) " asset-class=" (:asset-class inst))}]))

        :else nil))))

(defn- licensed-disclosure-violations
  "`:disclosure/query` is only ever served against a Store-registered,
  active contract — never against caller-asserted context. Over-disclosure
  (columns beyond the contract's tier) is checked the same pass."
  [{:keys [op]} {:keys [tenant]} proposal st]
  (when (= op :disclosure/query)
    (let [c (when tenant (store/contract st tenant))]
      (if (or (nil? c) (not (:active? c)))
        [{:rule :licensed-disclosure :detail (str "有効な契約が無い: tenant=" tenant)}]
        (let [allowed (get tier-columns (:tier c) #{})
              cols    (set (:columns proposal))
              extra   (set/difference cols allowed)]
          (when (seq extra)
            [{:rule :licensed-disclosure
              :detail (str "契約 tier " (:tier c) " に対し過剰な列: " (vec extra))}]))))))

(defn- halted-instrument?
  [st instrument-id]
  (when instrument-id
    (let [inst (store/instrument st instrument-id)]
      (boolean (and inst (or (= :halted (:status inst)) (:circuit-breaker? inst)))))))

(defn check
  "Censors a MarketData-LLM proposal against the policy tables. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :halted? bool
    :hard? bool :correction? bool}.

   - :hard?       — at least one HARD violation (tolerance-gate/source-
                    provenance-gate/licensed-disclosure). Forces HOLD; a
                    human cannot override.
   - :escalate?   — soft: low confidence, halted/circuit-broken instrument,
                    OR a correction request. A human decides.
   - :ok?         — clean AND not escalating: safe to auto-commit/-publish."
  [request context proposal st]
  (let [hard    (into []
                      (concat (rbac-violations request context)
                              (tolerance-violations request proposal st)
                              (source-provenance-violations request proposal st)
                              (licensed-disclosure-violations request context proposal st)))
        conf        (:confidence proposal 0.0)
        low?        (< conf confidence-floor)
        halted?     (halted-instrument? st (:subject request))
        correction? (= :correction/request (:op request))
        hard?       (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not halted?) (not correction?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? halted? correction?))
     :halted?      halted?
     :correction?  correction?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :policy-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
