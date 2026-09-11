(ns marketdata.aggregate
  "Cross-venue reference price — how this actor turns N first-party venue
  observations (`marketdata.venues`) and on-chain DEX observations
  (`marketdata.uniswap`) into ONE published crypto price, without ever
  asking an aggregator what the price is (ADR-2607262100).

  This is the namespace where a market-data service usually starts lying,
  so the design is deliberately unfashionable:

  - **Median, not volume-weighted.** A volume weighting is only as honest
    as the venues' self-reported volume, which is the single most
    manipulated field in crypto market data. The median of last prices
    needs no trust in any venue's volume claim, and a single manipulated
    venue cannot move it.
  - **Fails closed on disagreement.** If the venues disagree by more than
    `:max-dispersion`, this returns `{:ok? false :reason
    :dispersion-exceeded}` and NO price is published. A CoinMarketCap-style
    service would quietly drop the outlier and publish anyway; here silent
    outlier-dropping is opt-in (`:drop-outliers? true`) and every dropped
    venue is recorded in the result and in the published provenance.
  - **Never invents a conversion.** Binance quotes USDT, Uniswap quotes
    USDC, bitFlyer quotes JPY, Coinbase/Kraken quote USD. Collapsing those
    is a decision with real consequences (a depegged stablecoin is exactly
    when a price feed matters most), so a quote is only converted when the
    operator has explicitly declared `:currency-equivalence` (a stated
    peg assumption) or supplied a real `:fx-rates` entry — otherwise the
    quote is EXCLUDED with a reason, never silently treated as dollars.
    The natural source for `:fx-rates` is this same actor's ECB connector
    (`marketdata.feed/cross-rate`), so the FX leg is itself sourced.
  - **Quorum.** Below `:min-venues` surviving constituents, nothing is
    published — a 'reference price' from one venue is just that venue's
    price wearing a hat.

  Pure `.cljc`, no clock and no I/O: freshness is checked only against a
  `:now-epoch-seconds` the caller supplies, so this namespace can never
  disagree with itself between runtimes."
  (:require [kotoba.lang.text :as str]))

(def default-opts
  {:min-venues 3
   :max-dispersion 0.02
   :drop-outliers? false
   :currency-equivalence {}
   :fx-rates {}
   :max-age-seconds 300})

;; ───────────────────────── currency normalization ─────────────────────

(defn- convert
  "Convert `price` from `from` currency to `to`, returning
  `[converted-price basis]` or nil when no declared conversion path
  exists. `basis` records HOW it was converted so the published provenance
  can carry it:
    :identity            — already the target currency
    [:declared-equivalent from] — operator declared the pair equivalent
    [:fx-rate from rate]        — a real FX rate the operator supplied"
  [price from to {:keys [currency-equivalence fx-rates]}]
  (cond
    (= from to)
    [price :identity]

    (= to (get currency-equivalence from))
    [price [:declared-equivalent from]]

    (get fx-rates [from to])
    (let [r (double (get fx-rates [from to]))]
      (when (pos? r) [(* (double price) r) [:fx-rate from r]]))

    :else nil))

(defn normalize
  "`:quote/ingest` requests (from `marketdata.venues/venue-ingest-request`
  and/or `marketdata.uniswap/pool-ingest-request`) -> a vector of
  constituent maps, each either usable or excluded WITH A REASON. Nothing
  is dropped silently — `reference-price` reports the exclusions and they
  end up in the published provenance."
  [requests {:keys [target-currency now-epoch-seconds max-age-seconds] :as opts}]
  (mapv
   (fn [{:keys [price currency as-of source]}]
     (let [base {:venue (:venue source)
                 :class (:class source)
                 :ref (:ref source)
                 :price price
                 :currency currency
                 :as-of as-of}
           epoch (get-in source [:raw :epoch-seconds])
           age   (when (and now-epoch-seconds epoch) (- now-epoch-seconds epoch))]
       (cond
         (not (and (number? price) (pos? price)))
         (assoc base :usable? false :excluded :no-price)

         (and age max-age-seconds (> age max-age-seconds))
         (assoc base :usable? false :excluded :stale :age-seconds age)

         :else
         (if-let [[converted basis] (convert price currency target-currency opts)]
           (assoc base :usable? true :converted-price (double converted)
                  :conversion basis :age-seconds age
                  :freshness (if age :known :unknown))
           (assoc base :usable? false :excluded :no-conversion-path)))))
   requests))

;; ───────────────────────── statistics (pure) ──────────────────────────

(defn median
  [xs]
  (when (seq xs)
    (let [v (vec (sort xs)) n (count v) m (quot n 2)]
      (if (odd? n)
        (nth v m)
        (/ (+ (nth v (dec m)) (nth v m)) 2.0)))))

(defn- dispersion
  "Max relative deviation of any value from `mid`."
  [xs mid]
  (when (and (seq xs) (pos? (double mid)))
    (apply max (map #(/ (Math/abs (- (double %) (double mid))) (double mid)) xs))))

;; ───────────────────────── reference price ────────────────────────────

(defn reference-price
  "N venue/pool `:quote/ingest` requests -> ONE reference price, or a
  refusal. Returns:

    {:ok? true  :price .. :currency .. :venue-count n :dispersion d
     :constituents [..] :excluded [..] :dropped [..]}
    {:ok? false :reason :insufficient-venues|:dispersion-exceeded
     :venue-count n :dispersion d :constituents [..] :excluded [..]}

  `:ok? false` is a normal, expected outcome — the caller publishes
  nothing. There is no code path in this namespace that returns a price
  computed from fewer than `:min-venues` constituents or from constituents
  that disagree by more than `:max-dispersion`."
  [requests opts]
  (let [{:keys [min-venues max-dispersion drop-outliers? target-currency] :as o}
        (merge default-opts opts)
        _ (when-not target-currency
            (throw (ex-info "marketdata.aggregate: :target-currency is required"
                            {:type ::missing-target-currency})))
        all       (normalize requests o)
        excluded  (filterv (complement :usable?) all)
        usable    (filterv :usable? all)
        prices    (mapv :converted-price usable)
        mid       (median prices)
        ;; outliers are measured against the median of ALL usable quotes;
        ;; the published figures below are then recomputed over what
        ;; survives, so a drop can never quietly reuse the pre-drop median.
        outliers  (when (and drop-outliers? mid)
                    (filterv #(> (/ (Math/abs (- (:converted-price %) (double mid))) (double mid))
                                 max-dispersion)
                             usable))
        kept      (if (seq outliers) (filterv (complement (set outliers)) usable) usable)
        kept-px   (mapv :converted-price kept)
        kept-mid  (median kept-px)
        kept-disp (dispersion kept-px kept-mid)
        base      {:constituents kept :excluded excluded
                   :dropped (vec outliers)
                   :venue-count (count kept)
                   :dispersion kept-disp
                   :currency target-currency}]
    (cond
      (< (count kept) min-venues)
      (assoc base :ok? false :reason :insufficient-venues)

      (and kept-disp (> kept-disp max-dispersion))
      (assoc base :ok? false :reason :dispersion-exceeded)

      :else
      (assoc base :ok? true :price (double kept-mid)))))

;; ───────────────────────── composite ingest request ───────────────────

(defn composite-ingest-request
  "A successful `reference-price` result -> a `:quote/ingest` request map
  citing `:cross-venue-composite` provenance.

  The composite source is NOT a bare assertion: it enumerates every
  constituent (venue, its own source class, its own `:ref`, its observed
  price and the conversion basis applied). `marketdata.policy`'s
  source-provenance-gate independently re-checks that enumeration —
  quorum, each constituent's class being itself allowed, no nesting of one
  composite inside another, no duplicate refs — so a composite cannot be
  used to launder an unsourced or aggregator-derived price past the
  governor. Returns nil for a refused reference price."
  [instrument-id {:keys [ok? price currency constituents dispersion dropped]} as-of]
  (when ok?
    {:op :quote/ingest
     :subject instrument-id
     :instrument-id instrument-id
     :price price
     :currency currency
     :as-of as-of
     :source {:class :cross-venue-composite
              :ref (str "cross-venue-median:" instrument-id ":" as-of
                        ":n=" (count constituents))
              :method :median
              :dispersion dispersion
              :constituents (mapv (fn [c]
                                    {:class (:class c) :ref (:ref c)
                                     :venue (:venue c)
                                     :price (:price c) :currency (:currency c)
                                     :converted-price (:converted-price c)
                                     :conversion (:conversion c)
                                     :as-of (:as-of c)})
                                  constituents)
              :dropped (mapv :ref dropped)}}))

(defn describe
  "One-line human summary of a `reference-price` result — used by the demo
  and the operator console so what is shown always matches what was
  computed."
  [{:keys [ok? reason price currency venue-count dispersion constituents excluded]}]
  (if ok?
    (str "reference " price " " (name currency)
         " · n=" venue-count
         " · dispersion=" (when dispersion (str (Math/round (* 10000.0 dispersion)) "bp"))
         " · venues=" (str/join "," (map #(name (or (:venue %) :unknown)) constituents)))
    (str "NO PRICE PUBLISHED (" (name (or reason :unknown)) ")"
         " · usable=" venue-count
         (when (seq excluded)
           (str " · excluded=" (str/join "," (map #(str (name (or (:venue %) :unknown))
                                                        "/" (name (:excluded %)))
                                                  excluded)))))))
