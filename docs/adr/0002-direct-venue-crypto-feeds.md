# ADR-0002: crypto prices are read from the venues directly, never from an aggregator

**Status**: accepted
**Date**: 2026-07-26
**Superproject record**: ADR-2607262100 in `com-junkawasaki/root`
**Supersedes in part**: ADR-0001's treatment of crypto as a
`:licensed-operator-feed`-only asset class

## Context

ADR-0001 shipped this actor with five asset classes but only three real
sources (ECB FX, US EIA, FRED). Equities, crypto and most commodities were
left to `:licensed-operator-feed` — the structural class meaning "the
operator brings their own licensed vendor feed". For equities that is
simply true: exchange print data is licensed, and this repository cannot
give an operator a free live equity feed.

For **crypto it is not true**, and leaving it that way was a real gap:

- Every major crypto venue publishes its own live book on a **keyless,
  documented, public REST endpoint**. There is no license to buy to read
  Binance's own ticker for Binance's own trades.
- Every AMM DEX publishes its price as **public chain state**. A Uniswap v3
  pool's `slot0()` is readable with one `eth_call` against any Ethereum
  node.

So the alternative to "operator brings a licensed feed" is not "buy an
aggregator's API" — it is **read the venues**. The owner's instruction was
explicit: build the collection, and do not depend on CoinMarketCap or
similar; go to Uniswap and to each exchange.

That framing matters beyond convenience. An aggregator is an extra trusted
party between the print and the subscriber: their blend methodology, their
venue weighting, their outage, their terms. This actor's whole premise is
that a price must resolve to a citable source a subscriber can re-check —
and "CoinMarketCap said so" is not re-checkable, it is a second-hand
assertion.

## Decision

### 1. Two new provenance classes, and deliberately no aggregator class

`marketdata.facts/catalog` gains:

- `:exchange-first-party-public-api` — the venue's own public market-data
  endpoint (Binance, Coinbase Exchange, Kraken, bitFlyer; each catalog
  entry cites that venue's published API documentation).
- `:dex-onchain-observation` — a DEX pool contract's own state, read
  on-chain.

Neither requires a `feed-license`: they are the venue publishing its own
book and the chain publishing its own state. **No `:price-aggregator`
class was added, and none should be.** Because `allowed-source-classes` is
a closed set derived from the catalog, an aggregator-derived price has
nothing truthful to cite and is structurally unpublishable here — this is
enforced by a test (`an-aggregator-sourced-price-has-no-class-to-cite`),
not by convention.

### 2. `marketdata.venues` — CEX registry + pure shaping

Venue metadata, listings (venue × instrument × the venue's real quote
currency), URL builders and the `:quote/ingest` shaper, all pure `.cljc`.
The parsers live in `marketdata.feed` (JVM-only, like every other parser
here) and each returns `{:price :as-of :as-of-source :epoch-seconds :bid
:ask :volume}` or **nil** — never a zero or placeholder price.

Three venue-specific honesty decisions:

- **Kraken's payload carries no timestamp at all.** Rather than stamping
  wall-clock and calling it the venue's time, the parser leaves `:as-of`
  nil and the fetch layer fills in its own observation time tagged
  `:as-of-source :observed`, which survives into the published provenance.
- **Kraken renames pairs** (`XBTUSD` → `XXBTZUSD`). Instead of a
  translation table written from memory, the parser reads the single entry
  of a single-pair request and returns nil for anything else.
- **bitFlyer publishes a stale `ltp` while `state` is not `RUNNING`.**
  Ingesting that is exactly the stale-feed failure this actor exists to
  prevent, so a non-RUNNING state yields nil.

### 3. `marketdata.uniswap` — pure on-chain price math

Calldata selectors, ABI word decoding (including `int24` sign extension),
the sqrtPriceX96 → price formula with decimal adjustment and inversion, and
a pool registry whose every field (`token0`/`token1`/`decimals`) was read
from mainnet during development and is **re-verified live** by
`marketdata.feed-eth/verify-pool!`.

Precision is plain float64, the same portable-arithmetic choice
`marketdata.policy` and `marketdata.feed/cross-rate` already make — the
~1e-16 relative error is twelve orders of magnitude below the 15%
tolerance gate, and the whole valid sqrtPriceX96 range stays inside
float64's exponent range.

**`check-slot0` re-derives the price a second way** from `slot0()`'s `tick`
word (raw price = 1.0001^tick) and refuses the quote if the two
derivations disagree by more than 0.5%. This is the dual-decoder discipline
`cloud-itonami-isic-6611-cryptoexchange` uses for WYSIWYS signing, applied
here because a word-offset or inversion bug produces a number that is
wrong by orders of magnitude yet perfectly well-formed — and on a freshly
seeded instrument with no prior quote, the tolerance-gate has nothing to
compare it against.

### 4. `marketdata.feed-eth` — the RPC transport, isolated

JSON-RPC lives in its own `.clj` namespace depending on
`kotoba-lang/org-ethereum-jsonrpc` (whose `eth-method-whitelist` enforces
read-only in code), declared in the **`:feed` alias only**. Nothing under
`test/` requires it, so `clojure -M:dev:test` — what CI runs, checking out
only langgraph/langchain — stays offline and dependency-clean. The RPC
endpoint is always the caller's (`ETH_RPC_URL` read by `feed-demo`), never
a hardcoded provider.

### 5. `marketdata.aggregate` — a median that fails closed

What this actor publishes for a crypto instrument is one cross-venue
median, subject to:

- **quorum** (`:min-venues`, default 3) — below it, nothing is published;
- **dispersion** (`:max-dispersion`, default 2%) — beyond it, nothing is
  published. Silent outlier-dropping is opt-in (`:drop-outliers? true`) and
  every dropped venue is recorded in the result and in the published
  provenance;
- **no invented currency conversion** — a quote is converted only under an
  explicitly declared `:currency-equivalence` (the stablecoin-peg
  assumption, recorded per-constituent as `:conversion`) or a real supplied
  `:fx-rates` entry; otherwise it is excluded with `:no-conversion-path`;
- **median, not volume-weighted** — self-reported volume is the most
  manipulated field in crypto market data.

Median rather than mean, and refusal rather than outlier-dropping, are the
two places where this deliberately diverges from how a commercial
aggregator behaves.

### 6. The governor re-checks the composite

A `:cross-venue-composite` citation enumerates every constituent, and
`marketdata.policy`'s source-provenance-gate independently validates that
enumeration as a HARD gate: quorum met, every constituent a direct-venue
class (no licensed vendor tick, no nested composite), every `:ref`
non-blank and distinct. Without this, "it was aggregated" would be a
laundering hole for any number at all.

### 7. Real instruments, no seeded prices

`cx-btc-usd` and `cx-eth-usd` are seeded as instrument metadata only. This
repository ships no BTC or ETH price of its own — the first real price for
them comes from a live venue read.

## Consequences

- (+) Crypto is genuinely collected now, from 5 independent venues, with
  zero aggregator dependency and zero API keys. Verified live end-to-end on
  2026-07-26: 4 CEX + 2 Uniswap pools read, medians published through the
  governor (BTC 64444.31 USD, n=4, dispersion 11bp; ETH 1886.015 USD, n=4,
  8bp), with the JPY venue correctly excluded for having no declared
  conversion.
- (+) `clojure -M:dev:test`: 95 tests / 421 assertions, 0 failures (was 46
  / 177). `clojure -M:lint`: 0 errors, 0 warnings.
- (+) Every published crypto price is re-derivable by the subscriber from
  its `:ref` alone.
- (−) **Redistribution terms are the operator's problem, not this
  repository's.** Reading a venue's public endpoint is not the same as
  being licensed to redistribute it commercially; each venue's terms differ
  and change. `docs/operator-guide.md` §7 lists this as a per-venue
  checklist item before selling access.
- (−) The stablecoin-peg equivalence is a real assumption. It is explicit,
  operator-declared and recorded per-constituent — but a depegged USDT
  would still pull the median if declared equivalent. Mitigation available
  today: supply a real `:fx-rates` entry instead of an equivalence.
- (−) Staleness is only checked when the caller supplies
  `:now-epoch-seconds`, and Kraken supplies no venue clock to check.
- (−) Two Uniswap pools on one chain (Ethereum mainnet). No L2, no other
  DEX. Adding one is a registry entry plus a live `verify-pool!` run.
