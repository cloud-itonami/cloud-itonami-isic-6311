(ns marketdata.aggregate-test
  "Cross-venue reference price (ADR-2607262100). The prices used throughout
  are the REAL simultaneous observations captured on 2026-07-26 from the
  four venues and the Uniswap WBTC/USDC pool — so the 'happy path' test is
  a real market snapshot, and the dispersion/quorum tests perturb that same
  snapshot rather than inventing a market that never existed."
  (:require [clojure.test :refer [deftest is testing]]
            [marketdata.aggregate :as agg]))

(defn- q [venue class price currency ref & [epoch]]
  {:op :quote/ingest :subject "cx-btc-usd" :instrument-id "cx-btc-usd"
   :price price :currency currency :as-of "2026-07-26T13:05:51Z"
   :source (cond-> {:class class :ref ref :venue venue}
             epoch (assoc :raw {:epoch-seconds epoch}))})

;; the real 2026-07-26T13:05 snapshot
(def binance  (q :binance  :exchange-first-party-public-api 64424.64M :usdt "binance:BTCUSDT:2026-07-26T13:05:51.013Z" 1785071151))
(def coinbase (q :coinbase :exchange-first-party-public-api 64372.21M :usd  "coinbase:BTC-USD:2026-07-26T13:05:51.234745517Z" 1785071151))
(def kraken   (q :kraken   :exchange-first-party-public-api 64395.10M :usd  "kraken:XBTUSD:2026-07-26T13:05:52Z" 1785071152))
(def bitflyer (q :bitflyer :exchange-first-party-public-api 10554414M :jpy  "bitflyer:BTC_JPY:2026-07-26T13:05:47.343Z" 1785071147))
(def uniswap  (q :uniswap-v3-wbtc-usdc-030 :dex-onchain-observation 64374.95513619843 :usdc
                 "uniswap-v3:1:0x99ac8ca7087fa4a2a1fb6357269965a2014abc35:block-25617151" 1785071147))

(def usd-opts {:target-currency :usd :currency-equivalence {:usdt :usd :usdc :usd}})

(deftest median-of-the-real-snapshot
  (let [r (agg/reference-price [binance coinbase kraken uniswap] usd-opts)]
    (is (:ok? r))
    (is (= 4 (:venue-count r)))
    ;; sorted: 64372.21 64374.955 64395.10 64424.64 -> mean of middle two
    (is (< (Math/abs (- (:price r) 64385.02756809921)) 1e-6))
    (is (< (:dispersion r) 0.001) "the four venues agreed to within 10bp")
    (is (= :usd (:currency r)))))

(deftest a-quote-in-an-unconvertible-currency-is-excluded-not-assumed
  (testing "bitFlyer quotes JPY; with no FX rate supplied it CANNOT be counted"
    (let [r (agg/reference-price [binance coinbase kraken bitflyer] usd-opts)]
      (is (:ok? r))
      (is (= 3 (:venue-count r)) "the JPY quote is not silently treated as dollars")
      (is (= [:bitflyer] (mapv :venue (:excluded r))))
      (is (= [:no-conversion-path] (mapv :excluded (:excluded r)))))))

(deftest an-explicit-fx-rate-brings-the-jpy-venue-back-in
  (let [r (agg/reference-price [binance coinbase kraken bitflyer]
                               (assoc usd-opts :fx-rates {[:jpy :usd] 0.0061}))]
    (is (:ok? r))
    (is (= 4 (:venue-count r)))
    (is (empty? (:excluded r)))
    (testing "the conversion basis is recorded on the constituent, not lost"
      (let [bf (first (filter #(= :bitflyer (:venue %)) (:constituents r)))]
        (is (= [:fx-rate :jpy 0.0061] (:conversion bf)))
        (is (< (Math/abs (- (:converted-price bf) 64381.9254)) 1e-6))))))

(deftest declared-stablecoin-equivalence-is-visible-in-the-provenance
  (let [r (agg/reference-price [binance coinbase kraken uniswap] usd-opts)
        by-venue (into {} (map (juxt :venue identity)) (:constituents r))]
    (is (= [:declared-equivalent :usdt] (:conversion (by-venue :binance))))
    (is (= [:declared-equivalent :usdc] (:conversion (by-venue :uniswap-v3-wbtc-usdc-030))))
    (is (= :identity (:conversion (by-venue :coinbase))))))

(deftest below-quorum-nothing-is-published
  (let [r (agg/reference-price [binance coinbase] usd-opts)]
    (is (not (:ok? r)))
    (is (= :insufficient-venues (:reason r)))
    (is (nil? (:price r)) "a refusal carries NO price at all")))

(deftest venues-that-disagree-fail-closed-by-default
  (let [rogue (q :rogue :exchange-first-party-public-api 71000M :usd "rogue:BTCUSD:2026-07-26T13:05:51Z")
        r (agg/reference-price [binance coinbase kraken rogue] usd-opts)]
    (is (not (:ok? r)))
    (is (= :dispersion-exceeded (:reason r)))
    (is (nil? (:price r)))
    (testing "a CoinMarketCap-style silent outlier drop is opt-in, and audited"
      (let [r2 (agg/reference-price [binance coinbase kraken rogue]
                                    (assoc usd-opts :drop-outliers? true))]
        (is (:ok? r2))
        (is (= 3 (:venue-count r2)))
        (is (= ["rogue:BTCUSD:2026-07-26T13:05:51Z"] (mapv :ref (:dropped r2))))))))

(deftest a-stale-venue-is-excluded-when-the-caller-supplies-a-clock
  (let [now 1785071151
        stale (q :stale :exchange-first-party-public-api 64000M :usd "stale:BTCUSD:old" (- now 3600))
        r (agg/reference-price [binance coinbase kraken stale]
                               (assoc usd-opts :now-epoch-seconds now :max-age-seconds 300))]
    (is (:ok? r))
    (is (= 3 (:venue-count r)))
    (is (= [:stale] (mapv :excluded (:excluded r))))
    (testing "a venue with no parseable clock is :freshness :unknown, never faked-fresh"
      ;; the real case is Kraken, whose public ticker payload has no
      ;; timestamp field at all (marketdata.feed stamps its own observation
      ;; time and tags it :observed); here the same shape is exercised by a
      ;; constituent carrying no :epoch-seconds.
      (let [clockless (q :clockless :exchange-first-party-public-api 64390M :usd "clockless:BTCUSD:x")
            r2 (agg/reference-price [binance coinbase clockless]
                                    (assoc usd-opts :now-epoch-seconds now :max-age-seconds 300))
            c (first (filter #(= :clockless (:venue %)) (:constituents r2)))]
        (is (:ok? r2) "unknown freshness does not by itself disqualify a venue")
        (is (= :unknown (:freshness c)))
        (is (nil? (:age-seconds c)))))))

(deftest a-zero-or-missing-price-never-enters-the-median
  (let [zero (q :zero :exchange-first-party-public-api 0M :usd "zero:BTCUSD:x")
        r (agg/reference-price [binance coinbase kraken zero] usd-opts)]
    (is (= 3 (:venue-count r)))
    (is (= [:no-price] (mapv :excluded (:excluded r))))))

(deftest target-currency-is-mandatory
  (is (thrown? clojure.lang.ExceptionInfo (agg/reference-price [binance] {}))
      "there is no default currency to silently publish in"))

(deftest composite-ingest-request-enumerates-every-constituent
  (let [r (agg/reference-price [binance coinbase kraken uniswap] usd-opts)
        req (agg/composite-ingest-request "cx-btc-usd" r "2026-07-26T13:06:00Z")
        src (:source req)]
    (is (= :quote/ingest (:op req)))
    (is (= :cross-venue-composite (:class src)))
    (is (= :median (:method src)))
    (is (= 4 (count (:constituents src))))
    (testing "each constituent keeps its OWN source class and re-derivable ref"
      (is (= #{:exchange-first-party-public-api :dex-onchain-observation}
             (set (map :class (:constituents src)))))
      (is (every? :ref (:constituents src)))
      (is (= 4 (count (distinct (map :ref (:constituents src)))))))
    (testing "each constituent keeps its raw venue price AND the converted one"
      (let [bn (first (filter #(= :binance (:venue %)) (:constituents src)))]
        (is (= 64424.64M (:price bn)))
        (is (= :usdt (:currency bn)))
        (is (= 64424.64 (:converted-price bn)))))))

(deftest a-refused-reference-price-produces-no-ingest-request
  (let [r (agg/reference-price [binance coinbase] usd-opts)]
    (is (nil? (agg/composite-ingest-request "cx-btc-usd" r "2026-07-26T13:06:00Z"))
        "there is no code path from a refusal to a published quote")))

(deftest describe-reports-a-refusal-as-a-refusal
  (let [ok (agg/describe (agg/reference-price [binance coinbase kraken uniswap] usd-opts))
        no (agg/describe (agg/reference-price [binance coinbase] usd-opts))]
    (is (re-find #"^reference " ok))
    (is (re-find #"NO PRICE PUBLISHED \(insufficient-venues\)" no))))

(deftest median-handles-odd-and-even-counts
  (is (= 2 (agg/median [1 2 3])))
  (is (= 2.5 (agg/median [1 2 3 4])))
  (is (nil? (agg/median []))))
