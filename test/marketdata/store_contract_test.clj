(ns marketdata.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and the
  Datomic-backed (langchain.db) store satisfy the same contract is what
  makes 'swap the SSoT for Datomic' a configuration change, not a rewrite."
  (:require [clojure.test :refer [deftest is testing]]
            [marketdata.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "DEMOA" (:symbol (store/instrument s "eq-100"))))
      (is (= :equity (:asset-class (store/instrument s "eq-100"))))
      (is (= :halted (:status (store/instrument s "eq-200"))))
      (is (true? (:circuit-breaker? (store/instrument s "eq-200"))))
      (is (= 142.50M (:price (store/quote* s "eq-100"))))
      (is (= {:class :central-bank-reference-rate :ref "ecb-fx-reference-rates:usd-jpy"}
             (:source (store/quote* s "fx-100")))
          "source citation round-trips (stored as EDN on Datomic, not a sub-entity)")
      (is (= "Demo Exchange Data Vendor (fictitious)" (:provider (store/feed-license s "lic-demo"))))
      (is (true? (:active? (store/feed-license s "lic-demo"))))
      (is (false? (:active? (store/feed-license s "lic-expired"))))
      (is (= 6 (count (store/all-instruments s)))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "quote upsert replaces the latest price"
        (store/commit-record! s {:effect :quote-upsert
                                 :value {:instrument-id "eq-100" :price 145.00M :currency :usd
                                         :as-of "2026-07-11T00:00:00Z"
                                         :source {:class :licensed-operator-feed :ref "lic-demo:eq-100"
                                                   :license-id "lic-demo"}}})
        (is (= 145.00M (:price (store/quote* s "eq-100")))))
      (testing "series upsert commits derived bars"
        (store/commit-record! s {:effect :series-upsert
                                 :value {:series-id "eq-100-daily" :instrument-id "eq-100"
                                         :bars [{:o 140M :h 146M :l 139M :c 145M :date "2026-07-10"}]
                                         :source {:class :licensed-operator-feed :ref "lic-demo" :license-id "lic-demo"}}})
        (is (= 1 (count (:bars (store/series s "eq-100-daily"))))))
      (testing "correction-apply patches the target quote"
        (store/commit-record! s {:effect :correction-apply
                                 :value {:patch {:price 144.50M}}
                                 :path ["eq-100"]})
        (is (= 144.50M (:price (store/quote* s "eq-100")))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (take-last 2 (store/ledger s)))))))))

(deftest contract-lookup
  (doseq [[label s] (backends)]
    (testing label
      (is (= :tier/pro (:tier (store/contract s "tenant-acme"))))
      (is (true? (:active? (store/contract s "tenant-acme"))))
      (is (nil? (store/contract s "tenant-ghost"))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/instrument s "nope")))
    (is (= [] (store/all-instruments s)))
    (is (= [] (store/ledger s)))
    (store/with-instruments s {"x" {:id "x" :symbol "X" :asset-class :equity}})
    (is (= "X" (:symbol (store/instrument s "x"))))))
