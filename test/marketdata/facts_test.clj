(ns marketdata.facts-test
  "The R0 source catalog is the whole ground truth for the source-
  provenance gate — these tests guard its own internal honesty (every class
  it advertises is actually backed by a catalog entry, no duplicate/
  aspirational entries)."
  (:require [clojure.test :refer [deftest is testing]]
            [marketdata.facts :as facts]))

(deftest catalog-entries-are-well-formed
  (doseq [{:keys [id name asset-classes class access]} facts/catalog]
    (testing (str id)
      (is (keyword? id))
      (is (string? name))
      (is (set? asset-classes))
      (is (seq asset-classes))
      (is (keyword? class))
      (is (keyword? access)))))

(deftest allowed-source-classes-matches-catalog
  (is (= (into #{} (map :class facts/catalog)) facts/allowed-source-classes)))

(deftest class-allowed?-rejects-unlisted-classes
  (is (facts/class-allowed? :central-bank-reference-rate))
  (is (facts/class-allowed? :government-energy-data))
  (is (facts/class-allowed? :government-statistical-index))
  (is (facts/class-allowed? :licensed-operator-feed))
  (is (not (facts/class-allowed? :inference)))
  (is (not (facts/class-allowed? :scraped)))
  (is (not (facts/class-allowed? nil))))

(deftest licensed-feed-class-recognized
  (is (facts/licensed-feed-class? :licensed-operator-feed))
  (is (not (facts/licensed-feed-class? :central-bank-reference-rate))))

(deftest coverage-is-honest-not-aspirational
  (let [c (facts/coverage)]
    ;; the catalog is a handful of real sources plus one structural
    ;; licensed-feed class, not "全アセットクラスの生きたレート" — this test
    ;; fails loudly if someone pads the catalog with unverifiable entries.
    (is (= (count facts/catalog) (:source-count c)))
    (is (<= (:source-count c) 20) "R0 catalog should stay small and citable, not bulk-padded")
    (is (contains? (:asset-classes c) :fx))
    (is (contains? (:asset-classes c) :real-estate-index))
    (is (= #{:ecb-fx-reference-rates :us-eia-energy-open-data :fred-case-shiller-hpi
             :binance-public-market-data :coinbase-exchange-public-market-data
             :kraken-public-market-data :bitflyer-public-market-data
             :uniswap-v3-onchain-pool}
           (:free-public-sources c))
        "exactly the 3 official reference sources + the 5 direct crypto venues — every one
         of which is a real, keyless, first-party endpoint someone else can call today")))

;; ── direct-venue crypto classes (ADR-2607262100) ───────────────────────

(deftest no-aggregator-class-exists
  (testing "the closed catalog is the structural reason this actor cannot republish an aggregator"
    (doseq [c [:price-aggregator :coinmarketcap :coingecko :cryptocompare :vendor-blend :inference]]
      (is (not (facts/class-allowed? c)) (str c " must have no catalog class to cite")))))

(deftest direct-venue-classes-need-no-feed-license
  (is (facts/direct-venue-class? :exchange-first-party-public-api))
  (is (facts/direct-venue-class? :dex-onchain-observation))
  (is (not (facts/licensed-feed-class? :exchange-first-party-public-api)))
  (is (not (facts/direct-venue-class? :licensed-operator-feed))
      "a licensed vendor tick is not a direct-venue observation")
  (is (not (facts/direct-venue-class? :cross-venue-composite))
      "a derived median is not an observation either — which is what blocks nesting"))

(deftest composite-constituents-must-be-a-real-quorum-of-direct-observations
  (let [ok {:class :cross-venue-composite
            :constituents [{:class :exchange-first-party-public-api :ref "binance:BTCUSDT:t"}
                           {:class :exchange-first-party-public-api :ref "coinbase:BTC-USD:t"}
                           {:class :dex-onchain-observation :ref "uniswap-v3:1:0x99ac:block-1"}]}]
    (is (facts/composite-constituents-ok? ok))
    (testing "below quorum"
      (is (not (facts/composite-constituents-ok? (update ok :constituents butlast)))))
    (testing "a licensed vendor tick smuggled in as a constituent"
      (is (not (facts/composite-constituents-ok?
                (assoc-in ok [:constituents 2] {:class :licensed-operator-feed :ref "lic:x"})))))
    (testing "a nested composite"
      (is (not (facts/composite-constituents-ok?
                (assoc-in ok [:constituents 2] {:class :cross-venue-composite :ref "earlier"})))))
    (testing "the same venue counted three times"
      (is (not (facts/composite-constituents-ok?
                (assoc ok :constituents (vec (repeat 3 {:class :exchange-first-party-public-api
                                                        :ref "binance:BTCUSDT:t"})))))))
    (testing "a constituent with no verifiable ref"
      (is (not (facts/composite-constituents-ok?
                (assoc-in ok [:constituents 2 :ref] "   ")))))
    (testing "no constituents at all"
      (is (not (facts/composite-constituents-ok? {:class :cross-venue-composite}))))))
