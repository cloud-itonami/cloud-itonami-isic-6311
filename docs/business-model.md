# Open Business Blueprint: cloud-itonami-isic-6311

This repository publishes an OSS business model for operating a
multi-asset market-data aggregation and hosting service (Refinitiv Eikon /
Bloomberg Terminal-feed / ICE Data Services class) on itonami.cloud, with a
collect-hold-serve operating model: hold curated price data internally,
disclose it only to licensed, contracted subscribers.

## Classification

- Repository name: `cloud-itonami-isic-6311`
- Primary classification: ISIC Rev.4 6311 (Data processing, hosting and
  related activities), narrowed to market-price data specifically
- Activity: collecting, holding, updating and disclosing price/quote data
  across equities, FX, commodities, crypto and real-estate indices
- Served domain: instrument reference data, latest quote, derived series
  (OHLC), scoped to price data only — never order-routing, custody or
  trade execution

The ISIC code describes the business activity of processing and hosting
data for distribution. The market-data actor is the first productized
service inside that classification in this fleet, and is the fifth
`:spec → real repo` promotion in `kotoba-lang/industry`'s registry (after
`cloud-itonami-M6910`'s 6910, `cloud-itonami-isic-8291`'s 8291,
`cloud-itonami-isic-4690`'s 4690, and `cloud-itonami-isic-4610`'s 4610).

## Customer

Primary customers (contracted, licensed access only — never public/
anonymous):

- trading desks and portfolio-valuation teams needing a governed,
  audit-ready price source
- fintech/retail-app builders needing a licensed quote feed without
  building ingestion/governance themselves
- other `cloud-itonami-{ISIC}` blueprint operators who need price data as
  a licensed capability (a `:market-data` wholesale pattern, the same
  shape as `cloud-itonami-isic-8291`'s `:corporate-intelligence`
  wholesale pattern) — e.g. `kotoba-lang/securities` settlement flows
  that need a reference price
- researchers/analysts needing sourced, provenance-tagged historical
  series

## Problem

Market-data vendors (Bloomberg, Refinitiv, ICE) hold this data inside
closed systems and charge continuously for access. Customers cannot inspect
the governance logic (why was this print accepted, what source backs this
quote, why did a halt suppress publication), and vendors have no structural
guarantee against an unsourced or out-of-tolerance print reaching
subscribers.

## Offer

Operators provide an OSS actor for market-data aggregation and hosting:

- instrument reference data (symbol, asset class, venue, trading status)
- latest quote per instrument, source-cited
- derived series (daily OHLC bars)
- governed, tier-scoped disclosure (never a public/anonymous query surface)
- a data-quality correction/dispute channel, always human-reviewed
- immutable audit ledger of every ingest/disclosure event
- structural halt-awareness: no fresh print or disclosure for a
  halted/circuit-broken instrument without human review

The core promise: MarketData-LLM can draft print normalization and series
derivation, but it cannot ingest, publish, or resolve a dispute unless the
independent MarketDataGovernor allows it.

## Revenue

Operators can sell:

- per-seat or per-query licensed access (contract tenant × tier)
- tiered subscriptions: `:tier/basic` (latest price) → `:tier/pro`
  (+ derived series) → `:tier/institutional` (+ raw provenance detail)
- wholesale API access to other `cloud-itonami-{ISIC}` blueprint operators
  (the `:market-data` capability pattern)
- managed hosting: monthly subscription per tenant
- feed-license integration: onboarding a real exchange/broker/vendor feed
- compliance package: audit export, dispute-handling SLA, security review

| Package | Customer | Price shape |
|---|---|---|
| Basic quote feed | small fintech/retail app | per-query or low monthly tier |
| Pro tier | trading desk / valuation team | monthly platform fee |
| Institutional tier | quant/analytics team | monthly fee + usage |
| Fleet wholesale | other cloud-itonami operators | API metering |
| Managed Starter | one tenant, small fintech/retail-app builder (3–5 seats) | ¥25,000/月 flat |

**Market-anchored (2026-08-10)**: benchmarked against 6 real competitor
products in the market-data aggregation/hosting category. **Only 4 of the 6
publish real numbers.** The self-serve API vendors do:
**Massive (formerly Polygon.io)** — Stocks Starter `$29/month`, Developer
`$79/month`, Advanced `$199/month`
(<https://massive.com/pricing>); **Twelve Data** — Grow `$79`, Pro `$229`,
Ultra `$999` per month (<https://twelvedata.com/pricing>); **EODHD APIs** —
`EOD Historical Data — All World $19.99/mo.`, `EOD+Intraday $29.99/mo.`,
`ALL-IN-ONE Package $99.99/mo.` (<https://eodhd.com/pricing>); and
**J-Quants API** from the Japan Exchange Group — Light ¥1,650/月, Standard
¥3,300/月, Premium ¥16,500/月 (<https://jpx-jquants.com/>). The two
institutional vendors this repo names as its own comparator class —
**Bloomberg Terminal** and **LSEG Workspace (formerly Refinitiv Eikon) /
ICE Data Services** — **publish nothing**; neither has a price page, and
both route to a sales quote whose value depends on per-entitlement data
licensing. Third-party aggregators report a Bloomberg terminal at
`$31,980 per year` and LSEG Workspace at roughly `$22,000 per user per
year`, but those are **not first-party figures and are therefore not used
as an anchor here** — they are recorded only to show that the undisclosed
band is an order of magnitude away, not to price against.

Converting at ~¥150/$ for the assumed customer (one tenant, a small
fintech/retail-app builder at 3–5 seats and moderate query volume), the
**published** band is ¥3,300/月 (J-Quants Standard) to ¥34,350/月
(Twelve Data Pro), with Massive Developer at ¥11,850/月, EODHD ALL-IN-ONE
at ¥15,000/月 and Massive Advanced at ¥29,850/月 in between. **¥25,000/月
sits in the upper-middle of that measured band, and deliberately not at the
top.** It is not at the top because this actor **does not include the
equity/commodity data licence** — for those asset classes the operator
brings their own licensed feed, so relative to Massive/Twelve Data/EODHD
this is a complement, not a substitute. It is not at the bottom because,
unlike a self-serve API key, a managed tenant here carries per-tenant human
work: tolerance and provenance checking, halt-awareness (no fresh print or
disclosure for a halted/circuit-broken instrument without human review),
contract-tier-scoped disclosure, and a correction/dispute channel with an
SLA. For crypto the picture is different and stronger: this actor reads the
four venues' own first-party public APIs plus Uniswap v3 on-chain state and
publishes a fail-closed cross-venue median with no aggregator in the path,
so within crypto it is a full substitute rather than a complement.

**Subscribe (2026-08-10)**: a live Stripe Payment Link for the Managed
Starter tier (¥25,000/月 flat) is available now —
[**subscribe to Managed Market-Data Ops — Starter**](https://buy.stripe.com/4gMaEY4Un10Y9TyguueEo03).
This is a no-code Stripe-hosted checkout; nothing in this repo's actor code
changed. After subscribing, contact gftdcojp to arrange managed-tenant setup
and to register the licensed feed(s) the tenant will bring (manual
fulfillment today, no automated onboarding yet). **No fintech, desk or fleet
operator has claimed or subscribed to this tier yet — this is a live,
working checkout with zero paid tenants, not a claim of existing revenue.**

## Unit Economics

Track these numbers for every operator:

- feed-license integration hours per new asset class/vendor
- monthly infrastructure cost
- LLM cost per operation (ingest / derive / disclosure)
- correction/dispute handling hours per tenant
- gross margin after infrastructure and support
- churn and expansion revenue per contract tier

The business should only scale after the source catalog and every active
feed-license are genuinely real (never fabricated) and governor tests catch
tolerance/provenance/licensing misconfiguration before production use.

## Open Participation

Anyone may:

- fork the repository
- run the demo
- deploy a self-hosted instance
- submit issues and patches
- publish compatible source-catalog extensions (real, citable sources only)
- create a local operator business

itonami.cloud should require certification before listing an operator as a
trusted provider, routing customer leads, or allowing managed disclosure
under the platform brand.

## Operator Trust Levels

| Level | Capability |
|---|---|
| Contributor | patches, docs, issues, examples |
| Self-host operator | runs their own instance with no platform endorsement |
| Certified operator | listed on itonami.cloud after review |
| Managed operator | may receive leads and operate customer tenants |
| Core maintainer | can approve changes to governor, security and governance |

## Marketplace Metadata

Suggested itonami.cloud metadata:

```edn
{:itonami.blueprint/id "cloud-itonami-isic-6311"
 :itonami.blueprint/name "Multi-Asset Market-Data Aggregation & Hosting Actor"
 :itonami.blueprint/isic-rev4 "6311"
 :itonami.blueprint/domain :finance/market-data
 :itonami.blueprint/license "AGPL-3.0-or-later"
 :itonami.blueprint/operator-model :certified-open-business
 :itonami.blueprint/repo "https://github.com/cloud-itonami/cloud-itonami-isic-6311"
 :itonami.blueprint/status :public-oss
 :itonami.blueprint/required-technologies [:identity :forms :audit-ledger :securities]
 :itonami.blueprint/optional-technologies [:dmn :bpmn]}
```

## Non-Negotiables

- Do not commit real instrument prints or real feed-license credentials.
- Do not add a schema field for order-routing, custody or trade execution.
- Do not bypass the MarketDataGovernor for production ingestion or
  disclosures.
- Do not serve a disclosure to a tenant without an active, registered
  contract.
- Do not fabricate a source-catalog entry or a feed-license record to
  expand apparent coverage.
- Do not market an uncertified deployment as an itonami.cloud certified
  operator.
