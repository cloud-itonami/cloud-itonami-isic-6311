(ns marketdata.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 6+): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`marketdata.operation` -> `marketdata.policy` (MarketDataGovernor) ->
  `marketdata.store`) through a scenario adapted from this repo's own
  `marketdata.sim` demo driver (`clojure -M:dev:run`, confirmed BY ACTUALLY
  RUNNING IT before this file was written to produce exactly the dispositions
  its own comments claim -- unlike `cloud-itonami-isic-851`'s `schoolops.sim`,
  this repo's own sim driver uses ids that DO match `marketdata.store/
  demo-data`, so it was safe to reuse rather than author from scratch), plus
  one additional `:series/derive` call (sim.cljc itself never exercises that
  op) so the action-gate table below is backed by real telemetry for all
  four ops in `marketdata.phase/write-ops` + `:disclosure/query`, not just
  documented. Rendered deterministically -- no invented numbers, no
  timestamps in the page content, byte-identical across reruns against the
  same seed (verified by diffing two consecutive runs).

  Scenario roles: unlike the single-role realty/schoolops examples, this
  governor's `rbac` check is keyed on `:actor-role` per op
  (`marketdata.policy/permissions`), so `exec!` below takes an explicit
  `context` per call (feed-operator for ingest/derive, data-quality-officer
  for correction, subscriber for disclosure) -- using the wrong role would
  make the FIRST hard violation `:rbac` instead of the intended
  `:tolerance-gate`/`:source-provenance-gate`/`:licensed-disclosure`, which
  would misrepresent what this scenario is meant to demonstrate.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.string :as str]
            [marketdata.store :as store]
            [marketdata.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness -----------------------------

(def ^:private operator          {:actor-id "op-1" :actor-role :feed-operator :phase 3})
(def ^:private quality-officer   {:actor-id "qo-1" :actor-role :data-quality-officer :phase 3})
(def ^:private subscriber-basic  {:actor-id "sub-1" :actor-role :subscriber :tenant "tenant-basic" :phase 3})
(def ^:private subscriber-ghost  {:actor-id "sub-2" :actor-role :subscriber :tenant "tenant-ghost" :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid by]
  (g/run* actor {:approval {:status :approved :by by}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition this
  actor can reach (mined from this repo's own verified-correct
  `marketdata.sim`, plus one extra op): fx-100 ingests a clean ECB reference
  rate (auto-commit, phase-3, no capital risk); re-100 derives a daily-bar
  series from a citable government statistical index (auto-commit, phase-3
  -- exercises the `:series/derive` op sim.cljc itself never calls);
  eq-200 (halted / circuit-breaker) ingests a clean-otherwise price tick
  (ALWAYS escalates on `:halted?` regardless of confidence -- approved);
  eq-100 gets a correction/request dispute (ALWAYS escalates, any phase,
  any confidence -- approved); eq-100 ALSO HARD-holds on `:source-
  provenance-gate` (a feed tick arrives with no source citation at all --
  a dropped feed header), tenant-basic HARD-holds on `:licensed-disclosure`
  (an over-disclosure query pulling `:series`/`:raw-source` beyond its
  tier), tenant-ghost HARD-holds on `:licensed-disclosure` (query from an
  unregistered tenant, no active contract at all), and cr-100 HARD-holds on
  `:tolerance-gate` (an ingested price 90% off the last known-good quote --
  fat-finger/stale-feed defense). Every HARD hold never reaches a human.
  Returns the resulting store -- every field read by `render` below is real
  governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    (exec! actor "op1-fx-ingest"
           {:op :quote/ingest :subject "fx-100" :instrument-id "fx-100"
            :price 157.40M :currency :jpy :as-of "2026-07-10T12:00:00Z"
            :source {:class :central-bank-reference-rate :ref "ecb-fx-reference-rates:usd-jpy"}}
           operator)

    (exec! actor "op7-re-derive"
           {:op :series/derive :subject "re-100" :series-id "re-100-daily"
            :instrument-id "re-100"
            :bars [{:date "2026-04-01" :close 313.9M} {:date "2026-05-01" :close 315.6M}]
            :source {:class :government-statistical-index :ref "fred-case-shiller-hpi:2026-05"}}
           operator)

    (exec! actor "op4-eq200-halted-ingest"
           {:op :quote/ingest :subject "eq-200" :instrument-id "eq-200"
            :price 87.90M :currency :usd :as-of "2026-07-10T12:00:00Z"
            :source {:class :licensed-operator-feed :ref "lic-demo:eq-200" :license-id "lic-demo"}}
           operator)
    (approve! actor "op4-eq200-halted-ingest" "op-1")

    (exec! actor "op5-eq100-correction"
           {:op :correction/request :subject "eq-100" :disputed-field :price :claim 142.75M}
           quality-officer)
    (approve! actor "op5-eq100-correction" "qo-1")

    (exec! actor "op2-eq100-unsourced"
           {:op :quote/ingest :subject "eq-100" :instrument-id "eq-100"
            :price 143.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
            :source {:class :licensed-operator-feed :ref "lic-demo:eq-100" :license-id "lic-demo"}
            :unsourced? true}
           operator)

    (exec! actor "op3-basic-overdisclose"
           {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100" :greedy? true}
           subscriber-basic)

    (exec! actor "op3a-ghost-tenant"
           {:op :disclosure/query :subject "eq-100" :instrument-id "eq-100"}
           subscriber-ghost)

    (exec! actor "op6-cr100-tolerance"
           {:op :quote/ingest :subject "cr-100" :instrument-id "cr-100"
            :price 6125.00M :currency :usd :as-of "2026-07-10T12:00:00Z"
            :source {:class :licensed-operator-feed :ref "lic-demo:cr-100" :license-id "lic-demo"}}
           operator)
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger instrument-id]
  (last (filter #(= (:subject %) instrument-id) ledger)))

(defn- status-cell [ledger instrument-id]
  (let [f (last-fact-for ledger instrument-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :policy-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- trading-cell [{:keys [status circuit-breaker?]}]
  (if (or circuit-breaker? (= :halted status))
    "<span class=\"critical\">halted &middot; circuit-breaker</span>"
    "<span class=\"ok\">trading</span>"))

(defn- instrument-row [ledger {:keys [id symbol asset-class venue] :as inst}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc symbol) (esc (name (or asset-class :n-a))) (esc venue)
          (trading-cell inst) (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README `Ops` table, `marketdata.policy`/`marketdata.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:quote/ingest</code></td><td><span class=\"ok\">phase-3 auto-commit when clean</span> &middot; tolerance-gate + source-provenance-gate enforced; halted/circuit-broken instrument ALWAYS escalates regardless of confidence</td></tr>"
   "        <tr><td><code>:series/derive</code></td><td><span class=\"ok\">phase-3 auto-commit when clean</span> &middot; source-provenance-gate enforced</td></tr>"
   "        <tr><td><code>:disclosure/query</code></td><td><span class=\"warn\">governed read, not phase-gated</span> &middot; licensed-disclosure: active subscriber contract required, tier column ceiling enforced</td></tr>"
   "        <tr><td><code>:correction/request</code></td><td><span class=\"warn\">ALWAYS human review</span> &middot; never auto, any phase, any confidence</td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db` that
  has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        instruments (store/all-instruments db)
        instrument-rows (str/join "\n" (map (partial instrument-row ledger) instruments))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-6311 &middot; market-data operations</title><style>\n"
     "table { width: 100%; border-collapse: collapse; font-size: 14px; }\n"
     ".ok { color: #137a3f; }\n"
     "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }\n"
     "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }\n"
     "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }\n"
     "h2 { margin-top: 0; font-size: 15px; }\n"
     ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }\n"
     "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }\n"
     "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }\n"
     ".muted { color: #888; font-size: 13px; }\n"
     ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }\n"
     ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }\n"
     ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }\n"
     "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }\n"
     "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }\n"
     "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }\n"
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Market-data operations (ISIC 6311) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · never routes orders, custody or trade execution</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Instruments</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>marketdata.store</code> via <code>marketdata.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Instrument</th><th>Symbol</th><th>Asset class</th><th>Venue</th><th>Trading status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     instrument-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (MarketDataGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds (rbac / tolerance-gate / source-provenance-gate / licensed-disclosure) cannot be overridden. Ingested prices are checked against the last known-good quote; provenance is verified against a closed source-class catalog plus, for licensed feeds, an active feed-license; disclosures never exceed the caller's licensed contract tier.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Instrument / subject</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts )")))
