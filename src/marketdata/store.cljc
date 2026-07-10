(ns marketdata.store
  "SSoT for the market-data actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite:

    - `MemStore`     — atom of Datomic-shaped EDN. The deterministic default
                       for dev/tests/demo (no deps).
    - `DatomicStore` — backed by `langchain.db`, a Datomic-API-compatible EAV
                       store. Pure `.cljc`, so it runs offline AND can be
                       pointed at a real Datomic Local or a kotoba-server pod
                       by swapping `langchain.db`'s `:db-api`.

  Both implement the same protocol and pass the same contract
  (test/marketdata/store_contract_test.clj) — the actor, the
  MarketDataGovernor and the audit ledger never know which SSoT they run on.

  Entity shapes (ADR-2607111500): an instrument (equity/fx/commodity/crypto/
  real-estate-index — NEVER a raw brokerage position, only a quoted price
  reference), its latest quote, a derived series (e.g. daily OHLC bars), a
  feed-license (provenance for `:licensed-operator-feed` ingestion), and a
  subscriber contract (tenant × tier, licensed disclosure). There is NO field
  anywhere in this schema for order-routing, custody, or trade execution —
  this actor only collects, holds and serves prices, it never trades
  (ADR-2607111500 §1, the same class of structural exclusion as
  `cloud-itonami-isic-8291`'s private-life-field exclusion).

  The ledger stays append-only on every backend — 'who ingested/disclosed
  what, on what license/contract, on what source basis' is always a query
  over an immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [langchain.db :as d]))

(defprotocol Store
  (instrument [s id])
  (all-instruments [s])
  (quote* [s instrument-id] "latest known-good quote for this instrument")
  (series [s series-id])
  (feed-license [s license-id])
  (contract [s tenant])
  (ledger [s])
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision/disclosure fact")
  (with-instruments [s instruments] "replace/seed instruments (map id→instrument)")
  (with-quotes [s quotes]           "replace/seed latest quotes (map instrument-id→quote)")
  (with-series [s series-map]       "replace/seed derived series (map series-id→bars)")
  (with-feed-licenses [s licenses]  "replace/seed feed licenses (map license-id→license)")
  (with-contracts [s contracts]     "replace/seed subscriber contracts (map tenant→contract)"))

;; ───────────────────────── demo data (fictitious, non-real instruments) ──

(defn demo-data
  "A small, entirely fictitious dataset so the actor + tests run offline and
  no real instrument print is ever asserted by this repository. `eq-200`
  carries a demo `:status :halted`/`:circuit-breaker?` flag purely to
  exercise the halted-instrument governor gate — it is not a claim about any
  real security."
  []
  {:instruments
   {"eq-100" {:id "eq-100" :symbol "DEMOA" :asset-class :equity :venue "DEMO-NASDAQ"
              :status :trading :circuit-breaker? false}
    "eq-200" {:id "eq-200" :symbol "DEMOB" :asset-class :equity :venue "DEMO-NYSE"
              :status :halted :circuit-breaker? true}
    "fx-100" {:id "fx-100" :symbol "USD/JPY" :asset-class :fx :venue "ECB-REF"
              :status :trading :circuit-breaker? false}
    "cm-100" {:id "cm-100" :symbol "WTI" :asset-class :commodity :venue "DEMO-NYMEX"
              :status :trading :circuit-breaker? false}
    "cr-100" {:id "cr-100" :symbol "BTC/USD" :asset-class :crypto :venue "DEMO-VENDOR"
              :status :trading :circuit-breaker? false}
    "re-100" {:id "re-100" :symbol "CSUSHPINSA" :asset-class :real-estate-index :venue "FRED"
              :status :trading :circuit-breaker? false}}
   :quotes
   {"eq-100" {:instrument-id "eq-100" :price 142.50M :currency :usd :as-of "2026-07-10T00:00:00Z"
              :source {:class :licensed-operator-feed :ref "lic-demo:eq-100" :license-id "lic-demo"}}
    "eq-200" {:instrument-id "eq-200" :price 88.10M :currency :usd :as-of "2026-07-09T20:00:00Z"
              :source {:class :licensed-operator-feed :ref "lic-demo:eq-200" :license-id "lic-demo"}}
    "fx-100" {:instrument-id "fx-100" :price 157.32M :currency :jpy :as-of "2026-07-10T00:00:00Z"
              :source {:class :central-bank-reference-rate :ref "ecb-fx-reference-rates:usd-jpy"}}
    "cm-100" {:instrument-id "cm-100" :price 68.40M :currency :usd :as-of "2026-07-10T00:00:00Z"
              :source {:class :government-energy-data :ref "us-eia-energy-open-data:wti"}}
    "cr-100" {:instrument-id "cr-100" :price 61250.00M :currency :usd :as-of "2026-07-10T00:00:00Z"
              :source {:class :licensed-operator-feed :ref "lic-demo:cr-100" :license-id "lic-demo"}}
    "re-100" {:instrument-id "re-100" :price 315.6M :currency :index :as-of "2026-05-01"
              :source {:class :government-statistical-index :ref "fred-case-shiller-hpi:2026-05"}}}
   :feed-licenses
   {"lic-demo" {:license-id "lic-demo" :provider "Demo Exchange Data Vendor (fictitious)"
                :asset-classes #{:equity :crypto} :active? true}
    "lic-expired" {:license-id "lic-expired" :provider "Lapsed Demo Vendor (fictitious)"
                   :asset-classes #{:commodity} :active? false}}
   :contracts
   {"tenant-acme"  {:tenant "tenant-acme" :tier :tier/pro :active? true :purpose :trading-desk}
    "tenant-basic" {:tenant "tenant-basic" :tier :tier/basic :active? true :purpose :retail-app}}})

;; ───────────────────────── MemStore (default) ─────────────────────────

(defrecord MemStore [a]
  Store
  (instrument [_ id] (get-in @a [:instruments id]))
  (all-instruments [_] (sort-by :id (vals (:instruments @a))))
  (quote* [_ instrument-id] (get-in @a [:quotes instrument-id]))
  (series [_ series-id] (get-in @a [:series series-id]))
  (feed-license [_ license-id] (get-in @a [:feed-licenses license-id]))
  (contract [_ tenant] (get-in @a [:contracts tenant]))
  (ledger [_] (:ledger @a))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :quote-upsert  (swap! a assoc-in [:quotes (:instrument-id value)] value)
      :series-upsert (swap! a assoc-in [:series (:series-id value)] value)
      :correction-apply (swap! a update-in [:quotes (first path)] merge (:patch value))
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-instruments [s is]    (when (seq is) (swap! a assoc :instruments is)) s)
  (with-quotes [s qs]         (when (seq qs) (swap! a assoc :quotes qs)) s)
  (with-series [s sm]         (when (seq sm) (swap! a assoc :series sm)) s)
  (with-feed-licenses [s fls] (when (seq fls) (swap! a assoc :feed-licenses fls)) s)
  (with-contracts [s cts]     (when (seq cts) (swap! a assoc :contracts cts)) s))

(defn seed-db
  "A MemStore seeded with the demo data. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :series {} :ledger []))))

;; ───────────────────────── DatomicStore (langchain.db) ─────────────────

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (source citations, series bars) are stored as EDN
  strings so `langchain.db` doesn't expand them into sub-entities."
  {:instrument/id      {:db/unique :db.unique/identity}
   :quote/instrument-id {:db/unique :db.unique/identity}
   :series/id          {:db/unique :db.unique/identity}
   :feed-license/id    {:db/unique :db.unique/identity}
   :contract/tenant    {:db/unique :db.unique/identity}
   :ledger/seq         {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- instrument->tx [{:keys [id symbol asset-class venue status circuit-breaker?]}]
  (cond-> {:instrument/id id}
    symbol           (assoc :instrument/symbol symbol)
    asset-class      (assoc :instrument/asset-class asset-class)
    venue            (assoc :instrument/venue venue)
    status           (assoc :instrument/status status)
    true             (assoc :instrument/circuit-breaker (boolean circuit-breaker?))))

(defn- pull->instrument [m]
  (when (:instrument/id m)
    {:id (:instrument/id m) :symbol (:instrument/symbol m)
     :asset-class (:instrument/asset-class m) :venue (:instrument/venue m)
     :status (:instrument/status m) :circuit-breaker? (:instrument/circuit-breaker m)}))

(def ^:private instrument-pull
  [:instrument/id :instrument/symbol :instrument/asset-class :instrument/venue
   :instrument/status :instrument/circuit-breaker])

(defn- quote->tx [{:keys [instrument-id price currency as-of source]}]
  {:quote/instrument-id instrument-id
   :quote/price (enc price) :quote/currency currency :quote/as-of as-of
   :quote/source (enc source)})

(defn- pull->quote [m]
  (when (:quote/instrument-id m)
    {:instrument-id (:quote/instrument-id m) :price (dec* (:quote/price m))
     :currency (:quote/currency m) :as-of (:quote/as-of m) :source (dec* (:quote/source m))}))

(def ^:private quote-pull
  [:quote/instrument-id :quote/price :quote/currency :quote/as-of :quote/source])

(defn- series->tx [{:keys [series-id instrument-id bars source]}]
  {:series/id series-id :series/instrument-id instrument-id
   :series/bars (enc bars) :series/source (enc source)})

(defn- pull->series [m]
  (when (:series/id m)
    {:series-id (:series/id m) :instrument-id (:series/instrument-id m)
     :bars (dec* (:series/bars m)) :source (dec* (:series/source m))}))

(def ^:private series-pull
  [:series/id :series/instrument-id :series/bars :series/source])

(defn- feed-license->tx [{:keys [license-id provider asset-classes active?]}]
  {:feed-license/id license-id :feed-license/provider provider
   :feed-license/asset-classes (enc asset-classes) :feed-license/active active?})

(defn- pull->feed-license [m]
  (when (:feed-license/id m)
    {:license-id (:feed-license/id m) :provider (:feed-license/provider m)
     :asset-classes (dec* (:feed-license/asset-classes m)) :active? (:feed-license/active m)}))

(def ^:private feed-license-pull
  [:feed-license/id :feed-license/provider :feed-license/asset-classes :feed-license/active])

(defn- contract->tx [{:keys [tenant tier active? purpose]}]
  {:contract/tenant tenant :contract/tier tier :contract/active active? :contract/purpose purpose})

(defn- pull->contract [m]
  (when (:contract/tenant m)
    {:tenant (:contract/tenant m) :tier (:contract/tier m)
     :active? (:contract/active m) :purpose (:contract/purpose m)}))

(def ^:private contract-pull
  [:contract/tenant :contract/tier :contract/active :contract/purpose])

(defrecord DatomicStore [conn]
  Store
  (instrument [_ id] (pull->instrument (d/pull (d/db conn) instrument-pull [:instrument/id id])))
  (all-instruments [_]
    (->> (d/q '[:find [?id ...] :where [?e :instrument/id ?id]] (d/db conn))
         (map #(pull->instrument (d/pull (d/db conn) instrument-pull [:instrument/id %])))
         (sort-by :id)))
  (quote* [_ instrument-id]
    (pull->quote (d/pull (d/db conn) quote-pull [:quote/instrument-id instrument-id])))
  (series [_ series-id] (pull->series (d/pull (d/db conn) series-pull [:series/id series-id])))
  (feed-license [_ license-id]
    (pull->feed-license (d/pull (d/db conn) feed-license-pull [:feed-license/id license-id])))
  (contract [_ tenant] (pull->contract (d/pull (d/db conn) contract-pull [:contract/tenant tenant])))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (commit-record! [s {:keys [effect path value]}]
    (case effect
      :quote-upsert  (d/transact! conn [(quote->tx value)])
      :series-upsert (d/transact! conn [(series->tx value)])
      :correction-apply
      (d/transact! conn [(quote->tx (merge (quote* s (first path)) (:patch value)))])
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-instruments [s is]
    (when (seq is) (d/transact! conn (mapv instrument->tx (vals is)))) s)
  (with-quotes [s qs]
    (when (seq qs) (d/transact! conn (mapv quote->tx (vals qs)))) s)
  (with-series [s sm]
    (when (seq sm) (d/transact! conn (mapv series->tx (vals sm)))) s)
  (with-feed-licenses [s fls]
    (when (seq fls) (d/transact! conn (mapv feed-license->tx (vals fls)))) s)
  (with-contracts [s cts]
    (when (seq cts) (d/transact! conn (mapv contract->tx (vals cts)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`; empty when
  omitted."
  ([] (datomic-store {}))
  ([{:keys [instruments quotes series feed-licenses contracts]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (-> s (with-instruments instruments) (with-quotes quotes)
         (with-series series) (with-feed-licenses feed-licenses)
         (with-contracts contracts)))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo data — the Datomic-backed analog of
  `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))

;; ───────────────────────── ledger formatting ─────────────────────────

(defn ledger-line
  "Human-readable one-liner for a ledger fact (used by the demo)."
  [{:keys [op actor subject disposition basis]}]
  (str/join " · "
            [(name disposition)
             (str "op=" op)
             (str "actor=" actor)
             (str "subject=" subject)
             (str "basis=" (pr-str basis))]))
