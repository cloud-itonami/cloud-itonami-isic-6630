# Business Model: Fund management activities

## Classification

- Repository: `cloud-itonami-isic-6630`
- ISIC Rev.5: `6630`
- Activity: fund management activities -- managing collective investment vehicles (mutual funds, pension-fund assets, etc.) on behalf of unitholders
- Social impact: financial inclusion, data sovereignty, transparent audit

## Customer

- independent asset managers
- cooperative investment pools
- community fund-management operators

## Offer

- investment-mandate intake (LPA-authorized management-fee rate ceiling)
- management-fee drawdown proposal off an upstream investment-actor
  (`cloud-itonami-isic-6499`) fee-accrual report, independently
  re-verified against this company's own recorded mandate and
  double-draw-protected per billing period
- investment-guideline disclosure proposal beyond the fee-rate cap (blueprint-stage; not yet a governed op)
- carry (GP profit-share) distribution proposal (blueprint-stage; not yet a governed op)
- rebalancing/trade proposal (not applicable to a VC fund; retained here
  only because this ISIC class also covers mutual-fund/pension-asset
  management, where it is relevant)
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per fund AUM tier
- support: monthly retainer with SLA
- migration: import from an incumbent asset-management system
- management-fee processing

## Trust Controls

- no management fee is drawn without human (GP principal) sign-off
- a fee draw with no mandate on file, a claimed rate exceeding the
  recorded mandate cap, a claimed accrual not matching this company's own
  independent recomputation, or a double-draw of an already-drawn period
  forces a hold, not an override
- every mandate/drawdown path is auditable
- emergency manual override paths remain outside LLM control
