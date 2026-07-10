(ns marketdata.report
  "Disclosure rendering — output as a GOVERNED read. The column set is not
  chosen here; it is whatever the MarketDataGovernor's licensed-disclosure
  gate approved for the caller's contract tier (see `:disclosure/query`).
  This namespace only renders the approved columns, so a disclosure can
  never exceed the licensed tier — the market-data-vendor 'quote sheet'
  feature, with the tier-column policy fixed in code."
  (:require [marketdata.store :as store]))

(defn render-quote
  "Render one instrument's quote over exactly `columns` (already governor-
  approved). `:series` is only ever rendered when the caller's tier
  included it; `:raw-source` (full provenance detail) likewise."
  [db instrument-id columns]
  (let [inst (store/instrument db instrument-id)
        q    (store/quote* db instrument-id)
        cell (fn [col]
               (case col
                 :instrument-id instrument-id
                 :symbol        (:symbol inst)
                 :asset-class   (:asset-class inst)
                 :price         (:price q)
                 :currency      (:currency q)
                 :as-of         (:as-of q)
                 :series        (:bars (store/series db (str instrument-id "-daily")))
                 :raw-source    (:source q)
                 nil))]
    (into {} (map (juxt identity cell)) columns)))
