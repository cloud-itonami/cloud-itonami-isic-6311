(ns marketdata.feed-demo
  "Live smoke test for `marketdata.feed` against the real ECB/EIA/FRED
  APIs and the direct crypto venues -- NOT run by `clojure -M:dev:test`
  (network + real API keys required for EIA/FRED; ECB and the four CEX
  venues need no key; the Uniswap leg needs an RPC endpoint). Run
  explicitly:

    clojure -M:feed:dev:run-feed
    EIA_API_KEY=... FRED_API_KEY=... clojure -M:feed:dev:run-feed
    ETH_RPC_URL=https://<your-node> clojure -M:feed:dev:run-feed

  Reads API keys and the RPC endpoint from env HERE (the one place in this
  actor allowed to -- `marketdata.feed`/`marketdata.feed-eth` themselves
  never do, see their docstrings) and pushes each fetched quote through the
  real `OperationActor` (`marketdata.operation`) at phase 3, so this
  doubles as an end-to-end proof that a live feed actually satisfies the
  MarketDataGovernor's source-provenance-gate (a fetched-but-malformed
  price would HOLD here exactly like a hand-built one does in
  `marketdata.sim`). Plain `.clj` (not `.cljc`) -- this file is a JVM CLI
  script with no portable content, unlike `marketdata.feed` itself.

  The crypto section prints the FULL constituent table and, when the
  cross-venue median is refused (quorum or dispersion), prints the refusal
  rather than falling back to any single venue -- what it prints is exactly
  what the actor would publish, including publishing nothing."
  (:require [langgraph.graph :as g]
            [marketdata.aggregate :as agg]
            [marketdata.feed :as feed]
            [marketdata.feed-eth :as feed-eth]
            [marketdata.store :as store]
            [marketdata.venues :as venues]
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

    (println "\n── direct crypto venues (no aggregator, no key) ──")
    (println "  " (venues/describe))
    (let [rpc-url (System/getenv "ETH_RPC_URL")
          ;; The operator DECLARES the stablecoin peg assumption instead of
          ;; this actor smuggling it in: USDT/USDC quotes only become USD
          ;; here because the line below says so, and the declaration is
          ;; carried into every published constituent as :conversion.
          opts {:target-currency :usd
                :currency-equivalence {:usdt :usd :usdc :usd}
                :min-venues 3 :max-dispersion 0.02}]
      (when-not rpc-url
        (println "   SKIPPED (DEX leg): ETH_RPC_URL not set -- CEX venues only"))
      (doseq [instrument-id (venues/instrument-ids)]
        (println "\n  " instrument-id)
        (let [cex (mapv feed/fetch-venue-listing* (venues/listings-for instrument-id))
              dex (when rpc-url
                    (try (feed-eth/fetch-pool-quotes rpc-url instrument-id)
                         (catch Exception e
                           (println "    DEX FAILED:" (ex-message e)) [])))]
          (doseq [{:keys [ok? venue request error]} cex]
            (println "    " (name venue)
                     (if ok? (str (:price request) " " (name (:currency request))
                                  " @ " (:as-of request))
                         (str "-- " error))))
          (doseq [req dex]
            (println "     uniswap-v3" (:price req) (name (:currency req))
                     "@" (:as-of req) (str "(" (get-in req [:source :ref]) ")")))
          (let [reqs (into (vec (keep :request cex)) (or dex []))
                ref* (agg/reference-price reqs opts)]
            (println "    =>" (agg/describe ref*))
            (if-let [composite (agg/composite-ingest-request
                                instrument-id ref*
                                (str (java.time.Instant/now)))]
              (ingest! actor (str "cx-" instrument-id) composite)
              (println "     (nothing ingested -- reference price refused)"))))))

    (println "\n── resulting quotes ──")
    (doseq [i (store/all-instruments db)]
      (println " " (:id i) (:symbol i) "->" (:price (store/quote* db (:id i)))))

    (println "\ndone.")))
