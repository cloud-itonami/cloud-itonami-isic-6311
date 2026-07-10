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

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(deftest authorized-ingest-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :quote/ingest :subject "fx-100" :instrument-id "fx-100"
                   :price 157.40M :currency :jpy :as-of "2026-07-10T12:00:00Z"
                   :source {:class :central-bank-reference-rate :ref "ecb-fx-reference-rates:usd-jpy"}}
                  operator)]
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
                   officer)]
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
                      officer)
              r3 (g/run* actor2 {:approval {:status :rejected :by "quality-1"}}
                        {:thread-id "t10" :resume? true})]
          (is (= :hold (get-in r3 [:state :disposition])))
          (is (= (:price before) (:price (store/quote* db2 "eq-100")))))))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations → N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :quote/ingest :subject "fx-100" :instrument-id "fx-100"
                          :price 157.40M :currency :jpy :as-of "2026-07-10T12:00:00Z"
                          :source {:class :central-bank-reference-rate :ref "demo"}}
               operator)
      (exec-op actor "b" {:op :quote/ingest :subject "eq-100" :instrument-id "eq-100"
                          :price 143.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
                          :source nil :unsourced? true}
               operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
