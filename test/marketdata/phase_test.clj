(ns marketdata.phase-test
  "Phase 0→3 staged rollout through the OperationActor. The phase can only
  make the actor MORE conservative than the governor: hold writes that
  aren't enabled yet, force human approval before auto-commit is unlocked."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [marketdata.store :as store]
            [marketdata.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :feed-operator})
(def officer  {:actor-id "qo-1" :actor-role :data-quality-officer})

(def clean-ingest
  {:op :quote/ingest :subject "fx-100" :instrument-id "fx-100"
   :price 157.40M :currency :jpy :as-of "2026-07-10T12:00:00Z"
   :source {:class :central-bank-reference-rate :ref "ecb-fx-reference-rates:usd-jpy"}})

(def clean-derive
  {:op :series/derive :subject "eq-100" :series-id "eq-100-daily" :instrument-id "eq-100"
   :bars [{:o 140M :h 146M :l 139M :c 142.50M :date "2026-07-10"}]
   :source {:class :licensed-operator-feed :ref "lic-demo" :license-id "lic-demo"}})

(def clean-disclosure
  {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100"})

(def correction-req
  {:op :correction/request :subject "eq-100" :disputed-field :price :claim 142.75M})

(defn- run [phase req ctx]
  (let [s (store/seed-db)
        actor (op/build s)]
    [s (g/run* actor {:request req :context (assoc ctx :phase phase)}
               {:thread-id (str "ph-" phase "-" (:op req))})]))

(deftest phase0-holds-all-writes
  (let [[s res] (run 0 clean-ingest operator)]
    (is (= :hold (get-in res [:state :disposition])))
    (is (= :phase-disabled (-> (store/ledger s) first :phase-reason)))
    (is (= 157.32M (:price (store/quote* s "fx-100"))) "SSoT untouched in phase 0")))

(deftest phase0-allows-governed-reads
  (testing "disclosure/query is a read → phase 0 lets it through (governor still applies)"
    (let [[_ res] (run 0 clean-disclosure {:actor-id "sub-1" :actor-role :subscriber :tenant "tenant-basic"})]
      (is (= :commit (get-in res [:state :disposition]))))))

(deftest phase1-forces-approval-on-clean-ingest
  (testing "a clean ingest that auto-commits in phase 3 must go to a human in phase 1"
    (let [[_ res] (run 1 clean-ingest operator)]
      (is (= :interrupted (:status res)))
      (is (= :phase-approval (-> res :state :audit last :reason))))))

(deftest phase2-enables-series-derive-under-approval
  (let [[_ res] (run 2 clean-derive operator)]
    (is (= :interrupted (:status res)))
    (is (= :phase-approval (-> res :state :audit last :reason)))))

(deftest missing-phase-context-does-not-grant-max-autonomy
  ;; default-phase is the fallback both when :phase is entirely absent
  ;; from context (marketdata.operation) and when an unrecognized phase
  ;; number is passed (phase/gate). It used to be 3 -- where a clean
  ;; :quote/ingest can auto-commit -- so a caller that simply forgot to
  ;; set :phase silently got MAXIMUM autonomy instead of the safe
  ;; "start narrow" default.
  (testing "omitting :phase from context still requires human approval on a clean ingest"
    (let [s (store/seed-db)
          actor (op/build s)
          res (g/run* actor {:request clean-ingest :context operator} {:thread-id "mp"})]
      (is (not= :commit (get-in res [:state :disposition]))
          "a clean ingest must not auto-commit when :phase is unset")
      (is (= 157.32M (:price (store/quote* s "fx-100"))) "SSoT untouched without explicit phase"))))

(deftest phase3-auto-commits-clean-ingest
  (let [[s res] (run 3 clean-ingest operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= 157.40M (:price (store/quote* s "fx-100"))))))

(deftest governor-hold-beats-phase
  (testing "a hard governor violation (out-of-tolerance price) holds even in the most permissive phase"
    (let [[_ res] (run 3 {:op :quote/ingest :subject "cr-100" :instrument-id "cr-100"
                          :price 6125.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
                          :source {:class :licensed-operator-feed :ref "lic-demo" :license-id "lic-demo"}}
                       operator)]
      (is (= :hold (get-in res [:state :disposition]))))))

(deftest correction-request-never-auto-commits-at-any-phase
  (testing "a data-quality correction never reaches :commit without an explicit human :approval"
    (doseq [ph [0 1 2 3]]
      (let [[_ res] (run ph correction-req officer)]
        (is (not= :commit (get-in res [:state :disposition]))
            (str "phase " ph " must not auto-commit a correction"))))))
