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
    (is (= 3 (count (:free-public-sources c))) "exactly the 3 real, free, official sources")))
