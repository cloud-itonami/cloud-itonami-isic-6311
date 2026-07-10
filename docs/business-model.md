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
