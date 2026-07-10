(ns marketdata.facts
  "R0 source-basis catalog — the ONLY provenance classes/references the
  MarketDataGovernor will accept as a citation for an ingested quote or
  derived series (mirrors `cloud-itonami-isic-8291`'s `dossier.facts`
  discipline: honesty over coverage). Two kinds of entry:

    1. Real, public, free, official reference sources — genuinely citable
       today, no licensing needed (central-bank FX reference rates,
       government commodity/energy data, government housing-price indices).
    2. `:licensed-operator-feed` — the *structural* class for exchange/
       broker/vendor live ticks (equities, crypto, most commodities). This
       actor does not (and cannot) hold a public, free, real-time feed for
       these asset classes — same boundary as `kotoba-lang/securities`
       ('operator supplies their own licensed price feed'). A quote citing
       this class is only accepted when it also carries a `:license-id`
       that resolves to an ACTIVE `feed-license` record in the store
       (`marketdata.policy`'s source-provenance-gate checks both).

  Adding coverage means adding a real, citable catalog entry (kind 1) or a
  real registered feed-license (kind 2) — never fabricating either.")

(def catalog
  "Each entry: {:id :name :asset-classes :class :access :url}. `:class` is
  the value that must appear in a quote's `:source :class` for the
  source-provenance-gate to accept it as grounded (for `:licensed-operator-
  feed`, grounding also requires an active `feed-license`, checked
  separately — this catalog only proves the CLASS itself is real)."
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
   {:id :licensed-operator-feed
    :name "Operator-registered licensed exchange/broker/vendor feed (equities, crypto, most commodities)"
    :asset-classes #{:equity :crypto :commodity} :class :licensed-operator-feed
    :access :operator-licensed
    :url nil}])

(def allowed-source-classes
  "The set of `:source :class` values the source-provenance-gate will accept
  anywhere. A closed set — a class not in `catalog` (e.g. :inference,
  :scraped, :social-media) must be rejected, not silently accepted because
  it looks like a keyword."
  (into #{} (map :class catalog)))

(defn coverage
  "Honest, machine-checkable report of what R0 actually covers — never
  overstate ('全アセットクラスの生きたレート' in prose, 3 free official
  sources + 1 structural licensed-feed class in fact)."
  []
  {:source-count (count catalog)
   :asset-classes (into (sorted-set) (mapcat :asset-classes catalog))
   :free-public-sources (into #{} (map :id (filter #(= :public-api (:access %)) catalog)))
   :note (str "R0 scope: 3 free official reference sources (ECB FX, US EIA "
              "commodity, FRED real-estate index) + 1 structural licensed-"
              "operator-feed class for equities/crypto/most commodities. "
              "Extend only by appending a real, citable catalog entry or a "
              "real registered feed-license — never fabricate either.")})

(defn class-allowed? [source-class]
  (contains? allowed-source-classes source-class))

(defn licensed-feed-class? [source-class]
  (= :licensed-operator-feed source-class))
