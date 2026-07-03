# Business Model: Fund management activities

## Classification

- Repository: `cloud-itonami-6630`
- ISIC Rev.5: `6630`
- Activity: fund management activities -- managing collective investment vehicles (mutual funds, pension-fund assets, etc.) on behalf of unitholders
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- independent asset managers
- cooperative investment pools
- community fund-management operators

## Offer

- mandate intake
- investment-guideline disclosure proposal
- rebalancing/trade proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per fund AUM tier
- support: monthly retainer with SLA
- migration: import from an incumbent asset-management system
- management-fee processing

## Trust Controls

- no rebalancing trade is executed and no fee is drawn without human sign-off
- a guideline breach forces a hold, not an override
- every trade/fee path is auditable
- emergency manual override paths remain outside LLM control
