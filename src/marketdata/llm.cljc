(ns marketdata.llm
  "MarketData-LLM client — the *contained intelligence node*.

  It normalizes incoming feed ticks into the canonical quote schema, drafts
  derived series (e.g. daily OHLC bars) from already-committed quotes,
  proposes subscriber disclosure column sets, and drafts data-quality
  correction resolutions. CRITICAL: it is a smart-but-untrusted advisor. It
  returns a *proposal* (with a rationale + the fields/source it cited),
  never a committed or published record. Every output is censored
  downstream by `marketdata.policy` (the MarketDataGovernor) before
  anything touches the SSoT or is disclosed to a subscriber.

  Like `cloud-itonami-isic-8291`'s Dossier-LLM, this is a deterministic mock
  so the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm) with the
  same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why — SCANNED by the source-provenance gate
     :cites      [kw|str ..]    ; fields/attrs the LLM used
     :source     {:class kw :ref str :license-id str?}|nil ; SCANNED by source-provenance
     :effect     kw             ; how a commit would mutate the SSoT
     :value      map|nil        ; the record/series patch, for ingest/derive/correction
     :columns    [kw ..]|nil    ; proposed disclosure column set
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.model :as model]
            [marketdata.store :as store]))

(defn- propose-ingest
  "Feed-tick ingestion — the LLM only normalizes/validates the tick (adds no
  new provenance). `:unsourced?` injects the failure mode we must defend
  against: a print arriving with no source citation at all (a dropped/
  corrupted feed header) — the MarketDataGovernor's source-provenance-gate
  must reject this outright, regardless of how confident the LLM is."
  [_db {:keys [instrument-id price currency as-of source unsourced?]}]
  (let [src (when-not unsourced? source)]
    {:summary   (str "quote ingest: " instrument-id " @ " price)
     :rationale "出典引用済みフィードtickの正規化のみ。新規事実の生成なし。"
     :cites     [:instrument-id :price :currency :as-of]
     :source    src
     :effect    :quote-upsert
     :value     {:instrument-id instrument-id :price price :currency currency
                 :as-of as-of :source src}
     ;; deliberately HIGH confidence even when unsourced? — proves the hard
     ;; source-provenance gate does not care about confidence at all.
     :confidence (if unsourced? 0.9 0.95)}))

(defn- propose-derive
  "Derived-series draft (e.g. daily OHLC bars) from already-committed
  quotes. `series-id`/`instrument-id`/`bars` are supplied by the caller (a
  scheduled aggregation job in production); the LLM's role is limited to
  validating bar shape and citing the aggregation basis."
  [_db {:keys [series-id instrument-id bars source]}]
  {:summary   (str "series derive: " series-id " (" (count bars) " bars)")
   :rationale "既存コミット済み quote からの集計のみ。"
   :cites     [:series-id :instrument-id :bars]
   :source    source
   :effect    :series-upsert
   :value     {:series-id series-id :instrument-id instrument-id :bars bars :source source}
   :confidence 0.9})

(defn- propose-disclosure
  "Disclosure column-set proposal for a licensed subscriber query.
  `:greedy?` injects over-disclosure (pulls `:series`/`:raw-source` columns
  beyond a basic-tier contract) — the MarketDataGovernor's
  licensed-disclosure gate must reject the excess columns."
  [_db {:keys [instrument-id greedy?]}]
  (let [base [:instrument-id :symbol :asset-class :price :currency :as-of]
        greedy-extra [:series :raw-source]]
    {:summary   (str "開示列提案: " instrument-id)
     :rationale (if greedy? "分析に有用そうな列を広めに含めた。" "契約 tier に必要な最小列のみ。")
     :cites     base
     :source    nil
     :effect    :disclosure-serve
     :columns   (if greedy? (into base greedy-extra) base)
     :confidence 0.9}))

(defn- propose-correction
  "Data-quality correction/dispute resolution draft (e.g. a duplicate or
  fat-finger print flagged after the fact by a subscriber or the ingest
  pipeline itself). This NEVER auto-applies — `marketdata.policy` and
  `marketdata.phase` both structurally force every `:correction/request` to
  human review, independent of confidence."
  [_db {:keys [disputed-field claim]}]
  {:summary   (str "quote の " disputed-field " について訂正申立てへの解決案ドラフト")
   :rationale (str "申立て内容: " claim "。裏取りは人間レビューで行う。")
   :cites     [disputed-field]
   :source    nil
   :effect    :correction-apply
   :value     {:patch {disputed-field claim}}
   :confidence 0.5})

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :quote/ingest        (propose-ingest db request)
    :series/derive       (propose-derive db request)
    :disclosure/query    (propose-disclosure db request)
    :correction/request  (propose-correction db request)
    {:summary "未対応の操作" :rationale (str op) :cites [] :source nil
     :effect :noop :confidence 0.0}))

;; ───────────────────────── Advisor protocol ─────────────────────────
;; The advisor is injected into the OperationActor, so the contained
;; intelligence node is a swap: a deterministic mock for dev/tests, or a real
;; LLM in production. Either way its output is a PROPOSAL the
;; MarketDataGovernor still censors — the single invariant never depends on
;; which advisor ran.

(defprotocol Advisor
  (-advise [advisor store request] "store + request → proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは市場データ(株式/為替/コモディティ/暗号資産/不動産指数)の"
       "取込・集計アドバイザーです。与えられた事実のみに基づき、提案を1つだけ "
       "EDN マップで返します。説明や前置きは一切書かず、EDN だけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) :source({:class .. :ref .. :license-id? ..}か nil) "
       ":effect(:quote-upsert|:series-upsert|:disclosure-serve|:correction-apply) "
       ":value(該当マップ) :confidence(0..1)。\n"
       "重要: 出典(:source)を伴わない価格・系列は絶対に提案してはいけません。"
       "許容乖離を超える価格の妥当性判断や、取引停止/サーキットブレーカー中の"
       "銘柄への配信可否はあなたの責務ではありません(governor が判定します)。"))

(defn- facts-for [st {:keys [op subject instrument-id]}]
  (case op
    :disclosure/query {:instrument (store/instrument st (or instrument-id subject))
                       :quote (store/quote* st (or instrument-id subject))}
    {:instrument (store/instrument st (or instrument-id subject))}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure yields
  a safe low-confidence noop so the MarketDataGovernor escalates/holds — an
  LLM hiccup can never auto-commit or auto-publish."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :source nil :effect :noop :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference). Pass
  `model/anthropic-model`, an OpenAI-compatible model (Ollama/vLLM/kotoba), or
  `model/mock-model` for offline tests. `gen-opts` is forwarded to -generate."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record — the LLM's interpretable rationale is a
  key asset (dispute appeals, audits). Persisted to the :audit channel."
  [request proposal]
  {:t          :marketdatallm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :source     (:source proposal)
   :confidence (:confidence proposal)})
