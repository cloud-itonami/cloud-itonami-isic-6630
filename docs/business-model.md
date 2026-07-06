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

- investment-mandate intake (LPA-authorized management-fee rate ceiling,
  OPTIONALLY a separate carry-rate ceiling)
- management-fee drawdown proposal off an upstream investment-actor
  (`cloud-itonami-isic-6499`) fee-accrual report, independently
  re-verified against this company's own recorded mandate and
  double-draw-protected per billing period
- GP carry (profit-share) distribution proposal off an upstream
  investment-actor exit-distribution waterfall, independently
  re-verified against this company's own recorded carry-rate ceiling and
  double-distribution-protected per commitment
- investment-guideline (sector/stage concentration) compliance
  disclosure proposal off an upstream investment-actor portfolio-
  concentration report, checked against THIS company's own recorded
  sector/stage caps -- a genuine two-sided cross-check (the investment
  actor holds the portfolio composition, this company holds the
  LPA-authorized limit; neither holds both)
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

- no management fee is drawn, no GP carry is distributed, and no
  guideline-compliance disclosure is made, without human (GP principal)
  sign-off
- a fee draw or carry distribution with no (carry-)mandate on file, a
  claimed rate exceeding the recorded mandate cap, a claimed accrual/
  carry not matching this company's own independent recomputation, a
  double-draw/double-distribution of an already-processed period/
  commitment, a guideline disclosure with no sector/stage-cap mandate on
  file, or a disclosure whose reported concentration exceeds a recorded
  cap -- each forces a hold, not an override
- every mandate/drawdown/carry-distribution/guideline-disclosure path is
  auditable
- emergency manual override paths remain outside LLM control
