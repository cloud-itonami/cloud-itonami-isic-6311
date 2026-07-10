# Operator Guide

This guide is for people who want to start an open business from
`cloud-itonami-isic-6311`.

## 1. Fork and Run

```bash
git clone https://github.com/cloud-itonami/cloud-itonami-isic-6311
cd cloud-itonami-isic-6311
clojure -M:dev:test
clojure -M:dev:run
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
- wire the 3 free/official sources for real via `src/marketdata/feed.cljc`
  (ECB FX needs no key; EIA/FRED need a free registered API key). Run
  `clojure -M:feed:dev:run-feed` (with `EIA_API_KEY`/`FRED_API_KEY` set) as
  a live smoke test — it pushes each fetched quote through the real
  `OperationActor`, so a malformed/stale live response still gets caught
  by the same tolerance-gate/source-provenance-gate as any other request.
  For equities/crypto/most commodities, no free feed exists — you must
  register a `feed-license` and point `marketdata.feed`'s pattern (or your
  own connector) at your licensed vendor
- configure Datomic Local, kotoba-server or an equivalent durable SSoT
- configure the LLM adapter through environment variables or secret manager
- define subscriber contract tenants/tiers and RBAC rules
- run `clojure -M:dev:test`
- run `clojure -M:lint`
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
