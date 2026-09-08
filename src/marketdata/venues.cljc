(ns marketdata.venues
  "The centralized-exchange half of this actor's direct-venue crypto
  collection (ADR-2607262100): which trading venues this actor reads, which
  of their own public market-data endpoints it reads, and the pure shaping
  of an observed tick into a `:quote/ingest` request.

  **No aggregator, ever.** Every venue here is read from that venue's OWN
  first-party public market-data API — the exchange publishing its own
  book. This actor does not call CoinMarketCap, CoinGecko, CryptoCompare or
  any other price aggregator, and `marketdata.facts/catalog` deliberately
  has no source class that would let one in: a re-published third-party
  price cannot cite `:exchange-first-party-public-api` truthfully, and the
  source-provenance-gate accepts nothing outside the closed catalog. The
  point is that provenance stays one hop from the print — `venue:symbol:
  timestamp`, re-checkable by the subscriber against the same public
  endpoint — instead of 'an aggregator's opinion of a blend of venues'.

  **No feed license is required for these**, unlike `:licensed-operator-
  feed`: these are the venues' own documented, keyless, public endpoints.
  What each venue's terms permit for REDISTRIBUTION is a separate question
  the operator answers per venue in `docs/operator-guide.md` — this
  namespace only records where the data comes from.

  Pure `.cljc` with no host dependency: the URL builders and the
  `venue-ingest-request` shaper run anywhere. The JSON parsing and HTTP
  live in `marketdata.feed` (JVM-only, `:test`/`:feed` aliases), same
  zero-dep-core discipline as the ECB/EIA/FRED connectors."
  (:require [kotoba.lang.text :as str]))

;; ───────────────────────── venue registry ─────────────────────────────

(def venues
  "Each venue's own public market-data endpoint. `:doc-url` is the venue's
  published API documentation — the citable basis for the endpoint shape,
  the same discipline `marketdata.facts/catalog` applies to ECB/EIA/FRED.
  All four were called live during development (2026-07-26) and their real
  response bodies are the fixtures in `test/marketdata/feed_test.clj`."
  [{:id :binance
    :name "Binance"
    :endpoint-doc "https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints"
    :endpoint "https://api.binance.com/api/v3/ticker/24hr"
    :key-required? false}
   {:id :coinbase
    :name "Coinbase Exchange"
    :endpoint-doc "https://docs.cdp.coinbase.com/exchange/reference/exchangerestapi_getproductticker"
    :endpoint "https://api.exchange.coinbase.com/products"
    :key-required? false}
   {:id :kraken
    :name "Kraken"
    :endpoint-doc "https://docs.kraken.com/api/docs/rest-api/get-ticker-information"
    :endpoint "https://api.kraken.com/0/public/Ticker"
    :key-required? false}
   {:id :bitflyer
    :name "bitFlyer"
    :endpoint-doc "https://lightning.bitflyer.com/docs?lang=en#ticker"
    :endpoint "https://api.bitflyer.com/v1/ticker"
    :key-required? false}])

(defn venue [id] (first (filter #(= id (:id %)) venues)))

;; ───────────────────────── listings ───────────────────────────────────

(def listings
  "venue × instrument. `:currency` is the venue's ACTUAL quote asset, not
  what it is colloquially called: Binance's BTCUSDT is quoted in USDT (a
  stablecoin), Coinbase's BTC-USD in real USD, bitFlyer's BTC_JPY in yen.
  Collapsing USDT/USDC into USD is an operator-declared equivalence in
  `marketdata.aggregate`, made explicitly and visibly — never silently
  here, where it would become an invisible assumption inside a published
  price."
  [{:venue :binance  :instrument-id "cx-btc-usd" :venue-symbol "BTCUSDT" :currency :usdt}
   {:venue :coinbase :instrument-id "cx-btc-usd" :venue-symbol "BTC-USD" :currency :usd}
   {:venue :kraken   :instrument-id "cx-btc-usd" :venue-symbol "XBTUSD"  :currency :usd}
   {:venue :bitflyer :instrument-id "cx-btc-usd" :venue-symbol "BTC_JPY" :currency :jpy}
   {:venue :binance  :instrument-id "cx-eth-usd" :venue-symbol "ETHUSDT" :currency :usdt}
   {:venue :coinbase :instrument-id "cx-eth-usd" :venue-symbol "ETH-USD" :currency :usd}
   {:venue :kraken   :instrument-id "cx-eth-usd" :venue-symbol "ETHUSD"  :currency :usd}
   {:venue :bitflyer :instrument-id "cx-eth-usd" :venue-symbol "ETH_JPY" :currency :jpy}])

(defn listings-for
  "Every venue listing feeding `instrument-id`."
  [instrument-id]
  (filterv #(= instrument-id (:instrument-id %)) listings))

(defn instrument-ids
  "The distinct instruments this actor collects from CEX venues."
  []
  (vec (distinct (map :instrument-id listings))))

;; ───────────────────────── URL builders (pure) ────────────────────────

(defn ticker-url
  "The venue's own public ticker URL for `venue-symbol`. Keyless for all
  four venues — no credential is constructed, read or embedded here (this
  namespace has no access to any secret store, by design)."
  [venue-id venue-symbol]
  (case venue-id
    :binance  (str "https://api.binance.com/api/v3/ticker/24hr?symbol=" venue-symbol)
    :coinbase (str "https://api.exchange.coinbase.com/products/" venue-symbol "/ticker")
    :kraken   (str "https://api.kraken.com/0/public/Ticker?pair=" venue-symbol)
    :bitflyer (str "https://api.bitflyer.com/v1/ticker?product_code=" venue-symbol)
    (throw (ex-info (str "marketdata.venues: unknown venue " venue-id)
                    {:type ::unknown-venue :venue venue-id}))))

;; ───────────────────────── ingest-request shaping (pure) ──────────────

(defn venue-ingest-request
  "An observed venue tick -> a `marketdata.llm/infer`-compatible
  `:quote/ingest` request map.

  `tick` is `{:price :as-of :as-of-source :epoch-seconds :bid :ask :volume}`
  as produced by the `marketdata.feed/parse-*-ticker` fns (or by a
  ClojureScript/nbb caller's own parse of the same public payload). Returns
  nil when the venue reported no last price, or reported one that is not
  positive — a venue that is down, halted or returning a placeholder must
  produce NO quote, never a zero one.

  `:as-of-source` records whether the timestamp is the VENUE's own
  (`:venue`) or this actor's observation time (`:observed`, used for
  Kraken, whose public ticker payload carries no timestamp at all). That
  distinction is preserved into the provenance instead of being flattened
  into a timestamp that looks venue-authoritative but is not.
  `:epoch-seconds` is what `marketdata.aggregate` needs for its freshness
  check; a venue that gives no parseable clock simply gets
  `:freshness :unknown` there rather than a fabricated one.

  The `:source :ref` is `venue:symbol:as-of`, the exact coordinates a
  subscriber needs to re-request the same tick from the same venue."
  [{:keys [venue instrument-id venue-symbol currency]}
   {:keys [price as-of as-of-source bid ask volume epoch-seconds]}]
  (when (and (number? price) (pos? price))
    {:op :quote/ingest
     :subject instrument-id
     :instrument-id instrument-id
     :price price
     :currency currency
     :as-of as-of
     :source {:class :exchange-first-party-public-api
              :ref (str (name venue) ":" venue-symbol ":" as-of)
              :venue venue
              :raw (cond-> {:venue-symbol venue-symbol
                            :as-of-source (or as-of-source :venue)}
                     epoch-seconds (assoc :epoch-seconds epoch-seconds)
                     bid    (assoc :bid bid)
                     ask    (assoc :ask ask)
                     volume (assoc :volume volume))}}))

(defn describe
  "Human-readable coverage line — used by `docs/` and the operator console
  so the published claim about venue coverage is generated from the
  registry, never written by hand and left to drift."
  []
  (str (count venues) " venues ("
       (str/join ", " (map :name venues)) ") × "
       (count (instrument-ids)) " instruments ("
       (str/join ", " (instrument-ids)) "), "
       (count listings) " listings, all keyless first-party public endpoints"))
