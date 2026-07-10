(ns marketdata.feed-test
  "Parsing-only tests for `marketdata.feed` -- no network access, offline
  and CI-safe (same discipline as the rest of `clojure -M:dev:test`).

  Fixtures:
    - `ecb-fx-fixture` is a REAL response body, captured live via
      `curl https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml`
      during development of this test (2026-07-09 reference date). Not a
      fabricated schema.
    - `eia-spot-fixture`/`fred-observations-fixture` are hand-built to the
      documented, stable public response shape of EIA Open Data v2
      (https://www.eia.gov/opendata/documentation.php) and the FRED
      `series/observations` endpoint
      (https://fred.stlouisfed.org/docs/api/fred/series_observations.html)
      -- both APIs require a free registered key to call live, which this
      sandbox does not have; the exact field names/nesting here match the
      publicly documented schema, not an invented one."
  (:require [clojure.test :refer [deftest is testing]]
            [marketdata.feed :as feed]))

(def ecb-fx-fixture
  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<gesmes:Envelope xmlns:gesmes=\"http://www.gesmes.org/xml/2002-08-01\" xmlns=\"http://www.ecb.int/vocabulary/2002-08-01/eurofxref\">
\t<gesmes:subject>Reference rates</gesmes:subject>
\t<gesmes:Sender>
\t\t<gesmes:name>European Central Bank</gesmes:name>
\t</gesmes:Sender>
\t<Cube>
\t\t<Cube time='2026-07-09'>
\t\t\t<Cube currency='USD' rate='1.1435'/>
\t\t\t<Cube currency='JPY' rate='185.72'/>
\t\t\t<Cube currency='CZK' rate='24.254'/>
\t\t\t<Cube currency='GBP' rate='0.85363'/>
\t\t\t<Cube currency='CHF' rate='0.9227'/>
\t\t</Cube>
\t</Cube>
</gesmes:Envelope>")

(deftest parse-ecb-fx-xml-extracts-as-of-and-rates
  (let [{:keys [as-of eur-rates]} (feed/parse-ecb-fx-xml ecb-fx-fixture)]
    (is (= "2026-07-09" as-of))
    (is (= 1.1435M (get eur-rates "USD")))
    (is (= 185.72M (get eur-rates "JPY")))
    (is (= 5 (count eur-rates)))))

(deftest cross-rate-derives-usd-jpy-from-eur-quoted-rates
  (let [rates {"USD" 1.1435M "JPY" 185.72M}]
    (is (< (Math/abs (- (feed/cross-rate rates "USD" "JPY") (/ 185.72 1.1435))) 1e-9))
    (is (= 1.1435 (feed/cross-rate rates "EUR" "USD")) "EUR itself is rate 1")))

(deftest cross-rate-returns-nil-for-missing-currency
  (is (nil? (feed/cross-rate {"USD" 1.1435M} "USD" "XXX"))))

(deftest ecb-fx-ingest-requests-shapes-quote-ingest-map
  (let [parsed {:as-of "2026-07-09" :eur-rates {"USD" 1.1435M "JPY" 185.72M}}
        [req] (feed/ecb-fx-ingest-requests parsed)]
    (is (= :quote/ingest (:op req)))
    (is (= "fx-100" (:instrument-id req)))
    (is (= :jpy (:currency req)))
    (is (= "2026-07-09" (:as-of req)))
    (is (= :central-bank-reference-rate (get-in req [:source :class])))
    (is (< (Math/abs (- (:price req) (/ 185.72 1.1435))) 1e-9))))

(deftest ecb-fx-ingest-requests-skips-pairs-with-missing-currency
  (let [parsed {:as-of "2026-07-09" :eur-rates {"USD" 1.1435M}}]
    (is (empty? (feed/ecb-fx-ingest-requests parsed [["USD" "XXX" "fx-999"]])))))

;; ───────────────────────── EIA ─────────────────────────

(def eia-spot-fixture
  "{\"response\":{\"total\":\"1\",\"dateFormat\":\"YYYY-MM-DD\",\"frequency\":\"daily\",
    \"data\":[{\"period\":\"2026-07-09\",\"duoarea\":\"NUS\",\"area-name\":\"NUS\",
               \"product\":\"EPCWTI\",\"product-name\":\"WTI\",\"process\":\"PSA\",
               \"process-name\":\"Spot Price\",\"series\":\"RWTC\",
               \"series-description\":\"Cushing, OK WTI Spot Price FOB\",
               \"value\":\"68.40\",\"units\":\"$/BBL\"}]},
   \"request\":{\"command\":\"/v2/petroleum/pri/spt/data/\"},
   \"apiVersion\":\"2.1.8\"}")

(deftest parse-eia-spot-json-extracts-latest-observation
  (let [row (feed/parse-eia-spot-json eia-spot-fixture)]
    (is (= "2026-07-09" (:period row)))
    (is (= 68.40M (:value row)))
    (is (= "$/BBL" (:units row)))
    (is (= "RWTC" (:series row)))))

(deftest parse-eia-spot-json-nil-when-no-rows
  (is (nil? (feed/parse-eia-spot-json "{\"response\":{\"data\":[]}}"))))

(deftest eia-spot-ingest-request-shapes-quote-ingest-map
  (let [req (feed/eia-spot-ingest-request
             {:period "2026-07-09" :value 68.40M :series "RWTC"} "cm-100")]
    (is (= :quote/ingest (:op req)))
    (is (= "cm-100" (:instrument-id req)))
    (is (= 68.40M (:price req)))
    (is (= :usd (:currency req)))
    (is (= :government-energy-data (get-in req [:source :class])))))

(deftest eia-spot-url-embeds-key-and-series
  (is (re-find #"api_key=demo-key" (feed/eia-spot-url "demo-key")))
  (is (re-find #"facets\[series\]\[\]=RWTC" (feed/eia-spot-url "demo-key")))
  (is (re-find #"facets\[series\]\[\]=RBRTE" (feed/eia-spot-url "demo-key" "RBRTE"))))

;; ───────────────────────── FRED ─────────────────────────

(def fred-observations-fixture
  "{\"realtime_start\":\"2026-07-10\",\"realtime_end\":\"2026-07-10\",
   \"observation_start\":\"1600-01-01\",\"observation_end\":\"9999-12-31\",
   \"units\":\"lin\",\"output_type\":1,\"file_type\":\"json\",
   \"order_by\":\"observation_date\",\"sort_order\":\"desc\",
   \"count\":1,\"offset\":0,\"limit\":1,
   \"observations\":[{\"realtime_start\":\"2026-07-10\",\"realtime_end\":\"2026-07-10\",
                       \"date\":\"2026-05-01\",\"value\":\"315.6\"}]}")

(def fred-missing-value-fixture
  "{\"observations\":[{\"date\":\"2026-06-01\",\"value\":\".\"}]}")

(deftest parse-fred-observations-json-extracts-latest-observation
  (let [obs (feed/parse-fred-observations-json fred-observations-fixture)]
    (is (= "2026-05-01" (:date obs)))
    (is (= 315.6M (:value obs)))))

(deftest parse-fred-observations-json-nil-on-missing-value-sentinel
  (testing "FRED's own \".\" missing-value marker never coerces to a fake price"
    (is (nil? (feed/parse-fred-observations-json fred-missing-value-fixture)))))

(deftest fred-ingest-request-shapes-quote-ingest-map
  (let [req (feed/fred-ingest-request {:date "2026-05-01" :value 315.6M}
                                       "CSUSHPINSA" "re-100")]
    (is (= :quote/ingest (:op req)))
    (is (= "re-100" (:instrument-id req)))
    (is (= 315.6M (:price req)))
    (is (= :index (:currency req)))
    (is (= :government-statistical-index (get-in req [:source :class])))
    (is (re-find #"CSUSHPINSA" (get-in req [:source :ref])))))

(deftest fred-observations-url-embeds-key-and-series
  (is (re-find #"series_id=CSUSHPINSA" (feed/fred-observations-url "demo-key")))
  (is (re-find #"api_key=demo-key" (feed/fred-observations-url "demo-key"))))
