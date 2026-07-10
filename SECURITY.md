# Security Policy

This project handles market-data provenance, subscriber licensing and
price-quality dispute records. Treat vulnerabilities as potentially high
impact even when the demo data is synthetic — bad price data reaching a
downstream trading or valuation system has direct financial consequences.

## Do Not Disclose Publicly

Report privately before opening public issues for:

- credential or feed-license-key exposure
- MarketDataGovernor bypass (tolerance-gate, source-provenance-gate,
  licensed-disclosure)
- audit-ledger tampering
- over-disclosure beyond a subscriber contract's tier
- tenant isolation failures
- ingestion of an unlicensed/unsourced print through an undocumented path
- publication of a price for a halted/circuit-broken instrument without
  human review

## Reporting

Use GitHub private vulnerability reporting when available for the
repository. If that is unavailable, contact the repository maintainers
through the cloud-itonami organization before publishing details.

Include:

- affected commit or version
- reproduction steps
- expected and actual behavior
- impact on price data, governor enforcement or audit logging
- suggested fix, if known

## Production Guidance

- Store secrets and feed-license keys outside Git.
- Run governor tests before deployment.
- Export and review audit logs regularly.
- Use least privilege for feed operators and service accounts.
- Alert on any tolerance-gate or halted-instrument HOLD spike — it may
  indicate a compromised or malfunctioning upstream feed.
