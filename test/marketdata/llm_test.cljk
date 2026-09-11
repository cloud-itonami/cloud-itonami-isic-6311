(ns marketdata.llm-test
  "MarketData-LLM proposal generation, unit-level (no governor/actor
  involved — that integration is covered by policy_contract_test)."
  (:require [clojure.test :refer [deftest is testing]]
            [marketdata.store :as store]
            [marketdata.llm :as llm]))

(deftest ingest-proposal-carries-source-and-cites
  (let [db (store/seed-db)
        p (llm/infer db {:op :quote/ingest :subject "eq-100" :instrument-id "eq-100"
                         :price 143.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
                         :source {:class :licensed-operator-feed :ref "demo" :license-id "lic-demo"}})]
    (is (= :quote-upsert (:effect p)))
    (is (= {:class :licensed-operator-feed :ref "demo" :license-id "lic-demo"} (:source p)))
    (is (>= (:confidence p) 0.9))))

(deftest unsourced-ingest-proposal-carries-nil-source
  (testing "the LLM layer does not filter — that is the governor's job; this only proves the injected failure mode actually reaches the proposal"
    (let [db (store/seed-db)
          p (llm/infer db {:op :quote/ingest :subject "eq-100" :instrument-id "eq-100"
                           :price 143.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
                           :source {:class :licensed-operator-feed :ref "demo" :license-id "lic-demo"}
                           :unsourced? true})]
      (is (nil? (:source p)))
      (is (>= (:confidence p) 0.85) "still high-confidence — proves source-provenance cannot rely on confidence as a proxy"))))

(deftest derive-proposal-carries-bars-and-source
  (let [db (store/seed-db)
        p (llm/infer db {:op :series/derive :subject "eq-100" :series-id "eq-100-daily"
                         :instrument-id "eq-100"
                         :bars [{:o 140M :h 146M :l 139M :c 142.50M :date "2026-07-10"}]
                         :source {:class :licensed-operator-feed :ref "demo" :license-id "lic-demo"}})]
    (is (= :series-upsert (:effect p)))
    (is (= 1 (count (get-in p [:value :bars]))))))

(deftest disclosure-proposal-greedy-adds-extra-columns
  (let [db (store/seed-db)
        clean (llm/infer db {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100"})
        greedy (llm/infer db {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100" :greedy? true})]
    (is (< (count (:columns clean)) (count (:columns greedy))))
    (is (some #{:series :raw-source} (:columns greedy)))))

(deftest correction-proposal-never-marks-high-confidence
  (let [db (store/seed-db)
        p (llm/infer db {:op :correction/request :subject "eq-100" :disputed-field :price :claim 142.75M})]
    (is (= :correction-apply (:effect p)))
    (is (< (:confidence p) 0.9) "corrections are claims pending human verification, never auto-confident")))
