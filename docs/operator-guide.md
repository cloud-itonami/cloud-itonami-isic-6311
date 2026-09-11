# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-6311`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-6311
cd cloud-itonami-isic-6311
kbb -M:dev:test
kbb -M:dev:run
```

The default demo uses entirely fictitious instruments and prices.
Production quotes must stay outside the repository and be injected through
a store adapter, and every price must carry a real, verifiable source
citation (a real catalog source or a real, active feed-license).

## 2. Choose an Operating Mode

| Mode | Use when |
|---|---|
| Demo | validating the actor and governor contract |
| Self-host | one organization owns infrastructure and data |
| Managed tenant | an operator hosts for a customer |
| Certified operator | itonami.cloud has reviewed security and process controls |

## 3. Production Checklist

- replace demo instruments/quotes with real, source-cited feeds (extend
  `marketdata.facts/catalog` honestly for free/official sources — never
  fabricate one — and register real `feed-license` records for licensed
  exchange/broker/vendor feeds)
- wire the 3 free/official sources for real via `src/marketdata/feed.cljk`
  (ECB FX needs no key; EIA/FRED need a free registered API key). Run
  `kbb -M:feed:dev:run-feed` (with `EIA_API_KEY`/`FRED_API_KEY` set) as
  a live smoke test — it pushes each fetched quote through the real
  `OperationActor`, so a malformed/stale live response still gets caught
  by the same tolerance-gate/source-provenance-gate as any other request.
  For equities/crypto/most commodities, no free feed exists — you must
  register a `feed-license` and point `marketdata.feed`'s pattern (or your
  own connector) at your licensed vendor
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- define subscriber contract tenants/tiers and RBAC rules
- run `kbb -M:dev:test`
- run `kbb -M:lint`
- verify audit-ledger export
- document backup and restore
- document incident response
- document the data-quality correction/dispute-handling SLA
- get written legal review for the jurisdictions and asset classes you
  serve (exchange market-data redistribution licensing varies by venue and
  jurisdiction)

## 4. Sales Motion

Start with a narrow offer:

1. onboard one real, citable feed (e.g. ECB FX reference rates, or one
   licensed exchange feed with a real feed-license)
2. prove governed, tier-scoped disclosure end to end
3. run one derived-series workflow in assisted mode (human-approved)
4. export the audit ledger for review
5. convert to a metered or subscription contract

Avoid selling broad "全アセットクラスのリアルタイムレート" before the
source/feed-license catalog actually covers the asset classes a customer
needs — report coverage honestly (`marketdata.facts/coverage`), never
oversell.

## 5. Certification Requirements

itonami.cloud certification should require:

- passing tests and lint on the published version
- written data-flow diagram (feed → governor → disclosure)
- backup/restore evidence
- incident contact and response window
- proof that production ingestion/disclosures go through
  MarketDataGovernor
- proof that real feed-license credentials are not stored in Git
- proof that a data-quality correction/dispute channel exists and is
  human-reviewed
- customer-facing support and licensing terms

## 6. Operator Responsibilities

Operators are responsible for:

- lawful basis and redistribution licensing for each feed and asset class
  served
- local market-data-vendor licensing and exchange redistribution-agreement
  compliance
- secure infrastructure and tenant isolation
- honest source-catalog and feed-license maintenance
- human review workflow for halted-instrument and correction-request
  operations
- data-retention policy
- security updates

The OSS project provides software and an operating blueprint. It does not
make an operator compliant by itself, and it does not license or endorse
redistribution of any specific exchange's or vendor's data.

## 7. Running the direct crypto venues

Crypto is the one asset class this actor collects itself rather than
leaving to your licensed vendor feed: it reads four exchanges' own public
endpoints and Uniswap v3 on-chain, then publishes a fail-closed cross-venue
median (ADR-0002).

```bash
# CEX legs only — no API key, no account, no aggregator
kbb -M:feed:dev:run-feed

# with the on-chain Uniswap leg (your node or provider URL — this repo
# hardcodes none, and reads no credential from env inside the connector)
ETH_RPC_URL=https://<your-ethereum-node> kbb -M:feed:dev:run-feed
```

### What you must decide before selling access to it

1. **Redistribution terms, per venue.** Reading a venue's public endpoint
   is not automatically a right to redistribute it commercially, and the
   four venues' terms differ from each other and change over time. Check
   each venue's current terms of use for the endpoints in
   `marketdata.venues/venues` before including that venue in a paid tier.
   On-chain state (the Uniswap leg) does not carry this problem — it is
   public chain data, not a venue's proprietary feed.
2. **The stablecoin peg.** `:currency-equivalence {:usdt :usd :usdc :usd}`
   is a real assumption you are making on your subscribers' behalf: it says
   a USDT print may be published as a dollar price. It is recorded in every
   constituent's `:conversion`, so it is auditable — but during a depeg it
   is also wrong. If you would rather not assume it, supply real
   `:fx-rates` entries instead and the un-convertible venues will simply be
   excluded.
3. **Quorum and dispersion.** Defaults are 3 venues and 2%. Raising quorum
   or tightening dispersion makes the actor publish nothing more often —
   which is the intended failure mode, not an outage. Do not "fix" a
   dispersion refusal by enabling `:drop-outliers?` without looking at
   which venue is the outlier and why.
4. **Rate limits.** Each venue publishes its own; the collector makes one
   request per listing per round. Poll frequency is yours to choose, and
   exceeding a venue's limit degrades your quorum rather than erroring
   loudly.
5. **Staleness.** Pass `:now-epoch-seconds` to
   `marketdata.aggregate/reference-price` to enable the freshness check.
   Kraken's ticker payload has no venue clock, so its constituent is
   `:freshness :unknown` — it is stamped with this actor's observation time
   and labelled `:as-of-source :observed`, never presented as
   venue-authoritative.

### Adding a venue or a pool

- **CEX**: add a `marketdata.venues/venues` entry (with its published API
  doc URL), a `marketdata.venues/listings` row with the venue's REAL quote
  currency, a parser in `marketdata.feed`, and a catalog entry in
  `marketdata.facts`. A venue in the registry with no parser is caught by a
  test.
- **DEX pool**: add a `marketdata.uniswap/pools` entry and then actually
  run `marketdata.feed-eth/verify-pool!` against a node — it reads
  `token0()`/`token1()`/`decimals()` from the chain and tells you whether
  your entry is right. Do not commit a pool entry you have not verified;
  the wrong `decimals` produces a well-formed price that is wrong by a
  factor of 10^n.
- **Never** add a source class for an aggregator (CoinMarketCap, CoinGecko,
  CryptoCompare, a vendor blend). The closed catalog is what makes this
  actor's crypto provenance re-derivable, and a test asserts no such class
  exists.
