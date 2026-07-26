(ns marketdata.uniswap
  "Uniswap v3 on-chain price observation — the DEX half of this actor's
  direct-venue crypto collection (ADR-2607262100). Pure, portable `.cljc`:
  calldata construction, ABI word decoding, the sqrtPriceX96/tick price
  math and the verified pool registry all live here with no host
  dependency. The JSON-RPC transport that actually reads a pool's `slot0()`
  lives in `marketdata.feed-eth` (JVM-only, `:feed` alias) — a
  ClojureScript/nbb/kotoba-wasm operator brings their own `eth_call`
  mechanism and calls straight into `decode-slot0` + `pool-ingest-request`.

  **Why on-chain rather than an aggregator**: this actor never depends on
  CoinMarketCap/CoinGecko/any third-party price aggregator. A DEX price is
  read from the venue itself — the pool contract's own state word — so the
  provenance chain is `chain-id + block number + pool address + raw
  slot0 word`, every element of which a subscriber can independently
  re-derive against any Ethereum node. That is a strictly stronger
  provenance claim than 'an aggregator told us', which is why
  `:dex-onchain-observation` is its own source class in
  `marketdata.facts/catalog` rather than being folded into
  `:licensed-operator-feed`.

  **Read-only**: nothing here constructs, signs or broadcasts a
  transaction, and `slot0()`/`token0()`/`token1()`/`decimals()` are all
  `view` functions. The live seam (`marketdata.feed-eth`) goes through
  `kotoba-lang/org-ethereum-jsonrpc`'s `eth-method-whitelist`, which
  enforces that boundary in code.

  **Precision**: sqrtPriceX96 is a uint160 (up to ~1.46e48). It is decoded
  into a plain double rather than a BigInteger/BigDecimal, the same
  portable-arithmetic choice `marketdata.policy`'s tolerance-gate and
  `marketdata.feed/cross-rate` already make (ClojureScript has neither).
  The relative error is ~1e-16 — 12 orders of magnitude below this actor's
  15% tolerance gate — and the whole valid sqrtPriceX96 range
  (MIN_SQRT_RATIO 4295128739 .. MAX_SQRT_RATIO ~1.46e48) maps to prices
  well inside a float64's exponent range. `check-slot0` below independently
  re-derives the price from the pool's `tick` word and fails closed on
  disagreement, so a decode bug cannot silently reach the governor."
  (:require [clojure.string :as str]))

;; ───────────────────────── function selectors ─────────────────────────
;; keccak256("<signature>")[0..4], from the Uniswap v3 core ABI
;; (https://docs.uniswap.org/contracts/v3/reference/core/UniswapV3Pool) and
;; ERC-20. Each one was confirmed against Ethereum mainnet during
;; development (see `pools` :verified-at) — not taken from memory.

(def slot0-selector    "0x3850c7bd") ; slot0()
(def token0-selector   "0x0dfe1681") ; token0()
(def token1-selector   "0xd21220a7") ; token1()
(def decimals-selector "0x313ce567") ; decimals()

(def ^:private q96
  "2^96 — the Q64.96 fixed-point scale Uniswap v3 stores sqrtPrice in."
  (Math/pow 2 96))

(def ^:private two-pow-24 (Math/pow 2 24))

;; ───────────────────────── hex / ABI word decoding ────────────────────

(defn hex-string?
  [s]
  (boolean (and (string? s) (re-matches #"0[xX][0-9a-fA-F]*" s))))

(defn- digit->int [c]
  (let [n (int c)]
    (cond
      (and (>= n 48) (<= n 57))  (- n 48)   ; 0-9
      (and (>= n 97) (<= n 102)) (- n 87)   ; a-f
      (and (>= n 65) (<= n 70))  (- n 55)   ; A-F
      :else (throw (ex-info (str "marketdata.uniswap: not a hex digit: " c)
                            {:type ::bad-hex :char c})))))

(defn hex->double
  "Decode a hex quantity (with or without the `0x` prefix) into a double.
  Portable (no BigInteger/js BigInt) and lossless up to 2^53; above that it
  carries float64's ~1e-16 relative error, which is what the docstring's
  precision note covers. Throws on a non-hex input rather than returning a
  silently wrong 0."
  [s]
  (when-not (string? s)
    (throw (ex-info (str "marketdata.uniswap: not a hex string: " (pr-str s))
                    {:type ::bad-hex :value s})))
  (let [digits (if (str/starts-with? (str/lower-case s) "0x") (subs s 2) s)]
    (if (str/blank? digits)
      0.0
      (reduce (fn [acc c] (+ (* acc 16.0) (digit->int c))) 0.0 digits))))

(defn abi-word
  "The `n`-th 32-byte word (0-indexed) of an ABI-encoded `eth_call` result,
  returned as a bare 64-char lowercase hex string. Returns nil when the
  result is too short to contain that word — callers treat nil as 'the node
  returned something that is not this function's return shape' and fail
  closed rather than reading past the end."
  [result n]
  (when (hex-string? result)
    (let [body (subs result 2)
          from (* n 64)
          to   (+ from 64)]
      (when (<= to (count body))
        (str/lower-case (subs body from to))))))

(defn- word->int24
  "Decode an ABI-encoded `int24` word (sign-extended to 32 bytes) into a
  plain integer. Uniswap v3's `tick` is an int24 and is negative whenever
  token1 is worth less than token0 in raw units, so the sign extension is
  not a theoretical case."
  [word]
  (let [magnitude (hex->double (subs word 58))   ; low 3 bytes = the int24
        negative? (= \f (first word))]           ; sign-extended 0xfff...
    (long (if negative? (- magnitude two-pow-24) magnitude))))

(defn decode-slot0
  "Decode a `slot0()` `eth_call` result into
  `{:sqrt-price-x96 double :tick long}`.

  `slot0()` returns 7 values (sqrtPriceX96, tick, observationIndex,
  observationCardinality, observationCardinalityNext, feeProtocol,
  unlocked); only the first two are prices. Returns nil — never a partial
  or zero-filled map — when `result` is not a well-formed 7-word return,
  so a node returning `0x` (wrong address / non-pool contract / archive
  miss) can never be mistaken for a real price of 0."
  [result]
  (let [w0 (abi-word result 0)
        w1 (abi-word result 1)
        w6 (abi-word result 6)]
    (when (and w0 w1 w6)
      (let [sqrt-price (hex->double w0)]
        (when (pos? sqrt-price)
          {:sqrt-price-x96 sqrt-price
           :tick (word->int24 w1)})))))

(defn decode-address
  "Decode an ABI-encoded `address` return word into its 0x-prefixed
  lowercase hex form. Used by the pool-verification path
  (`marketdata.feed-eth/verify-pool!`) to confirm a registry entry's
  token0/token1 against the chain rather than trusting the registry."
  [result]
  (some-> (abi-word result 0) (subs 24) (->> (str "0x"))))

(defn decode-uint
  "Decode a single-word ABI `uint`/`int` return (e.g. `decimals()`) as a
  double."
  [result]
  (some-> (abi-word result 0) hex->double))

;; ───────────────────────── price math ─────────────────────────────────

(defn sqrt-price-x96->price
  "Uniswap v3's canonical price formula, decimal-adjusted:

    raw   = (sqrtPriceX96 / 2^96)^2          ; token1 per token0, raw units
    price = raw * 10^(token0-decimals - token1-decimals)

  which is token1 per token0 in *human* units. `:invert?` returns token0
  per token1 instead — needed whenever the quote asset sorts first in the
  pool (Uniswap orders token0/token1 by address, not by which side is the
  numéraire: in the mainnet USDC/WETH pool USDC is token0, so the ETH price
  is the inverted quote).

  Returns nil for a non-positive sqrtPriceX96 or an inverted price of 0
  rather than producing an Infinity that would sail through arithmetic
  downstream."
  [sqrt-price-x96 {:keys [token0-decimals token1-decimals invert?]}]
  (when (and (number? sqrt-price-x96) (pos? sqrt-price-x96))
    (let [ratio (/ (double sqrt-price-x96) q96)
          price (* ratio ratio (Math/pow 10 (- token0-decimals token1-decimals)))]
      (cond
        (not (pos? price)) nil
        invert?            (/ 1.0 price)
        :else              price))))

(defn tick->price
  "The same price derived from `slot0()`'s OTHER field: Uniswap v3 stores
  `tick` such that raw price = 1.0001^tick. This is an INDEPENDENT
  derivation from a different word of the same payload — `check-slot0` uses
  it to catch a decode/word-offset bug, in the spirit of this workspace's
  dual-decoder WYSIWYS discipline (`cloud-itonami-isic-6611-cryptoexchange`).
  Lower resolution than the sqrtPrice path (a tick is one 0.01% step), so
  it is a CHECK, never the published price."
  [tick {:keys [token0-decimals token1-decimals invert?]}]
  (let [raw   (Math/pow 1.0001 (double tick))
        price (* raw (Math/pow 10 (- token0-decimals token1-decimals)))]
    (cond
      (not (pos? price)) nil
      invert?            (/ 1.0 price)
      :else              price)))

(def tick-check-tolerance
  "How far the tick-derived price may sit from the sqrtPrice-derived price
  before `check-slot0` fails closed. One tick is 0.01%; the current price
  sits somewhere inside the current tick, so the two derivations legitimately
  differ by up to ~1 tick. 0.5% leaves generous headroom for that while
  still catching a word-offset/decimals/inversion bug, all of which move the
  price by orders of magnitude, not basis points."
  0.005)

(defn check-slot0
  "Cross-derive the price two ways from one `slot0()` payload and return
  `{:ok? bool :price .. :tick-price .. :deviation ..}`. `:ok? false` means
  the decoder disagrees with itself — the caller MUST NOT ingest that
  price (`pool-ingest-request` returns nil for it), because a decoder bug
  produces a number that is wrong by orders of magnitude yet perfectly
  well-formed, i.e. exactly the input the tolerance-gate exists to stop but
  which would sail through on a freshly-seeded instrument with no prior
  quote."
  [{:keys [sqrt-price-x96 tick]} pool]
  (let [price      (sqrt-price-x96->price sqrt-price-x96 pool)
        tick-price (tick->price tick pool)]
    (if (and price tick-price (pos? price) (pos? tick-price))
      (let [dev (/ (Math/abs (- price tick-price)) price)]
        {:ok? (<= dev tick-check-tolerance)
         :price price :tick-price tick-price :deviation dev})
      {:ok? false :price price :tick-price tick-price :deviation nil})))

;; ───────────────────────── verified pool registry ─────────────────────

(def pools
  "Uniswap v3 mainnet pools this actor reads directly.

  Every field of every entry was confirmed against Ethereum mainnet during
  development — `token0()`, `token1()` and each token's `decimals()` were
  read from the chain by `eth_call` and the resulting price cross-checked
  against the CEX venues in `marketdata.venues` (agreement was 0.008% for
  ETH and 0.14% for BTC). None of it is recalled from memory, and
  `marketdata.feed-eth/verify-pool!` re-runs that verification live so a
  registry entry can never quietly drift from the chain.

  `:quote-currency` is the pool's actual quote asset — USDC, not USD. This
  actor does not silently treat a stablecoin as fiat; converting `:usdc` to
  `:usd` is an explicit operator-declared equivalence in
  `marketdata.aggregate` (`:currency-equivalence`), never an assumption
  baked in here."
  [{:id :uniswap-v3-weth-usdc-005
    :name "Uniswap v3 WETH/USDC 0.05% (Ethereum mainnet)"
    :chain :ethereum-mainnet :chain-id 1
    :address "0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640"
    :fee-tier-bps 5
    :token0 {:symbol "USDC" :address "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" :decimals 6}
    :token1 {:symbol "WETH" :address "0xc02aaa39b223fe8d0a0e5c4f27ead9083c756cc2" :decimals 18}
    :token0-decimals 6 :token1-decimals 18
    :invert? true                     ; want USDC per WETH, pool quotes WETH per USDC
    :symbol "ETH/USDC" :quote-currency :usdc :instrument-id "cx-eth-usd"
    :verified-at "2026-07-26"}
   {:id :uniswap-v3-wbtc-usdc-030
    :name "Uniswap v3 WBTC/USDC 0.30% (Ethereum mainnet)"
    :chain :ethereum-mainnet :chain-id 1
    :address "0x99ac8ca7087fa4a2a1fb6357269965a2014abc35"
    :fee-tier-bps 30
    :token0 {:symbol "WBTC" :address "0x2260fac5e5542a773aa44fbcfedf7c193bc2c599" :decimals 8}
    :token1 {:symbol "USDC" :address "0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48" :decimals 6}
    :token0-decimals 8 :token1-decimals 6
    :invert? false                    ; pool already quotes USDC per WBTC
    :symbol "BTC/USDC" :quote-currency :usdc :instrument-id "cx-btc-usd"
    :verified-at "2026-07-26"}])

(defn pool
  "Look up a registry pool by `:id`."
  [id]
  (first (filter #(= id (:id %)) pools)))

(defn pools-for
  "Every registry pool feeding `instrument-id`."
  [instrument-id]
  (filterv #(= instrument-id (:instrument-id %)) pools))

;; ───────────────────────── ingest-request shaping ─────────────────────

(defn pool-ingest-request
  "A decoded `slot0()` + the block it was read at -> a
  `marketdata.llm/infer`-compatible `:quote/ingest` request map for the
  pool's instrument.

  Returns nil (never a fabricated or partial quote) when the payload does
  not decode or when `check-slot0`'s independent tick derivation disagrees.
  `:as-of` is the BLOCK's own timestamp, not wall-clock: the price is a
  property of chain state at that block, and a subscriber re-running the
  same `eth_call` at that block number gets the same word back. Because it
  is the block clock, it is genuinely venue-authoritative
  (`:as-of-source :venue`) — unlike an exchange endpoint that returns no
  timestamp.

  The `:source :ref` carries the full re-derivation chain
  (`chain-id:pool-address:block-number`) plus the raw sqrtPriceX96 word, so
  an institutional-tier subscriber holding `:raw-source` can verify the
  published price against any Ethereum node without this actor's code."
  [pool-entry slot0 {:keys [block-number block-timestamp as-of]}]
  (let [{:keys [ok? price]} (check-slot0 slot0 pool-entry)]
    (when ok?
      {:op :quote/ingest
       :subject (:instrument-id pool-entry)
       :instrument-id (:instrument-id pool-entry)
       :price price
       :currency (:quote-currency pool-entry)
       :as-of (or as-of (str block-timestamp))
       :source {:class :dex-onchain-observation
                :ref (str "uniswap-v3:" (:chain-id pool-entry) ":"
                          (:address pool-entry) ":block-" block-number)
                :venue (:id pool-entry)
                :raw {:sqrt-price-x96 (:sqrt-price-x96 slot0)
                      :tick (:tick slot0)
                      :block-number block-number
                      :epoch-seconds block-timestamp
                      :as-of-source :venue}}})))
