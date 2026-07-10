(ns marketdata.feed-demo
  "Live smoke test for `marketdata.feed` against the real ECB/EIA/FRED
  APIs -- NOT run by `clojure -M:dev:test` (network + real API keys
  required for EIA/FRED; ECB needs no key). Run explicitly:

    clojure -M:feed:dev:run-feed
    EIA_API_KEY=... FRED_API_KEY=... clojure -M:feed:dev:run-feed

  Reads API keys from env HERE (the one place in this actor allowed to --
  `marketdata.feed` itself never does, see its docstring) and pushes each
  fetched quote through the real `OperationActor` (`marketdata.operation`)
  at phase 3, so this doubles as an end-to-end proof that a live feed
  actually satisfies the MarketDataGovernor's source-provenance-gate (a
  fetched-but-malformed price would HOLD here exactly like a hand-built one
  does in `marketdata.sim`). Plain `.clj` (not `.cljc`) -- this file is a
  JVM CLI script with no portable content, unlike `marketdata.feed` itself."
  (:require [langgraph.graph :as g]
            [marketdata.feed :as feed]
            [marketdata.store :as store]
            [marketdata.operation :as op]))

(defn- ingest! [actor thread-id req]
  (let [res (g/run* actor {:request req :context {:actor-id "feed-demo" :actor-role :feed-operator :phase 3}}
                    {:thread-id thread-id})]
    (println " " thread-id "->" (get-in res [:state :disposition])
             (when-let [v (get-in res [:state :verdict :violations])] (when (seq v) (str "violations=" v))))
    res))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]

    (println "── ECB euro FX reference rates (no key) ──")
    (try
      (doseq [req (feed/fetch-ecb-fx-rates)]
        (ingest! actor (str "ecb-" (:instrument-id req)) req))
      (catch Exception e (println "  FAILED:" (ex-message e))))

    (println "\n── US EIA Open Data (needs EIA_API_KEY) ──")
    (if-let [k (System/getenv "EIA_API_KEY")]
      (try
        (when-let [req (feed/fetch-eia-spot k)]
          (ingest! actor "eia-cm-100" req))
        (catch Exception e (println "  FAILED:" (ex-message e))))
      (println "  SKIPPED: EIA_API_KEY not set"))

    (println "\n── FRED Case-Shiller HPI (needs FRED_API_KEY) ──")
    (if-let [k (System/getenv "FRED_API_KEY")]
      (try
        (when-let [req (feed/fetch-fred-series k)]
          (ingest! actor "fred-re-100" req))
        (catch Exception e (println "  FAILED:" (ex-message e))))
      (println "  SKIPPED: FRED_API_KEY not set"))

    (println "\n── resulting quotes ──")
    (doseq [i (store/all-instruments db)]
      (println " " (:id i) (:symbol i) "->" (:price (store/quote* db (:id i)))))

    (println "\ndone.")))
