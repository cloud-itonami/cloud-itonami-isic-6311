(ns marketdata.venues-test
  "Venue registry + the pure ingest-request shaper (ADR-2607262100).
  Parsing of the venues' real payloads lives in `marketdata.feed-test`;
  this file covers the parts that must hold with no network and no host."
  (:require [clojure.test :refer [deftest is testing]]
            [marketdata.facts :as facts]
            [marketdata.venues :as venues]))

(deftest every-listing-points-at-a-registered-venue
  (doseq [{:keys [venue]} venues/listings]
    (is (venues/venue venue) (str venue " must exist in the venue registry"))))

(deftest every-venue-is-keyless-and-cites-its-own-documentation
  (doseq [v venues/venues]
    (is (false? (:key-required? v)) (str (:id v) " is a public keyless endpoint"))
    (is (re-find #"^https://" (:endpoint-doc v)) (str (:id v) " cites its published API docs"))))

(deftest listed-quote-currencies-are-the-venues-actual-ones
  (testing "no venue's quote asset is laundered into :usd at the registry level"
    (is (= :usdt (:currency (first (filter #(= :binance (:venue %)) venues/listings))))
        "Binance's BTCUSDT is quoted in USDT, not USD")
    (is (= :jpy (:currency (first (filter #(= :bitflyer (:venue %)) venues/listings))))
        "bitFlyer's BTC_JPY is quoted in yen")))

(deftest ticker-urls-are-the-venues-own-endpoints
  (is (= "https://api.binance.com/api/v3/ticker/24hr?symbol=BTCUSDT"
         (venues/ticker-url :binance "BTCUSDT")))
  (is (= "https://api.exchange.coinbase.com/products/BTC-USD/ticker"
         (venues/ticker-url :coinbase "BTC-USD")))
  (is (= "https://api.kraken.com/0/public/Ticker?pair=XBTUSD"
         (venues/ticker-url :kraken "XBTUSD")))
  (is (= "https://api.bitflyer.com/v1/ticker?product_code=BTC_JPY"
         (venues/ticker-url :bitflyer "BTC_JPY")))
  (testing "no aggregator host appears anywhere in this actor's crypto path"
    (doseq [{:keys [venue]} venues/listings]
      (let [u (venues/ticker-url venue "X")]
        (is (not (re-find #"coinmarketcap|coingecko|cryptocompare|nomics" u))))))
  (is (thrown? clojure.lang.ExceptionInfo (venues/ticker-url :unknown-venue "X"))))

(deftest venue-ingest-request-shapes-a-governable-quote
  (let [listing (first (filter #(and (= :coinbase (:venue %))
                                     (= "cx-btc-usd" (:instrument-id %)))
                               venues/listings))
        req (venues/venue-ingest-request
             listing {:price 64372.21M :as-of "2026-07-26T13:05:51.234745517Z"
                      :as-of-source :venue :epoch-seconds 1785071151
                      :bid 64372.21M :ask 64372.22M :volume 1821.87214427M})]
    (is (= :quote/ingest (:op req)))
    (is (= "cx-btc-usd" (:instrument-id req)))
    (is (= :usd (:currency req)))
    (is (= :exchange-first-party-public-api (get-in req [:source :class])))
    (is (facts/class-allowed? (get-in req [:source :class]))
        "the class it cites must be one the governor actually accepts")
    (is (= "coinbase:BTC-USD:2026-07-26T13:05:51.234745517Z" (get-in req [:source :ref])))
    (is (= 1785071151 (get-in req [:source :raw :epoch-seconds])))
    (is (= :venue (get-in req [:source :raw :as-of-source])))))

(deftest venue-ingest-request-refuses-a-non-price
  (let [listing (first venues/listings)]
    (is (nil? (venues/venue-ingest-request listing {:price 0M :as-of "x"})))
    (is (nil? (venues/venue-ingest-request listing {:price -1M :as-of "x"})))
    (is (nil? (venues/venue-ingest-request listing {:as-of "x"}))
        "a venue that answered without a price contributes nothing, not a zero")))

(deftest observed-timestamps-stay-labelled-as-observed
  (let [listing (first (filter #(= :kraken (:venue %)) venues/listings))
        req (venues/venue-ingest-request listing {:price 64395.10M :as-of "2026-07-26T13:05:52Z"
                                                  :as-of-source :observed})]
    (is (= :observed (get-in req [:source :raw :as-of-source]))
        "Kraken's payload has no clock; the substitute must not look venue-authoritative")))

(deftest describe-is-generated-from-the-registry
  (let [d (venues/describe)]
    (is (re-find #"4 venues" d))
    (is (re-find #"8 listings" d))
    (is (re-find #"Binance" d))))
