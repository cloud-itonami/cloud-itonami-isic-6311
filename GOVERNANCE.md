# Governance

`cloud-itonami-isic-6311` is an OSS open-business blueprint. Governance
covers both code and the operator model.

## Maintainers

Maintainers may merge changes that preserve these invariants:

- MarketData-LLM cannot directly ingest, publish or resolve a correction
  request.
- MarketDataGovernor remains independent of the advisor.
- hard governor violations (tolerance-gate, source-provenance-gate,
  licensed-disclosure) cannot be overridden by human approval.
- a correction/dispute request never auto-resolves, at any rollout phase.
- an ingest or disclosure targeting a halted/circuit-broken instrument
  always reaches a human, regardless of confidence.
- every commit, hold and disclosure event is auditable.
- no schema field exists for order-routing, custody or trade execution —
  scope is structural, not a runtime filter someone could forget to call.
- real feed-license credentials and real subscriber contract documents
  stay outside Git.

## Decision Records

Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, disclosure scope, public business model, operator
certification or license should add or update an ADR.

## Operator Governance

Anyone may fork and operate independently. itonami.cloud certification is a
separate trust mark and should require security, audit, support and
data-flow review.

Certified operators can lose certification for:

- bypassing governor checks
- disclosing data to an uncontracted party
- ingesting an unsourced or unlicensed print
- publishing a price for a halted/circuit-broken instrument without human
  review
- misrepresenting certification status
- failing to respond to security incidents or data-quality disputes
- hiding material changes to customer-facing operation
