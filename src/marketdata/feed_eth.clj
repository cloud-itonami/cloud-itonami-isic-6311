(ns marketdata.feed-eth
  "Ethereum JSON-RPC transport for the DEX leg of the direct-venue crypto
  collector (ADR-2607262100). Reads a Uniswap v3 pool's own `slot0()` state
  word with `eth_call` and hands it to the pure `marketdata.uniswap` layer.

  **Why this is a separate `.clj` namespace and not part of
  `marketdata.feed`**: it is the only namespace here that depends on
  `kotoba-lang/org-ethereum-jsonrpc`, which this repo declares in the
  `:feed` alias ONLY. `clojure -M:dev:test` (what CI runs, checking out
  just langgraph/langchain) must not need that checkout, and it does not —
  nothing under `test/` requires this namespace, and all the price logic it
  would want to test is pure and lives in `marketdata.uniswap`, which has
  no external dependency at all. Keeping the RPC seam here is what lets the
  test suite stay offline and the core stay portable.

  **Read-only, enforced upstream**: every request is built through
  `kotobase.ethereum.rpc/build-request`, whose `eth-method-whitelist`
  throws on anything that is not a read method. This namespace never
  constructs, signs or broadcasts a transaction and never touches a key.
  See that repo's permanent safety boundary.

  **The endpoint is the caller's**: no RPC URL is hardcoded as a default
  that quietly phones a specific provider, and no API key is read from env
  or any secret store here — same injected-credential discipline as
  `marketdata.feed`'s EIA/FRED keys. `marketdata.feed-demo` reads
  `ETH_RPC_URL` and passes it in explicitly."
  (:require [jsonista.core :as j]
            [kotobase.ethereum.rpc :as rpc]
            [marketdata.uniswap :as uni]
            [org.httpkit.client :as http]))

(defn- rpc!
  "POST one JSON-RPC request to `endpoint` and return the decoded result
  string, or throw. Uses `rpc/build-request` (whitelist enforcement) and
  `rpc/parse-response` (error shaping) rather than hand-rolling the
  envelope."
  [endpoint method params]
  (let [payload (rpc/build-request method params 1)
        {:keys [status body error]} @(http/post endpoint
                                                {:headers {"content-type" "application/json"}
                                                 :body (j/write-value-as-string payload)})]
    (when error
      (throw (ex-info "marketdata.feed-eth: HTTP transport error"
                      {:endpoint endpoint :method method :error error})))
    (when-not (<= 200 status 299)
      (throw (ex-info "marketdata.feed-eth: HTTP error status"
                      {:endpoint endpoint :method method :status status :body body})))
    (let [parsed (rpc/parse-response (j/read-value body))]
      (if (:ok? parsed)
        (:result parsed)
        (throw (ex-info "marketdata.feed-eth: JSON-RPC error"
                        {:endpoint endpoint :method method :error (:error parsed)}))))))

(defn eth-call
  "`eth_call` against `to` with raw `data` calldata, at `block` (default
  \"latest\"). Returns the raw hex result string."
  ([endpoint to data] (eth-call endpoint to data "latest"))
  ([endpoint to data block]
   (rpc! endpoint "eth_call" [{"to" to "data" data} block])))

(defn latest-block
  "-> {:number long :timestamp long :hash str} for the chain head. The
  block's own timestamp becomes the quote's `:as-of`: a DEX price is a
  property of chain state at a block, so the block clock — not this
  actor's wall clock — is the authoritative observation time."
  [endpoint]
  ;; `rpc!` returns the raw JSON-RPC result — for this method a block map
  ;; with STRING keys (the transport parses bodies string-keyed, which is
  ;; what `rpc/parse-response` expects).
  (let [b (rpc! endpoint "eth_getBlockByNumber" ["latest" false])]
    {:number (rpc/hex->long (get b "number"))
     :timestamp (rpc/hex->long (get b "timestamp"))
     :hash (get b "hash")}))

(defn verify-pool!
  "Re-verify a `marketdata.uniswap/pools` registry entry AGAINST THE CHAIN:
  reads the pool's `token0()`/`token1()` and each token's `decimals()` and
  compares them with what the registry claims. Returns `{:ok? bool
  :mismatches [..]}`.

  This exists because the registry is the one place in the crypto path
  where a hardcoded fact could silently rot (a fat-fingered address, or a
  copied-from-memory decimals value) and produce a well-formed price that
  is wrong by orders of magnitude. `fetch-pool-quote` calls it by default;
  `marketdata.feed-demo` prints it. Cheap: 4 read-only `eth_call`s."
  [endpoint {:keys [address token0 token1] :as pool-entry}]
  (let [got-t0 (uni/decode-address (eth-call endpoint address uni/token0-selector))
        got-t1 (uni/decode-address (eth-call endpoint address uni/token1-selector))
        got-d0 (some-> (eth-call endpoint (:address token0) uni/decimals-selector) uni/decode-uint long)
        got-d1 (some-> (eth-call endpoint (:address token1) uni/decimals-selector) uni/decode-uint long)
        mism (cond-> []
               (not= got-t0 (:address token0))
               (conj {:field :token0 :registry (:address token0) :chain got-t0})
               (not= got-t1 (:address token1))
               (conj {:field :token1 :registry (:address token1) :chain got-t1})
               (not= got-d0 (:token0-decimals pool-entry))
               (conj {:field :token0-decimals :registry (:token0-decimals pool-entry) :chain got-d0})
               (not= got-d1 (:token1-decimals pool-entry))
               (conj {:field :token1-decimals :registry (:token1-decimals pool-entry) :chain got-d1}))]
    {:ok? (empty? mism) :mismatches mism :pool (:id pool-entry)}))

(defn fetch-pool-quote
  "Live read of ONE Uniswap v3 pool -> a `:quote/ingest` request map, or
  nil.

  Order of operations matters and is deliberate: verify the registry
  against the chain FIRST (unless `:verify? false`), then read `slot0()` at
  the head block, then let `marketdata.uniswap/pool-ingest-request` refuse
  the quote if its independent tick re-derivation disagrees with the
  sqrtPrice one. Three independent ways for a wrong number to be caught
  before it ever reaches the governor."
  ([endpoint pool-entry] (fetch-pool-quote endpoint pool-entry {}))
  ([endpoint pool-entry {:keys [verify?] :or {verify? true}}]
   (when (or (not verify?) (:ok? (verify-pool! endpoint pool-entry)))
     (let [{:keys [number timestamp]} (latest-block endpoint)
           slot0 (uni/decode-slot0 (eth-call endpoint (:address pool-entry) uni/slot0-selector))]
       (when slot0
         (uni/pool-ingest-request pool-entry slot0
                                  {:block-number number
                                   :block-timestamp timestamp
                                   :as-of (str (java.time.Instant/ofEpochSecond timestamp))}))))))

(defn fetch-pool-quotes
  "Live read of every registry pool feeding `instrument-id` -> a vector of
  `:quote/ingest` request maps. A pool that fails verification, fails to
  decode or disagrees with its own tick contributes nothing (the quorum in
  `marketdata.aggregate` shrinks) rather than contributing a guess."
  [endpoint instrument-id]
  (vec (keep #(try (fetch-pool-quote endpoint %)
                   (catch Exception _ nil))
             (uni/pools-for instrument-id))))
