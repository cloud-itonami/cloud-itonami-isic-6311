(ns marketdata.facts
  "R0 source-basis catalog — the ONLY provenance classes/references the
  MarketDataGovernor will accept as a citation for an ingested quote or
  derived series (mirrors `cloud-itonami-isic-8291`'s `dossier.facts`
  discipline: honesty over coverage). Four kinds of entry:

    1. Real, public, free, official reference sources — genuinely citable
       today, no licensing needed (central-bank FX reference rates,
       government commodity/energy data, government housing-price indices).
    2. `:licensed-operator-feed` — the *structural* class for exchange/
       broker/vendor live ticks (equities, and any crypto venue outside the
       direct-venue set below). This actor does not (and cannot) hold a
       public, free, real-time feed for these asset classes — same boundary
       as `kotoba-lang/securities` ('operator supplies their own licensed
       price feed'). A quote citing this class is only accepted when it
       also carries a `:license-id` that resolves to an ACTIVE
       `feed-license` record in the store (`marketdata.policy`'s
       source-provenance-gate checks both).
    3. Direct-venue crypto observation (ADR-2607262100) — the venue's own
       first-party public market-data endpoint
       (`:exchange-first-party-public-api`) or the DEX pool contract's own
       chain state (`:dex-onchain-observation`). These need no feed license
       because they are the venue publishing its own book / the public
       chain publishing its own state; what they DO need is a
       re-derivable `:ref` (venue+symbol+timestamp, or chain-id+pool+block)
       so a subscriber can check the print against the same public source.
    4. `:cross-venue-composite` — the median this actor publishes over
       kind-3 observations. A DERIVED class, not a source: it is only
       accepted when it enumerates its constituents and those constituents
       independently satisfy this catalog (see
       `composite-constituents-ok?`, enforced hard by the governor).

  **No aggregator class exists, deliberately.** There is no catalog entry
  under which a CoinMarketCap/CoinGecko/CryptoCompare-derived price could
  be cited, so the closed-set check in `class-allowed?` structurally
  prevents this actor from ever republishing an aggregator's number as its
  own. Crypto coverage comes from reading the venues directly instead.

  Adding coverage means adding a real, citable catalog entry or a real
  registered feed-license — never fabricating either."
  (:require [clojure.string :as str]))

(def catalog
  "Each entry: {:id :name :asset-classes :class :access :url}. `:class` is
  the value that must appear in a quote's `:source :class` for the
  source-provenance-gate to accept it as grounded (for `:licensed-operator-
  feed`, grounding also requires an active `feed-license`; for
  `:cross-venue-composite`, a valid constituent enumeration — both checked
  separately, this catalog only proves the CLASS itself is real)."
  [{:id :ecb-fx-reference-rates
    :name "European Central Bank euro foreign exchange reference rates"
    :asset-classes #{:fx} :class :central-bank-reference-rate
    :access :public-api
    :url "https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html"}
   {:id :us-eia-energy-open-data
    :name "U.S. Energy Information Administration Open Data (spot prices)"
    :asset-classes #{:commodity} :class :government-energy-data
    :access :public-api
    :url "https://www.eia.gov/opendata/"}
   {:id :fred-case-shiller-hpi
    :name "FRED — S&P/Case-Shiller U.S. National Home Price Index"
    :asset-classes #{:real-estate-index} :class :government-statistical-index
    :access :public-api
    :url "https://fred.stlouisfed.org/series/CSUSHPINSA"}
   ;; ── kind 3: direct-venue crypto (ADR-2607262100) ───────────────────
   {:id :binance-public-market-data
    :name "Binance own public market-data REST API (24hr ticker)"
    :asset-classes #{:crypto} :class :exchange-first-party-public-api
    :access :public-api
    :url "https://developers.binance.com/docs/binance-spot-api-docs/rest-api/market-data-endpoints"}
   {:id :coinbase-exchange-public-market-data
    :name "Coinbase Exchange own public market-data REST API (product ticker)"
    :asset-classes #{:crypto} :class :exchange-first-party-public-api
    :access :public-api
    :url "https://docs.cdp.coinbase.com/exchange/reference/exchangerestapi_getproductticker"}
   {:id :kraken-public-market-data
    :name "Kraken own public market-data REST API (Ticker)"
    :asset-classes #{:crypto} :class :exchange-first-party-public-api
    :access :public-api
    :url "https://docs.kraken.com/api/docs/rest-api/get-ticker-information"}
   {:id :bitflyer-public-market-data
    :name "bitFlyer own public market-data REST API (ticker)"
    :asset-classes #{:crypto} :class :exchange-first-party-public-api
    :access :public-api
    :url "https://lightning.bitflyer.com/docs?lang=en#ticker"}
   {:id :uniswap-v3-onchain-pool
    :name "Uniswap v3 pool contract state read on-chain (slot0, eth_call)"
    :asset-classes #{:crypto} :class :dex-onchain-observation
    :access :public-rpc
    :url "https://docs.uniswap.org/contracts/v3/reference/core/UniswapV3Pool"}
   ;; ── kind 4: derived ────────────────────────────────────────────────
   {:id :cross-venue-composite
    :name "Cross-venue median over direct-venue observations (derived, this actor)"
    :asset-classes #{:crypto} :class :cross-venue-composite
    :access :derived
    :url nil}
   ;; ── kind 2: structural licensed feed ───────────────────────────────
   {:id :licensed-operator-feed
    :name "Operator-registered licensed exchange/broker/vendor feed (equities, most commodities, any venue outside the direct-venue set)"
    :asset-classes #{:equity :crypto :commodity} :class :licensed-operator-feed
    :access :operator-licensed
    :url nil}])

(def allowed-source-classes
  "The set of `:source :class` values the source-provenance-gate will accept
  anywhere. A closed set — a class not in `catalog` (e.g. :inference,
  :scraped, :social-media, :price-aggregator) must be rejected, not
  silently accepted because it looks like a keyword."
  (into #{} (map :class catalog)))

(def direct-venue-classes
  "Kind-3 classes: an observation read straight from the venue or the
  chain, needing no feed license. These are also the ONLY classes a
  `:cross-venue-composite` may be built from — a composite of licensed
  vendor ticks would be redistributing licensed data under a derived
  label, and a composite of composites would launder provenance."
  #{:exchange-first-party-public-api :dex-onchain-observation})

(def min-composite-constituents
  "Quorum for a `:cross-venue-composite` citation. Two venues cannot
  outvote each other, so a median over fewer than three constituents is
  just one venue's price with extra steps."
  3)

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn licensed-feed-class? [source-class]
  (= :licensed-operator-feed source-class))

(defn composite-class? [source-class]
  (= :cross-venue-composite source-class))

(defn direct-venue-class? [source-class]
  (contains? direct-venue-classes source-class))

(defn composite-constituents-ok?
  "Is a `:cross-venue-composite` source's constituent enumeration valid?
  Returns true only when ALL of:

    - at least `min-composite-constituents` constituents are enumerated;
    - every constituent cites a kind-3 direct-venue class (so no licensed
      vendor tick and no nested composite can hide inside one);
    - every constituent carries a non-blank `:ref` (the re-derivation
      coordinates), and those refs are DISTINCT — otherwise one venue
      counted three times would satisfy the quorum.

  `marketdata.policy` calls this as a HARD gate. It is a pure predicate on
  the citation itself: it proves the composite is well-formed and
  self-consistent, not that the underlying venues were honest — that is
  what the tolerance-gate, the dispersion refusal in
  `marketdata.aggregate/reference-price`, and the subscriber's own ability
  to re-check each `:ref` are for."
  [source]
  (let [cs (:constituents source)
        refs (map :ref cs)]
    (boolean
     (and (sequential? cs)
          (>= (count cs) min-composite-constituents)
          (every? #(direct-venue-class? (:class %)) cs)
          (every? #(and (string? %) (not (str/blank? %))) refs)
          (= (count refs) (count (set refs)))))))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全アセットクラスの生きたレート' in prose; in fact 3 free
  official reference sources + 5 direct crypto venues + 1 derived
  composite + 1 structural licensed-feed class)."
  []
  {:source-count (count catalog)
   :asset-classes (into (sorted-set) (mapcat :asset-classes catalog))
   :free-public-sources (into #{} (map :id (filter #(#{:public-api :public-rpc} (:access %)) catalog)))
   :direct-venue-classes direct-venue-classes
   :note (str "R0 scope: 3 free official reference sources (ECB FX, US EIA "
              "commodity, FRED real-estate index); 5 direct crypto venues "
              "(Binance, Coinbase, Kraken, bitFlyer first-party public APIs "
              "+ Uniswap v3 on-chain) published as a fail-closed cross-venue "
              "median; 1 structural licensed-operator-feed class for "
              "equities and most commodities. NO price-aggregator class "
              "exists — a CoinMarketCap/CoinGecko-derived number has no "
              "catalog class to cite and is structurally unpublishable here. "
              "Extend only by appending a real, citable catalog entry or a "
              "real registered feed-license — never fabricate either.")})
