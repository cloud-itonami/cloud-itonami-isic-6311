(ns marketdata.phase
  "Phase 0→3 staged rollout — this actor's analog of robotaxi's ODD phases
  and `cloud-itonami-isic-8291`'s rollout phases: start narrow (read-only),
  widen as trust grows. Where the MarketDataGovernor answers 'is this
  allowed?', the phase answers 'how much autonomy does the actor have
  *yet*?'. It can only ever make the actor MORE conservative than the
  governor: it downgrades a governor-clean commit to approval or hold,
  never the reverse.

    Phase 0  read-only        — no writes at all. `:disclosure/query` only
                                (still governor-gated).
    Phase 1  assisted-ingest  — `:quote/ingest` allowed, every ingest needs
                                human approval.
    Phase 2  + derived series — adds `:series/derive` and
                                `:correction/request` (still approval-only).
    Phase 3  supervised auto  — governor-clean, high-confidence
                                `:quote/ingest`/`:series/derive` may
                                auto-commit.

  `:correction/request` is deliberately NEVER a member of any phase's `:auto`
  set, at any phase — a data-quality dispute always reaches a human,
  independent of the MarketDataGovernor's own always-escalate check on the
  same op.

  `gate` runs AFTER `policy/check`, taking the governor disposition
  (:commit | :escalate | :hold) and returning the phase-adjusted disposition
  plus a reason when the phase changed it.")

(def read-ops  #{:disclosure/query})
(def write-ops #{:quote/ingest :series/derive :correction/request})

(def phases
  "phase → {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}. `:correction/request` is intentionally
  absent from every phase's `:auto` set."
  {0 {:label "read-only"        :writes #{}
                                 :auto #{}}
   1 {:label "assisted-ingest"  :writes #{:quote/ingest}
                                 :auto #{}}
   2 {:label "assisted-series"  :writes #{:quote/ingest :series/derive :correction/request}
                                 :auto #{}}
   3 {:label "supervised-auto"  :writes #{:quote/ingest :series/derive :correction/request}
                                 :auto #{:quote/ingest :series/derive}}})

(def default-phase
  "The phase used when `context` carries no :phase at all
  (marketdata.operation: (:phase context phase/default-phase)), AND the
  fallback `gate` itself uses for an unrecognized phase NUMBER
  (`(get phases phase (get phases default-phase))`). This is directly
  reachable by any ordinary caller that simply omits :phase -- not just
  malformed/malicious input -- so it must be the MOST CONSERVATIVE
  phase, never the most permissive. This was 3 (supervised-auto, where
  :quote/ingest and :series/derive can auto-commit) until a live check
  confirmed a caller who forgets :phase silently got maximum autonomy
  instead of the safe default -- the same accidental-fail-open shape
  already found and fixed this session in the shared talent.phase
  template (gftd-talent-actor) and its many siblings across
  kotoba-lang/etzhayyim. 1 (assisted-ingest, :auto empty) matches those
  fixes. :correction/request remains unaffected either way (never in
  any phase's :auto set -- a data-quality dispute always reaches a
  human)."
  1)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - reads (`:disclosure/query`) pass through unchanged (phase restricts
    write autonomy, not governed reads).
  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase → HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible → ESCALATE (:phase-approval),
    even if the governor was clean. `:correction/request` is never
    auto-eligible at any phase, so it always lands here once phase ≥ 2."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)      {:disposition :hold :reason nil}
      (contains? read-ops op)             {:disposition governor-disposition :reason nil}
      (not (contains? writes op))         {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))     {:disposition :escalate :reason :phase-approval}
      :else                               {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a MarketDataGovernor verdict to a base disposition before the phase
  gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
