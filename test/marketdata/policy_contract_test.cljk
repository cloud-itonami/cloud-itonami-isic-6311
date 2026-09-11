(ns marketdata.policy-contract-test
  "The governor contract as executable tests — the analog of
  `cloud-itonami-isic-8291`'s policy_contract_test / robotaxi's
  safety_contract_test. The single invariant under test:

    MarketData-LLM never ingests/publishes/resolves a record the
    MarketDataGovernor would reject, and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketdata.store :as store]
            [marketdata.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :feed-operator})
(def officer  {:actor-id "qo-1" :actor-role :data-quality-officer})
;; default-phase is now 1 (assisted, no auto-commit -- see phase.cljc's
;; default-phase docstring for why). Tests that specifically exercise
;; governor-clean auto-commit/escalate behavior opt into phase 3 (or, for
;; correction/request, phase 2+ where it first enters :writes) explicitly,
;; the same way phase_test.clj parameterizes phase -- they are not testing
;; "what happens with no :phase set" (that is missing-phase-context-does-
;; not-grant-max-autonomy's job, in phase_test.clj).
(def operator-p3 (assoc operator :phase 3))
(def officer-p3  (assoc officer :phase 3))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-ingest-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :quote/ingest :subject "fx-100" :instrument-id "fx-100"
                   :price 157.40M :currency :jpy :as-of "2026-07-10T12:00:00Z"
                   :source {:class :central-bank-reference-rate :ref "ecb-fx-reference-rates:usd-jpy"}}
                  operator-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 157.40M (:price (store/quote* db "fx-100"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))
    (is (= :commit (-> (store/ledger db) first :disposition)))))

(deftest unauthorized-role-is-held
  (testing "a :subscriber role has no ingest permission → HOLD, no write"
    (let [[db actor] (fresh)
          res (exec-op actor "t2"
                    {:op :quote/ingest :subject "fx-100" :instrument-id "fx-100"
                     :price 157.40M :currency :jpy :as-of "2026-07-10T12:00:00Z"
                     :source {:class :central-bank-reference-rate :ref "demo"}}
                    {:actor-id "sub-1" :actor-role :subscriber})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (= 157.32M (:price (store/quote* db "fx-100"))) "SSoT unchanged")
      (is (= [:rbac] (-> (store/ledger db) first :basis))))))

(deftest unsourced-print-is-held
  (testing "a price tick with no source citation (dropped feed header) → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :quote/ingest :subject "eq-100" :instrument-id "eq-100"
                     :price 143.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
                     :source {:class :licensed-operator-feed :ref "lic-demo:eq-100" :license-id "lic-demo"}
                     :unsourced? true}
                    operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis)))
      (is (= 142.50M (:price (store/quote* db "eq-100"))) "no print written"))))

(deftest unlicensed-feed-class-is-held
  (testing "a licensed-operator-feed citation whose license-id is inactive → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t3b"
                    {:op :quote/ingest :subject "cm-100" :instrument-id "cm-100"
                     :price 68.90M :currency :usd :as-of "2026-07-10T12:00:00Z"
                     :source {:class :licensed-operator-feed :ref "lic-expired:cm-100" :license-id "lic-expired"}}
                    operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis))))))

(deftest tolerance-breach-is-held
  (testing "a price grossly outside tolerance of the last known-good quote → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t4"
                    {:op :quote/ingest :subject "cr-100" :instrument-id "cr-100"
                     :price 6125.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
                     :source {:class :licensed-operator-feed :ref "lic-demo:cr-100" :license-id "lic-demo"}}
                    operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:tolerance-gate} (-> (store/ledger db) first :basis)))
      (is (= 61250.00M (:price (store/quote* db "cr-100")))))))

(deftest uncontracted-disclosure-is-held
  (testing "a disclosure query from a tenant with no registered contract → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t5"
                    {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100"}
                    {:actor-id "sub-2" :actor-role :subscriber :tenant "tenant-ghost"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest over-disclosure-beyond-tier-is-held
  (testing "a disclosure query pulling columns beyond the contract's tier → HOLD"
    (let [[db actor] (fresh)
          res (exec-op actor "t6"
                    {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100" :greedy? true}
                    {:actor-id "sub-1" :actor-role :subscriber :tenant "tenant-basic"})]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:licensed-disclosure} (-> (store/ledger db) first :basis))))))

(deftest clean-disclosure-within-tier-commits-directly
  (testing "a clean, in-tier disclosure query auto-serves (it's a governed read)"
    (let [[_db actor] (fresh)
          res (exec-op actor "t6b"
                    {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100"}
                    {:actor-id "sub-1" :actor-role :subscriber :tenant "tenant-basic"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest halted-instrument-ingest-escalates-then-human-decides
  (testing "an otherwise-clean ingest targeting a halted/circuit-broken instrument interrupts for human approval"
    (let [[db actor] (fresh)
          r1 (exec-op actor "t7"
                   {:op :quote/ingest :subject "eq-200" :instrument-id "eq-200"
                    :price 87.90M :currency :usd :as-of "2026-07-10T12:00:00Z"
                    :source {:class :licensed-operator-feed :ref "lic-demo:eq-200" :license-id "lic-demo"}}
                   operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval")
      (is (= :halted-instrument (-> r1 :state :audit last :reason)))
      (testing "approve → commit"
        (let [r2 (g/run* actor {:approval {:status :approved :by "quality-1"}}
                         {:thread-id "t7" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 87.90M (:price (store/quote* db "eq-200"))))
          (is (= :commit (-> (store/ledger db) last :disposition)))))))
  (testing "reject → hold"
    (let [[db actor] (fresh)
          _  (exec-op actor "t8"
                  {:op :quote/ingest :subject "eq-200" :instrument-id "eq-200"
                   :price 87.90M :currency :usd :as-of "2026-07-10T12:00:00Z"
                   :source {:class :licensed-operator-feed :ref "lic-demo:eq-200" :license-id "lic-demo"}}
                  operator)
          r2 (g/run* actor {:approval {:status :rejected :by "quality-1"}}
                     {:thread-id "t8" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (= 88.10M (:price (store/quote* db "eq-200")))))))

(deftest correction-request-always-escalates-regardless-of-confidence
  (testing "a data-quality correction request always reaches a human, never auto-resolves"
    (let [[db actor] (fresh)
          before (store/quote* db "eq-100")
          r1 (exec-op actor "t9"
                   {:op :correction/request :subject "eq-100" :disputed-field :price :claim 142.75M}
                   officer-p3)]
      (is (= :interrupted (:status r1)))
      (is (= :data-quality-dispute (-> r1 :state :audit last :reason)))
      (testing "approve → commit applies the correction"
        (let [r2 (g/run* actor {:approval {:status :approved :by "quality-1"}}
                         {:thread-id "t9" :resume? true})]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 142.75M (:price (store/quote* db "eq-100"))))))
      (testing "a second, rejected dispute leaves the quote unchanged"
        (let [[db2 actor2] (fresh)
              _  (exec-op actor2 "t10"
                      {:op :correction/request :subject "eq-100" :disputed-field :price :claim 142.75M}
                      officer-p3)
              r3 (g/run* actor2 {:approval {:status :rejected :by "quality-1"}}
                        {:thread-id "t10" :resume? true})]
          (is (= :hold (get-in r3 [:state :disposition])))
          (is (= (:price before) (:price (store/quote* db2 "eq-100")))))))))

;; ── cross-venue composite provenance (ADR-2607262100) ──────────────────
;; The direct-venue crypto path publishes a DERIVED median. That derivation
;; is only as sourced as the observations under it, so the governor
;; re-checks the constituent enumeration itself — these tests are the
;; contract for that check. Without them, `:cross-venue-composite` would be
;; a hole through which any number could be published by simply asserting
;; it was "aggregated".

(defn- constituent [venue ref]
  {:class :exchange-first-party-public-api :venue venue :ref ref
   :price 64400M :currency :usd})

(defn- composite-req [constituents]
  {:op :quote/ingest :subject "cx-btc-usd" :instrument-id "cx-btc-usd"
   :price 64385.02M :currency :usd :as-of "2026-07-26T13:06:00Z"
   :source {:class :cross-venue-composite
            :ref "cross-venue-median:cx-btc-usd:2026-07-26T13:06:00Z"
            :method :median
            :constituents constituents}})

(deftest a-well-formed-cross-venue-composite-commits
  (let [[db actor] (fresh)
        res (exec-op actor "cx1"
                     (composite-req [(constituent :binance "binance:BTCUSDT:t")
                                     (constituent :coinbase "coinbase:BTC-USD:t")
                                     {:class :dex-onchain-observation :venue :uniswap-v3-wbtc-usdc-030
                                      :ref "uniswap-v3:1:0x99ac:block-25617151"
                                      :price 64374.95 :currency :usdc}])
                     operator-p3)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 64385.02M (:price (store/quote* db "cx-btc-usd"))))))

(deftest a-composite-below-quorum-is-held
  (testing "two venues cannot outvote each other — a 2-constituent median is one venue with steps"
    (let [[db actor] (fresh)
          res (exec-op actor "cx2"
                       (composite-req [(constituent :binance "binance:BTCUSDT:t")
                                       (constituent :coinbase "coinbase:BTC-USD:t")])
                       operator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/quote* db "cx-btc-usd")) "no print written"))))

(deftest a-composite-hiding-a-licensed-vendor-tick-is-held
  (testing "a derived label must not launder licensed vendor data into publication"
    (let [[db actor] (fresh)
          res (exec-op actor "cx3"
                       (composite-req [(constituent :binance "binance:BTCUSDT:t")
                                       (constituent :coinbase "coinbase:BTC-USD:t")
                                       {:class :licensed-operator-feed :license-id "lic-demo"
                                        :ref "lic-demo:cx-btc-usd" :venue :vendor}])
                       operator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis))))))

(deftest a-composite-of-composites-is-held
  (testing "nesting would let provenance be diluted one level at a time"
    (let [[db actor] (fresh)
          res (exec-op actor "cx4"
                       (composite-req [(constituent :binance "binance:BTCUSDT:t")
                                       (constituent :coinbase "coinbase:BTC-USD:t")
                                       {:class :cross-venue-composite :ref "cross-venue-median:earlier"
                                        :venue :self}])
                       operator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis))))))

(deftest a-composite-counting-one-venue-three-times-is-held
  (testing "duplicate refs would satisfy a naive quorum count"
    (let [[db actor] (fresh)
          res (exec-op actor "cx5"
                       (composite-req (repeat 3 (constituent :binance "binance:BTCUSDT:t")))
                       operator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis))))))

(deftest a-composite-with-a-blank-ref-is-held
  (testing "an unverifiable constituent is not a constituent"
    (let [[db actor] (fresh)
          res (exec-op actor "cx6"
                       (composite-req [(constituent :binance "binance:BTCUSDT:t")
                                       (constituent :coinbase "coinbase:BTC-USD:t")
                                       (constituent :kraken "  ")])
                       operator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis))))))

(deftest a-direct-venue-quote-needs-no-feed-license
  (testing "these are the venues' own public endpoints, not a licensed vendor feed"
    (let [[db actor] (fresh)
          res (exec-op actor "cx7"
                       {:op :quote/ingest :subject "cx-eth-usd" :instrument-id "cx-eth-usd"
                        :price 1883.54 :currency :usdc :as-of "2026-07-26T13:05:47Z"
                        :source {:class :dex-onchain-observation
                                 :ref "uniswap-v3:1:0x88e6:block-25617151"}}
                       operator-p3)]
      (is (= :commit (get-in res [:state :disposition])))
      (is (= 1883.54 (:price (store/quote* db "cx-eth-usd")))))))

(deftest an-aggregator-sourced-price-has-no-class-to-cite
  (testing "the closed catalog is what structurally keeps CoinMarketCap out"
    (let [[db actor] (fresh)
          res (exec-op actor "cx8"
                       {:op :quote/ingest :subject "cx-btc-usd" :instrument-id "cx-btc-usd"
                        :price 64400M :currency :usd :as-of "2026-07-26T13:06:00Z"
                        :source {:class :price-aggregator :ref "coinmarketcap:BTC"}}
                       operator-p3)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:source-provenance-gate} (-> (store/ledger db) first :basis)))
      (is (nil? (store/quote* db "cx-btc-usd"))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :quote/ingest :subject "fx-100" :instrument-id "fx-100"
                          :price 157.40M :currency :jpy :as-of "2026-07-10T12:00:00Z"
                          :source {:class :central-bank-reference-rate :ref "demo"}}
               operator-p3)
      (exec-op actor "b" {:op :quote/ingest :subject "eq-100" :instrument-id "eq-100"
                          :price 143.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
                          :source nil :unsourced? true}
               operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
