# Operator Guide

## First Deployment

1. Register the operator's license, jurisdiction and responsible principals.
2. Import historical accounts/positions and counterparties.
3. Run read-only validation of existing records against this blueprint's
   contracts.
4. Configure the FundManagementGovernor's hold/escalation policy.
5. If deploying alongside `cloud-itonami-isic-6499` (the investment
   actor), agree the transport for upstream fee-accrual reports (a
   message queue, signed webhook, or shared `kotoba-server` pod) -- see
   `docs/adr/0001-architecture.md` for the data contract.
6. Publish a dry-run operation and audit export.

## Minimum Production Controls

- spec-basis citation required before any customer-facing determination
- executing a rebalancing trade or drawing a management fee always requires a human sign-off
- audit export for every hold, approval and disbursement
- backup manual process for governor/system outage

## Certification

Certified operators must prove case/account-record integrity, governor
independence, evidence-backed reporting and human review for every
high-stakes action.
