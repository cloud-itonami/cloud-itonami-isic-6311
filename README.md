# cloud-itonami-isic-6311

Open Business Blueprint for **ISIC Rev.4 6311**: data processing, hosting
and related activities, narrowed to a **multi-asset market-data
aggregation and hosting** service — the Refinitiv Eikon / Bloomberg
Terminal-feed / ICE Data Services class of business — published as an OSS
business that any qualified operator can fork, deploy, run, improve and
sell.

Collects (ingests), holds and updates price/quote data across five asset
classes — equities, FX, commodities, crypto and real-estate indices — and
serves it to licensed subscribers under a tiered contract, exactly the
"operator supplies their own licensed price feed" boundary this workspace's
[`kotoba-lang/securities`](https://github.com/kotoba-lang/securities) already
documents (`securities` is the settlement/custody layer; this actor is the
market-data layer that would feed it). Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop, interrupts,
Datomic/in-mem checkpoints) — the same actor pattern as
[`cloud-itonami-isic-8291`](https://github.com/cloud-itonami/cloud-itonami-isic-8291)
and [`cloud-itonami-6310`](https://github.com/gftdcojp/cloud-itonami-6310).

> **Why an actor layer at all?** A MarketData-LLM is great at normalizing
> feed ticks, drafting derived series (OHLC bars), and proposing subscriber
> column sets — but it has **no notion of print tolerance, feed-license
> entitlement, trading-halt state, or a subscriber's disclosure tier**.
> Letting it write or publish directly invites a fat-fingered/duplicate
> print reaching subscribers unchecked, an unlicensed feed masquerading as
> provenance, over-disclosure beyond a contract's tier, or a fresh print
> auto-publishing during a circuit-breaker halt. This project seals the
> MarketData-LLM into a single node and wraps it with an independent
> **MarketDataGovernor**, a human **review workflow**, and an immutable
> **audit ledger**.

## Scope (deliberately narrow — read this before anything else)

This actor **collects, holds and serves prices**. It never routes an order,
never holds custody, never executes a trade — there is no field anywhere in
this schema for order-routing, custody or trade execution (see
`docs/adr/0001-architecture.md`). Ingested provenance is limited to real,
citable public reference sources (`src/marketdata/facts.cljc`: ECB FX
reference rates, US EIA commodity spot data, FRED real-estate index) or an
operator-registered `:licensed-operator-feed` — every price must resolve to
one or the other, never a bare "the LLM inferred it".

## Consuming this actor from another blueprint

Two governed read ops are the actual product surface: `:disclosure/query`
(an instrument's latest price / OHLC series, columns limited to your
contract tier). It always runs through the MarketDataGovernor's
licensed-disclosure check — there is no bypass.

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full architecture and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
decision record. See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an open
business on itonami.cloud.

## Open business

This repository is not only source code. It is a public, forkable business
model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, MarketDataGovernor, governed disclosure, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, deploy, support and sell the service |
| Trust controls | Governance, security reporting, policy tests, audit requirements |

The primary industry classification is **ISIC Rev.4 6311** because the
commercial activity is processing and hosting third-party data for
distribution — here, market-price data specifically.

## The core contract

```
request + injected role/tenant/phase context
        │
        ▼
   ┌──────────────┐    proposal      ┌──────────────────────┐
   │ MarketData-LLM│ ───────────────▶│ MarketDataGovernor    │  (independent system)
   │ (sealed)      │  draft + source │  tolerance · provenance│
   └──────────────┘   citation       │  · license · human     │
                                      └──────────────────────┘
                                              │
                                   commit / publish only if allowed
                                              ▼
                                    append-only audit ledger
```

**Single invariant**: MarketData-LLM never ingests, publishes, or resolves
a correction the MarketDataGovernor would reject.

## Run

```bash
clojure -M:dev:test   # governor contract · store parity · phases · facts
clojure -M:dev:run    # 6-operation demo through one OperationActor
clojure -M:lint
```

## Real feeds (`marketdata.feed`)

`src/marketdata/feed.cljc` is the "operator wires a real feed" seam this
README gestures at above: live HTTP connectors for the 3 free/official R0
catalog sources — ECB euro FX reference rates (no key), US EIA Open Data,
FRED Case-Shiller HPI (the latter two need a free API key). Every
`fetch-*` fn does the I/O + parse and hands back a request already shaped
for `marketdata.operation/build` — it still goes through the full
MarketDataGovernor (tolerance-gate, source-provenance-gate, ...) exactly
like a hand-built request does. JVM-only (`clojure.xml`/http-kit/jsonista,
same seam as `langchain.jvm`); those deps live only in the `:test`/`:feed`
aliases, never `:deps`.

```bash
clojure -M:feed:dev:run-feed                                   # ECB only (no key)
EIA_API_KEY=... FRED_API_KEY=... clojure -M:feed:dev:run-feed  # all 3
```

Equities/crypto/most commodities still require an operator-registered
`feed-license` — `marketdata.feed` does not (and cannot) supply a free live
feed for those asset classes; see `docs/operator-guide.md`.

## Non-Negotiables

- Do not commit real instrument prints or real feed-license credentials.
- Do not add a schema field for order-routing, custody or trade execution.
- Do not bypass the MarketDataGovernor for production ingestion or
  disclosures.
- Do not serve a disclosure without an active, registered contract.
- Do not fabricate a source-catalog entry or a feed-license record.

License: AGPL-3.0-or-later.
