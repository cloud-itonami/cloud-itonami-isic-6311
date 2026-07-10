(ns marketdata.feed
  "Real HTTP feed connectors for the 3 free/official R0 catalog sources
  (`marketdata.facts/catalog`): ECB euro FX reference rates (no key), US
  EIA Open Data (needs an API key), FRED (needs an API key). This is the
  'operator wires a real feed' seam the actor's docs gesture at — before
  this namespace existed, `:quote/ingest` only ever ran against whatever
  price/source a test or the demo (`marketdata.sim`) handed it directly.

  JVM-only (`#?(:clj ...)`) host-fn layer, same zero-dep-core discipline as
  `langchain.jvm`'s `org.httpkit.client`/`jsonista.core` reference impl:
  this repo's `deps.edn` pulls http-kit/jsonista in only via the `:test`
  and `:feed` aliases, never `:deps` -- kotoba wasm/clojurewasm/ClojureScript/
  nbb consumers of `marketdata.*` are never forced to carry a JVM HTTP
  client. A ClojureScript/nbb/kotoba-wasm operator brings their own fetch
  mechanism and only needs the pure `parse-*`/`*-ingest-request` fns below,
  which are plain `.cljc` with no host dependency.

  Every `fetch-*` fn does I/O + parse and returns data ALREADY SHAPED as a
  `marketdata.llm/infer`-compatible `:quote/ingest` request map (or a
  vector of them) -- never a raw HTTP response -- so it plugs straight into
  `marketdata.operation/build` with no glue code. I/O + parsing (`get!`,
  `parse-*`) stay JVM-only (`clojure.xml`/jsonista); the *-ingest-request
  shaping fns and `cross-rate` are genuinely portable `.cljc` with no host
  dependency, so a ClojureScript/nbb/kotoba-wasm caller that parses the raw
  feed with its own host's XML/JSON tools can still call straight into
  these to get a `:quote/ingest`-shaped map. The JVM parse-* fns are
  unit-testable without network access
  (test/marketdata/feed_test.clj uses real fixture payloads captured from
  the live APIs, never a fabricated schema).

  API keys are NEVER read from env (or any secret store) inside this
  namespace -- the CALLER reads `EIA_API_KEY`/`FRED_API_KEY` (or a secret
  manager, `scripts/b2-creds.bb`-style) and passes the key in explicitly as
  a plain string argument. `marketdata.feed` is a pure injected-credential
  client, same discipline as this workspace's B2 credential resolution."
  (:require [clojure.string :as str]
            #?(:clj [clojure.xml :as xml])
            #?(:clj [org.httpkit.client :as http])
            #?(:clj [jsonista.core :as j])))

#?(:clj
   (defn- get!
     "GET `url`, return the response body string. Throws on transport error
     or a non-2xx status (never returns a partial/garbage body silently)."
     [url]
     (let [{:keys [status body error]} @(http/get url)]
       (when error (throw (ex-info "marketdata.feed: HTTP transport error" {:url url :error error})))
       (when-not (<= 200 status 299)
         (throw (ex-info "marketdata.feed: HTTP error status" {:url url :status status :body body})))
       body)))

#?(:clj (def ^:private json-read #(j/read-value % j/keyword-keys-object-mapper)))

;; ───────────────────────── ECB euro FX reference rates ─────────────────

(def ecb-fx-url "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml")

#?(:clj
   (defn parse-ecb-fx-xml
     "ECB daily reference-rate XML -> {:as-of \"YYYY-MM-DD\" :eur-rates {\"USD\"
     1.1435M \"JPY\" 185.72M ...}}. Every rate is EUR-quoted (1 EUR = rate
     CCY) -- the ECB feed is EUR-based, it does not publish e.g. USD/JPY
     directly; see `cross-rate`. JVM-only (`clojure.xml`/SAX); a
     ClojureScript/nbb caller parses the same XML with its own host's
     parser and calls `ecb-fx-ingest-requests` directly with the result."
     [xml-str]
     (let [root (xml/parse (java.io.ByteArrayInputStream. (.getBytes ^String xml-str "UTF-8")))
           outer-cube (first (filter #(= :Cube (:tag %)) (:content root)))
           day-cube   (first (filter #(= :Cube (:tag %)) (:content outer-cube)))
           as-of      (get-in day-cube [:attrs :time])
           rates      (into {}
                            (map (fn [c] [(get-in c [:attrs :currency])
                                          (bigdec (get-in c [:attrs :rate]))]))
                            (:content day-cube))]
       {:as-of as-of :eur-rates rates})))

(defn cross-rate
  "A BASE/QUOTE cross rate derived from two EUR-quoted rates (e.g.
  base=\"USD\" quote=\"JPY\" -> USD/JPY). \"EUR\" itself is rate 1. Returns
  nil (not an exception) when either currency is absent from `eur-rates` --
  callers (`ecb-fx-ingest-requests`) skip missing pairs rather than fail
  the whole batch. Plain double division (not BigDecimal/`with-precision`,
  which ClojureScript lacks) -- same portable-arithmetic choice
  `marketdata.policy`'s tolerance-gate already makes, so this fn (and
  `ecb-fx-ingest-requests` below) stays genuinely portable `.cljc`, not
  JVM-only like the XML/JSON parsing."
  [eur-rates base quote]
  (let [r (fn [ccy] (if (= ccy "EUR") 1.0 (some-> (get eur-rates ccy) double)))
        b (r base) q (r quote)]
    (when (and b q) (/ q b))))

(defn ecb-fx-ingest-requests
  "{:as-of :eur-rates} (from `parse-ecb-fx-xml`) -> vector of `:quote/ingest`
  request maps, one per `[base quote instrument-id]` triple in `pairs`
  (default: USD/JPY -> \"fx-100\", this actor's seeded demo instrument).
  Pairs with no rate available (either currency absent from the feed) are
  silently skipped, never fabricated."
  [{:keys [as-of eur-rates]} & [pairs]]
  (vec
   (for [[base quote instrument-id] (or pairs [["USD" "JPY" "fx-100"]])
         :let [rate (cross-rate eur-rates base quote)]
         :when rate]
     {:op :quote/ingest :subject instrument-id :instrument-id instrument-id
      :price rate :currency (keyword (str/lower-case quote)) :as-of as-of
      :source {:class :central-bank-reference-rate
               :ref (str "ecb-fx-reference-rates:" base "/" quote ":" as-of)}})))

#?(:clj
   (defn fetch-ecb-fx-rates
     "Live fetch + parse. Returns a vector of `:quote/ingest` request maps
     (see `ecb-fx-ingest-requests`). No API key required."
     [& [pairs]]
     (-> (get! ecb-fx-url) parse-ecb-fx-xml (ecb-fx-ingest-requests pairs))))

;; ───────────────────────── US EIA Open Data (commodity spot) ───────────

(defn eia-spot-url
  "EIA v2 Open Data URL for the most recent spot-price observation of
  `series` (default \"RWTC\" = Cushing OK WTI Crude Oil spot price FOB,
  the series `marketdata.store`'s demo \"cm-100\" instrument represents).
  `api-key` is a plain string the caller supplies (never read from env
  here)."
  [api-key & [series]]
  (str "https://api.eia.gov/v2/petroleum/pri/spt/data/?api_key=" api-key
       "&frequency=daily&data[0]=value&facets[series][]=" (or series "RWTC")
       "&sort[0][column]=period&sort[0][direction]=desc&length=1"))

#?(:clj
   (defn parse-eia-spot-json
     "EIA v2 Open Data JSON response body -> {:period :value :units :series}
     for the most recent observation (first row of `response.data`), or nil
     when the response has no data rows. JVM-only (jsonista)."
     [json-str]
     (let [row (first (get-in (json-read json-str) [:response :data]))]
       (when row
         {:period (:period row) :value (bigdec (:value row))
          :units (:units row) :series (:series row)}))))

(defn eia-spot-ingest-request
  "A parsed EIA observation -> a `:quote/ingest` request map for
  `instrument-id`."
  [{:keys [period value series]} instrument-id]
  {:op :quote/ingest :subject instrument-id :instrument-id instrument-id
   :price value :currency :usd :as-of period
   :source {:class :government-energy-data
            :ref (str "us-eia-energy-open-data:" series ":" period)}})

#?(:clj
   (defn fetch-eia-spot
     "Live fetch + parse for one EIA series. `api-key` supplied by the
     caller. Returns a `:quote/ingest` request map, or nil if the API
     returned no observation for the series."
     [api-key & [{:keys [series instrument-id] :or {series "RWTC" instrument-id "cm-100"}}]]
     (some-> (get! (eia-spot-url api-key series)) parse-eia-spot-json
             (eia-spot-ingest-request instrument-id))))

;; ───────────────────────── FRED (real-estate index) ─────────────────────

(defn fred-observations-url
  "FRED `series/observations` URL for the single most recent observation of
  `series-id` (default \"CSUSHPINSA\" = S&P/Case-Shiller U.S. National Home
  Price Index, the series `marketdata.store`'s demo \"re-100\" instrument
  represents). `api-key` is a plain string the caller supplies."
  [api-key & [series-id]]
  (str "https://api.stlouisfed.org/fred/series/observations?series_id=" (or series-id "CSUSHPINSA")
       "&api_key=" api-key "&file_type=json&sort_order=desc&limit=1"))

#?(:clj
   (defn parse-fred-observations-json
     "FRED `series/observations` JSON response body -> {:date :value} for the
     most recent observation, or nil when there is none or FRED's own
     missing-value sentinel `\".\"` is present (never coerced to a fake
     price). JVM-only (jsonista)."
     [json-str]
     (let [obs (first (:observations (json-read json-str)))]
       (when (and obs (not= "." (:value obs)))
         {:date (:date obs) :value (bigdec (:value obs))}))))

(defn fred-ingest-request
  "A parsed FRED observation -> a `:quote/ingest` request map for
  `instrument-id`."
  [{:keys [date value]} series-id instrument-id]
  {:op :quote/ingest :subject instrument-id :instrument-id instrument-id
   :price value :currency :index :as-of date
   :source {:class :government-statistical-index
            :ref (str "fred-case-shiller-hpi:" series-id ":" date)}})

#?(:clj
   (defn fetch-fred-series
     "Live fetch + parse for one FRED series. `api-key` supplied by the
     caller. Returns a `:quote/ingest` request map, or nil if FRED returned
     no usable observation."
     [api-key & [{:keys [series-id instrument-id] :or {series-id "CSUSHPINSA" instrument-id "re-100"}}]]
     (some-> (get! (fred-observations-url api-key series-id)) parse-fred-observations-json
             (fred-ingest-request series-id instrument-id))))
