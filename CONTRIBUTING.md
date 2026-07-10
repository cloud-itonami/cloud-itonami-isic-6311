# Contributing

`cloud-itonami-isic-6311` accepts contributions to the OSS actor, governor
tests, documentation, examples and open business blueprint.

## Development

```bash
clojure -M:dev:test
clojure -M:lint
```

Keep changes small and include tests for governor, audit, store or
disclosure behavior.

## Rules

- Do not commit real instrument prints, real license credentials, or
  customer contract documents.
- Keep production ingestion and disclosures behind MarketDataGovernor.
- Treat every new asset class or feed integration as high-risk: add tests
  for tolerance-gate, source-provenance-gate, licensed-disclosure,
  confidence floor and audit logging.
- Never fabricate a source-catalog entry to expand apparent free-source
  coverage — a new feed vendor is a `feed-license` record with a real
  operator license, not a catalog entry.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests

PRs should describe:

- what behavior changed
- which governor invariant is affected
- how it was tested
- whether operator or certification docs need updates
