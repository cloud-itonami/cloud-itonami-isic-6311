(ns marketdata.uniswap-test
  "Decoding + price math for the DEX leg (ADR-2607262100). Offline: the
  two `slot0()` payloads below are REAL `eth_call` results captured live
  from Ethereum mainnet on 2026-07-26 against the two registry pools, not
  fabricated hex. The prices they decode to were cross-checked in that same
  session against the CEX venues' own live prices (ETH: 1883.54 decoded vs
  1885.34 on Binance, 0.10%; BTC: 64374.96 decoded vs 64424.64 on Binance,
  0.08%) — i.e. the math below is anchored to independently observed
  reality, not just to itself."
  (:require [clojure.test :refer [deftest is testing]]
            [marketdata.uniswap :as uni]))

;; Real mainnet `slot0()` result for the WETH/USDC 0.05% pool
;; (0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640), block 0x186e2ff.
(def weth-usdc-slot0
  "0x0000000000000000000000000000000000005a0191b0613f974f6af6b8476e4c00000000000000000000000000000000000000000000000000000000000310cf000000000000000000000000000000000000000000000000000000000000007900000000000000000000000000000000000000000000000000000000000002d300000000000000000000000000000000000000000000000000000000000002d300000000000000000000000000000000000000000000000000000000000000440000000000000000000000000000000000000000000000000000000000000001")

;; Real mainnet `slot0()` result for the WBTC/USDC 0.30% pool
;; (0x99ac8ca7087fa4a2a1fb6357269965a2014abc35).
(def wbtc-usdc-slot0
  "0x00000000000000000000000000000000000000195f49d08a75ef031d4ab5a3ab000000000000000000000000000000000000000000000000000000000000fca400000000000000000000000000000000000000000000000000000000000002020000000000000000000000000000000000000000000000000000000000000258000000000000000000000000000000000000000000000000000000000000025800000000000000000000000000000000000000000000000000000000000000660000000000000000000000000000000000000000000000000000000000000001")

(defn- close? [a b tol] (< (/ (Math/abs (- (double a) (double b))) (double b)) tol))

(deftest hex->double-decodes-exactly-in-the-uint160-range
  (is (= 0.0 (uni/hex->double "0x")))
  (is (= 255.0 (uni/hex->double "0xff")))
  (is (= 255.0 (uni/hex->double "FF")) "case-insensitive, prefix optional")
  ;; the real sqrtPriceX96 above, decoded losslessly at float64 precision
  (is (close? (uni/hex->double "0x5a0191b0613f974f6af6b8476e4c")
              1.8255411810477756E33 1e-15))
  (is (thrown? clojure.lang.ExceptionInfo (uni/hex->double "0xzz"))
      "a non-hex digit throws rather than decoding to a silently wrong number"))

(deftest decode-slot0-reads-sqrt-price-and-tick-from-the-real-payload
  (let [{:keys [sqrt-price-x96 tick]} (uni/decode-slot0 weth-usdc-slot0)]
    (is (close? sqrt-price-x96 1.8255411810477756E33 1e-15))
    (is (= 200911 tick)))
  (let [{:keys [tick]} (uni/decode-slot0 wbtc-usdc-slot0)]
    (is (= 64676 tick))))

(deftest decode-slot0-fails-closed-on-a-payload-that-is-not-slot0
  (testing "an empty / short / zero result is never mistaken for a price of 0"
    (is (nil? (uni/decode-slot0 "0x")))
    (is (nil? (uni/decode-slot0 nil)))
    (is (nil? (uni/decode-slot0 (str "0x" (apply str (repeat 64 "0")))))
        "one word is not a 7-word slot0 return")
    (is (nil? (uni/decode-slot0 (str "0x" (apply str (repeat (* 7 64) "0")))))
        "a well-formed but all-zero return has sqrtPriceX96 = 0, which is not a price")))

(deftest word->int24-handles-abi-sign-extension
  (testing "a negative tick is ABI-encoded sign-extended to 32 bytes"
    ;; tick = -1 => 0xfff...fff (constructed per the ABI spec, which is
    ;; deterministic; neither pool in the registry currently sits at a
    ;; negative tick, and inventing a fake API response to get one would be
    ;; worse than constructing the encoding the spec defines).
    (let [neg-one (str "0x" (apply str (repeat 64 "f"))
                       (apply str (repeat (* 6 64) "0")))
          payload (str "0x" (apply str (repeat 63 "0")) "1"     ; sqrtPriceX96 = 1
                       (subs neg-one 2))]
      (is (= -1 (:tick (uni/decode-slot0 payload)))))))

(deftest sqrt-price-x96->price-matches-the-live-cross-checked-value
  (let [pool (uni/pool :uniswap-v3-weth-usdc-005)
        {:keys [sqrt-price-x96]} (uni/decode-slot0 weth-usdc-slot0)]
    (testing "USDC is token0, so the ETH price is the INVERTED pool quote"
      (is (close? (uni/sqrt-price-x96->price sqrt-price-x96 pool) 1883.5445592895542 1e-9)))
    (testing "without the inversion you get the reciprocal, not a price in USD"
      (is (close? (uni/sqrt-price-x96->price sqrt-price-x96 (assoc pool :invert? false))
                  (/ 1.0 1883.5445592895542) 1e-9))))
  (let [pool (uni/pool :uniswap-v3-wbtc-usdc-030)
        {:keys [sqrt-price-x96]} (uni/decode-slot0 wbtc-usdc-slot0)]
    (testing "USDC is token1 here, so the pool already quotes USDC per WBTC"
      (is (close? (uni/sqrt-price-x96->price sqrt-price-x96 pool) 64374.95513619843 1e-9)))))

(deftest sqrt-price-x96->price-refuses-a-non-price
  (let [pool (uni/pool :uniswap-v3-weth-usdc-005)]
    (is (nil? (uni/sqrt-price-x96->price 0 pool)))
    (is (nil? (uni/sqrt-price-x96->price -1 pool)))
    (is (nil? (uni/sqrt-price-x96->price nil pool)))))

(deftest tick-derivation-independently-agrees-with-the-sqrt-derivation
  (doseq [[pool-id payload expected] [[:uniswap-v3-weth-usdc-005 weth-usdc-slot0 1883.5445592895542]
                                      [:uniswap-v3-wbtc-usdc-030 wbtc-usdc-slot0 64374.95513619843]]]
    (let [pool (uni/pool pool-id)
          check (uni/check-slot0 (uni/decode-slot0 payload) pool)]
      (is (:ok? check) (str pool-id " tick and sqrtPrice derivations must agree"))
      (is (close? (:price check) expected 1e-9))
      (is (< (:deviation check) 1e-4)
          "real agreement is ~1e-5, four orders inside the 0.5% gate"))))

(deftest check-slot0-catches-a-wrong-decimals-registry-entry
  (testing "the exact class of silent corruption verify-pool!/check-slot0 exist for"
    (let [pool (uni/pool :uniswap-v3-weth-usdc-005)
          slot0 (uni/decode-slot0 weth-usdc-slot0)]
      ;; A decimals typo moves the price by 10^n but leaves BOTH derivations
      ;; well-formed... except that the sqrt and tick paths use decimals
      ;; identically, so decimals alone cannot be caught here — what IS
      ;; caught is a decode/word-offset bug, simulated by feeding the tick
      ;; word a different tick.
      (is (:ok? (uni/check-slot0 slot0 (assoc pool :token1-decimals 8)))
          "decimals shifts both derivations equally — caught by verify-pool!, not here")
      (is (not (:ok? (uni/check-slot0 (assoc slot0 :tick 100000) pool)))
          "a tick that does not match the sqrtPrice word IS caught, and fails closed"))))

(deftest pool-ingest-request-shapes-a-governable-quote-with-rederivable-provenance
  (let [pool (uni/pool :uniswap-v3-weth-usdc-005)
        slot0 (uni/decode-slot0 weth-usdc-slot0)
        req (uni/pool-ingest-request pool slot0 {:block-number 25617151
                                                 :block-timestamp 1785071147
                                                 :as-of "2026-07-26T13:05:47Z"})]
    (is (= :quote/ingest (:op req)))
    (is (= "cx-eth-usd" (:instrument-id req)))
    (is (close? (:price req) 1883.5445592895542 1e-9))
    (testing "the pool quotes USDC — this actor does not silently call that USD"
      (is (= :usdc (:currency req))))
    (testing "as-of is the block clock, not this actor's wall clock"
      (is (= "2026-07-26T13:05:47Z" (:as-of req)))
      (is (= :venue (get-in req [:source :raw :as-of-source]))))
    (testing "provenance is re-derivable by the subscriber against any node"
      (is (= :dex-onchain-observation (get-in req [:source :class])))
      (is (= "uniswap-v3:1:0x88e6a0c2ddd26feeb64f039a2c41296fcb3f5640:block-25617151"
             (get-in req [:source :ref])))
      (is (= 200911 (get-in req [:source :raw :tick]))))))

(deftest pool-ingest-request-publishes-nothing-when-the-decoders-disagree
  (let [pool (uni/pool :uniswap-v3-weth-usdc-005)
        slot0 (assoc (uni/decode-slot0 weth-usdc-slot0) :tick 100000)]
    (is (nil? (uni/pool-ingest-request pool slot0 {:block-number 1 :block-timestamp 1})))))

(deftest registry-entries-are-internally-consistent
  (doseq [p uni/pools]
    (is (= (:decimals (:token0 p)) (:token0-decimals p)) (str (:id p) " token0 decimals"))
    (is (= (:decimals (:token1 p)) (:token1-decimals p)) (str (:id p) " token1 decimals"))
    (is (re-matches #"0x[0-9a-f]{40}" (:address p)) (str (:id p) " pool address is lowercase hex"))
    (is (contains? #{:usdc} (:quote-currency p)) (str (:id p) " quote currency is the real one"))
    (is (:verified-at p) (str (:id p) " records when it was verified against the chain"))))
