(ns marketdata.sim
  "Demo runner: push six representative operations through one
  OperationActor and watch the MarketDataGovernor + approval workflow earn
  the MarketData-LLM the right to ingest, publish or resolve a dispute.

    op1  ECB参照レート更新(出典あり・許容範囲内)          → commit
    op2  価格 tick が出典なし(フィード欠落/ハルシネーション) → source-provenance REJECT → hold
    op3  開示クエリが tier/basic 契約なのに series/raw-source を要求 → licensed-disclosure REJECT → hold
    op3a 開示クエリが未契約 tenant から                    → licensed-disclosure REJECT → hold
    op4  取引停止(サーキットブレーカー発動中)銘柄への価格ingest → 人間承認へ escalate → approve → commit
    op5  データ品質の訂正申立て(どの phase でも常に人間レビュー) → escalate → approve → commit
    op6  価格が許容乖離(15%)を大幅超過(誤発注/桁間違い疑い)  → tolerance-gate REJECT → hold

  Run: clojure -M:dev:run"
  (:require [langgraph.graph :as g]
            [marketdata.store :as store]
            [marketdata.operation :as op]
            [marketdata.facts :as facts]
            [marketdata.report :as report]))

(defn- line [& xs] (println (apply str xs)))

(defn- run-op!
  "Run one operation on its own thread-id. If it interrupts for human
  approval, a data-quality officer 'approves' and we resume."
  [actor thread-id request context approve?]
  (let [res (g/run* actor {:request request :context context} {:thread-id thread-id})]
    (if (= :interrupted (:status res))
      (do (line "   ⏸  人間レビュー待ち (reason: "
                (-> res :state :audit last :reason) ")")
          (let [res2 (g/run* actor
                             {:approval {:status (if approve? :approved :rejected)
                                         :by "quality-1"}}
                             {:thread-id thread-id :resume? true})]
            (line "   ▶  " (if approve? "承認 → " "却下 → ") "disposition = "
                  (get-in res2 [:state :disposition]))
            res2))
      (do (line "   → disposition = " (get-in res [:state :disposition])
                "  (confidence " (get-in res [:state :verdict :confidence]) ")")
          res))))

(defn -main [& _]
  (let [db    (store/seed-db)
        actor (op/build db)
        operator {:actor-id "op-1" :actor-role :feed-operator}
        officer  {:actor-id "qo-1" :actor-role :data-quality-officer}]

    (line "── R0 出典カバレッジ(正直な現状) ──")
    (line (pr-str (facts/coverage)))

    (line "\n── OperationActor (MarketData-LLM sealed; MarketDataGovernor active) ──")

    (line "\nop1  ECB参照レート更新(出典あり・許容範囲内)")
    (run-op! actor "op1"
             {:op :quote/ingest :subject "fx-100" :instrument-id "fx-100"
              :price 157.40M :currency :jpy :as-of "2026-07-10T12:00:00Z"
              :source {:class :central-bank-reference-rate :ref "ecb-fx-reference-rates:usd-jpy"}}
             operator true)

    (line "\nop2  価格tick — MarketData-LLM が出典なしで提案(フィード欠落)")
    (run-op! actor "op2"
             {:op :quote/ingest :subject "eq-100" :instrument-id "eq-100"
              :price 143.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
              :source {:class :licensed-operator-feed :ref "lic-demo:eq-100" :license-id "lic-demo"}
              :unsourced? true}
             operator true)

    (line "\nop3  開示クエリ(tier/basic 契約なのに series/raw-source まで要求)")
    (run-op! actor "op3"
             {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100" :greedy? true}
             {:actor-id "sub-1" :actor-role :subscriber :tenant "tenant-basic"} true)

    (line "\nop3a 開示クエリ(登録されていない tenant から)")
    (run-op! actor "op3a"
             {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100"}
             {:actor-id "sub-2" :actor-role :subscriber :tenant "tenant-ghost"} true)

    (line "\nop4  取引停止(サーキットブレーカー発動中)銘柄への価格ingest(出典・許容範囲は正常でも人間承認)")
    (run-op! actor "op4"
             {:op :quote/ingest :subject "eq-200" :instrument-id "eq-200"
              :price 87.90M :currency :usd :as-of "2026-07-10T12:00:00Z"
              :source {:class :licensed-operator-feed :ref "lic-demo:eq-200" :license-id "lic-demo"}}
             operator true)

    (line "\nop5  データ品質の訂正申立て — 誤って記録された価格の是正(どの phase でも常に人間レビュー)")
    (run-op! actor "op5"
             {:op :correction/request :subject "eq-100" :disputed-field :price :claim 142.75M}
             officer true)

    (line "\nop6  価格が許容乖離(15%)を大幅超過(桁間違い/誤発注疑い)")
    (run-op! actor "op6"
             {:op :quote/ingest :subject "cr-100" :instrument-id "cr-100"
              :price 6125.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
              :source {:class :licensed-operator-feed :ref "lic-demo:cr-100" :license-id "lic-demo"}}
             operator true)

    (line "\n── 開示(governor が承認した tier/basic 列のみ) ──")
    (line (pr-str (report/render-quote db "eq-100" [:instrument-id :symbol :price :currency :as-of])))

    (line "\n── 監査台帳 (append-only; 誰が・何を・どの契約/出典で ingest/開示したか) ──")
    (doseq [f (store/ledger db)]
      (line "  " (store/ledger-line f)))

    (line "\ndone.")))
