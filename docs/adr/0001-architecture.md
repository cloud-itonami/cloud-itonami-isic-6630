# ADR-0001: cloud-itonami-isic-6630 -- FundManager-LLM as a contained intelligence node, cross-repo integration with `cloud-itonami-isic-6499`

- Status: Accepted (2026-07-06)
- Related: `cloud-itonami-isic-6499` ADR-0001 (DD-LLM ⊣
  InvestmentCommitteeGovernor, the venture-fund investment actor this
  repo interoperates with), `cloud-itonami-isic-6430` ADR-0001
  (TrustAdmin-LLM ⊣ TrustFundGovernor, the fund vehicle -- the third
  actor in the same three-repo VC-fund system, and the fuller writeup of
  WHY these three repos exist and how they relate), `cloud-itonami-isic-
  6511` ADR-0001 (Underwriter-LLM ⊣ UnderwritingGovernor, the original
  pattern this family ports), langgraph-clj ADR-0001

## Context and history

This repository was originally published (2026-07-04) as a generic
`:blueprint`-tier ISIC 6630 scaffold ("fund management activities") --
README/blueprint.edn/docs only, no running code, generic `FundManager-
LLM`/`Fund Management Governor` names with no actual implementation
behind them, and an Offer list ("rebalancing/trade proposal") more
oriented toward mutual-fund/pension-asset management than a VC fund.

See `cloud-itonami-isic-6430`'s ADR-0001 for the full context of why
this repo, and `6430`, were built out in the same session: the owner
asked whether `cloud-itonami-isic-6499` (the venture-capital investment
actor) was itself "連携" (linked) with any other ISIC classification, and
on finding `6430`/`6630` were documentation-only despite being named as
"adjacent" in `6499`'s own ADR, picked the largest of three offered
scopes -- implement both as real governed actors and wire them to
genuinely interoperate with `6499`.

## Problem

A real VC fund's management company (the GP entity) does two things a
generic mutual-fund manager's blueprint doesn't quite capture:

1. It earns a **management fee** -- typically ~2% annually on committed
   capital, per the fund's LPA. `cloud-itonami-isic-6499`'s `vcfund.nav/
   fund-nav-report` already computes this accrual (a pure ADVISORY
   calculation reading the investment actor's own LP/deal data), but
   computing a number is not the same as this company actually DRAWING
   it as real cash.
2. It has its own, SEPARATE fiduciary duty to the LPs: the fee rate
   actually charged must not exceed what the LPA authorizes, and the
   SAME billing period must never be drawn twice. Neither of these is
   something the investment actor (`6499`) has any concept of at all --
   `vcfund.governor` computes accruals but has no notion of an "LPA fee
   cap" or "already billed this quarter." That is fundamentally this
   management company's OWN responsibility.

"Rebalancing/trade execution," this blueprint's originally-published
Offer item, does not apply to a VC fund (there is no public-market
position to rebalance) -- retained in `docs/business-model.md` only as a
note that this ISIC class also covers mutual-fund/pension-asset
management contexts where it would.

## Decision

### 1. `fee/drawdown` is the flagship integration op

`fundmgmt.advisor/propose-fee-drawdown` reads an upstream fee-accrual
report (`:upstream-fee-report`, the exact `{:fee-basis .. :annual-fee-
rate .. :years-elapsed .. :accrued-amount ..}` shape an operator would
read off `vcfund.nav/fund-nav-report`'s inputs/output in the SEPARATE
`cloud-itonami-isic-6499` repo) as a fact, never invented, and proposes
`:fee/drawn`.

`fundmgmt.governor` NEVER trusts that report's numbers as-is. THREE
independent HARD checks fire on it:

- **`mandate-missing-violations`** -- an investment mandate (the LPA-
  authorized fee-rate ceiling) must actually be recorded with this
  company before any fee is drawn.
- **`rate-exceeds-mandate-violations`** -- the upstream report's claimed
  `:annual-fee-rate` must not exceed THIS company's OWN recorded
  `:annual-fee-rate-cap`. This is a check `vcfund.governor` has no
  equivalent of at all -- rate-cap compliance is this company's OWN
  fiduciary responsibility, deliberately NOT duplicated into `6499`.
- **`accrual-mismatch-violations`** -- `fundmgmt.registry/fee-accrued`
  INDEPENDENTLY recomputes the flat-rate formula from the upstream
  report's own fee-basis/rate/years-elapsed and compares the result
  against the report's claimed `:accrued-amount` (within float
  tolerance).

Plus a fourth guard, `double-draw-violations`, needing no upstream
comparison at all -- it simply refuses to draw the SAME `:period` twice,
off this company's OWN drawdown history.

### 2. Deliberately SEPARATE re-implementation of the accrual formula

`fundmgmt.registry/fee-accrued` is NOT a call into `vcfund.nav/
management-fee-accrued` -- it independently reimplements that fn's
BASE-CASE flat-rate formula (fee-basis * rate * years-elapsed). The same
rationale `cloud-itonami-isic-6430`'s ADR gives for its own independent
pro-rata re-implementation applies here: a shared library would mean a
bug in ONE implementation silently defeats the entire point of
independent re-verification.

### 3. Honestly bounded: cannot independently re-derive `fee-basis`

Unlike `trustfund.governor`'s allocation-mismatch check (which
independently re-derives ITS input, the pro-rata allocation, from its
OWN subscription ledger), this company has NO LP directory of its own --
it cannot independently re-derive `:fee-basis` (the sum of LP
commitments) the way `6430` can re-derive a capital-call allocation. It
can only reapply the rate*basis*years FORMULA to whatever basis the
upstream report claims, plus independently check the RATE against its
own mandate. This is a real, meaningful, non-trivial check (catches a
wrong-formula application, an over-cap rate, or a double-draw) but it is
NOT a full independent re-derivation of every input -- documented
honestly in the governor's own docstring and in README's coverage table,
not silently claimed as equivalent to `trustfund`'s stronger guarantee.

### 4. Mandate intake is the foundation, non-actuation write

`:mandate/record` has a HARD gate (`invalid-rate-cap-violations` --
checked at the governor so an out-of-range rate HOLDS gracefully rather
than throwing an uncaught exception when `fundmgmt.registry/register-
mandate` validates it again at commit time) but is NOT `high-stakes` --
recording the LPA's fee terms moves no capital -- so it is auto-eligible
at phase 3.

### 5. `:actuation/draw-fee` is the one high-stakes member

This company performs exactly ONE real-world cash movement today:
drawing the management fee. `fundmgmt.phase` never puts it in any
phase's `:auto` set -- two independent layers (governor high-stakes
gate, phase table) agree, the same invariant `vcfund.*`/`trustfund.*`
establish.

### 6. Scoped to ONE flagship op for this R0

Carry (GP profit-share) distribution -- which would follow the SAME
"read an upstream `vcfund` exit-distribution waterfall fact, independently
re-verify, never trust it" pattern against `vcfund.registry/register-
distribution`'s `:total-to-gp` figure -- and investment-guideline
disclosure beyond the fee-rate cap (sector/stage/concentration limits)
are documented as explicit next steps in README's coverage table, not
silently claimed as done.

## Consequences

- (+) The cross-repo integration is PROVEN, not merely asserted: the
  demo (`clojure -M:dev:run`) and test suite
  (`governor_contract_test.clj`) exercise a CLEAN upstream report
  (escalates then commits), a report exceeding the mandate rate, a
  report with a mismatched accrual, a fee draw with no mandate at all,
  and a double-draw of an already-drawn period.
- (+) No shared-code dependency between `cloud-itonami-isic-6499` and
  this repo -- each is independently forkable, independently deployable.
- (-) Because there is no shared library, the DATA CONTRACT (the exact
  shape `vcfund.nav/fund-nav-report` produces) is documented but not
  type-checked across the repo boundary -- the same accepted limitation
  `cloud-itonami-isic-6430`'s ADR notes for its own upstream draft.
- (-) This company cannot independently re-derive `:fee-basis` (see
  Decision §3) -- a genuinely narrower independent-verification
  guarantee than `6430`'s, honestly documented rather than glossed over.
- (-) Carry distribution and guideline-compliance checks remain
  blueprint-only for this company (see README's coverage table).

## Test/lint status

`test/fundmgmt/*` -- 25 tests / 98 assertions, lint-clean
(`clojure -M:lint`), demo (`clojure -M:dev:run`) runs end-to-end with no
exceptions: one clean mandate+drawdown lifecycle (escalate → approve →
commit) plus four HARD-hold cases (no mandate on file, rate exceeding the
mandate cap, mismatched accrual, double-draw of the same period) that
never reach a human.

## Addendum 1 (2026-07-06, same day, autonomous /loop iteration): `:carry/distribute` -- GP carry distribution closes the second flagship integration point

The recurring `/loop` prompt asked for coverage/maturity improvement, so
this autonomously picked "Carry (GP profit-share) distribution off an
upstream `vcfund` exit-distribution waterfall's `:total-to-gp` figure"
off this repo's own README coverage table -- explicitly documented as
"the SAME integration pattern, not yet implemented" since the R0 build
(the original Decision section above).

- **`fundmgmt.registry/carry-accrued`** (new, pure) -- INDEPENDENTLY
  reimplements `vcfund.registry/distribute-waterfall`'s `:gp-carry` split
  (`after-preferred-profit * carry-rate`). `after-preferred-profit` is
  NOT itself exposed by that fn's return -- an operator/integration
  reconstructs it as `:gp-carry` + `:lp-residual-profit` from the
  upstream waterfall record, the documented data-contract translation
  (same posture as `cloud-itonami-isic-6499`'s own `fund-nav-report`
  exposing `:fee-basis` for this repo's fee-drawdown integration).
- **`fundmgmt.registry/register-carry-distribution`** (new) -- drafts
  the `CARRY-000000`-style distribution record, referencing the upstream
  `commitment_number` for traceability, mirroring `register-fee-
  drawdown`'s shape.
- **`fundmgmt.registry/register-mandate` grew a 4th positional arg**,
  `carry-rate-cap` -- OPTIONAL (the 3-arity form still works unchanged,
  fully backward compatible, verified by a dedicated test asserting the
  3-arity record never even contains a `carry_rate_cap` key). A fund's
  LPA can authorize a management fee without (yet) authorizing carry
  distribution through this company at all -- a SEPARATE cap from
  `annual-fee-rate-cap`, not a reuse of it.
- **New op `:carry/distribute`** -- ingests `:upstream-distribution-
  report` (`{:after-preferred-profit .. :carry-rate .. :gp-carry ..}`,
  the exact shape an operator derives from an upstream `vcfund.registry/
  register-distribution` fact). FOUR new HARD checks mirror the
  fee-drawdown pattern exactly: `carry-mandate-missing-violations` (a
  mandate WITH a `:carry-rate-cap` must be on file -- a fee-only mandate
  does not authorize this op), `carry-rate-exceeds-mandate-violations`
  (the claimed rate can't exceed this company's OWN recorded ceiling),
  `carry-mismatch-violations` (independently recompute via `carry-
  accrued` and compare against the claimed `:gp-carry`), and `double-
  distribution-violations` (refuses the SAME `commitment-number` twice,
  off this company's own history -- no upstream comparison needed).
  `:actuation/distribute-carry` joins `high-stakes` as a second member
  (a management company moves real cash in two economic forms -- fee and
  carry -- so, like `vcfund.governor`'s multi-member set, this is not
  collapsed to one). `:carry/distribute` is NEVER auto-eligible at any
  phase, the same permanent structural invariant `:fee/drawdown` already
  established.
- Demo (`clojure -M:dev:run`) ties the clean carry-distribution scenario
  directly to `cloud-itonami-isic-6499`'s own demo numbers
  (after-preferred-profit=9,520,000, carry-rate=20%, gp-carry=1,904,000
  -- the exact figures from that repo's `waterfall-splits-carry-only-on-
  residual-profit` deal-by-deal example), making the cross-repo
  narrative concrete rather than abstract.

Consequences: `test/fundmgmt/*` grew from 25 tests/98 assertions to 41
tests/166 assertions (new `register-mandate` 4-arity/carry-rate-cap
tests, `carry-accrued`/`register-carry-distribution` tests in
`registry_test.clj`; carry-distribution parity in `store_contract_
test.clj`; five new HARD-hold + one escalate-then-approve/reject test in
`governor_contract_test.clj`; `:carry/distribute` never-auto structural
test in `phase_test.clj`), still lint-clean; demo now walks BOTH
flagship integration flows (fee drawdown, carry distribution) through
clean escalate-then-approve paths plus eight total HARD-hold cases (four
per op) that never reach a human. Remaining honest gaps unchanged from
the original Decision section: investment-guideline disclosure beyond
the fee/carry-rate caps, rebalancing/trade (not applicable to a VC
fund), real fund-accounting-system integration, tax/regulatory
reporting.

## Addendum 2 (2026-07-06, same day, owner-directed): `:guideline/disclose` -- investment-guideline compliance disclosure, closing the last documented gap

Addendum 1's "remaining honest gaps" named investment-guideline
disclosure (sector/stage/concentration limits) as the one item left.
Unlike the fee-drawdown/carry-distribution integrations, this one could
NOT be built by consuming an existing upstream fact -- `cloud-itonami-
isic-6499` (`vcfund`) had no deal-sector or investment-stage data
modeled at all. Asked how to proceed, the owner chose to design the
missing taxonomy in `vcfund` first, then build this op on top -- a
larger scope than any prior addition in this three-actor system, since
it required a coordinated change across TWO repos rather than one.

- **`vcfund` side** (see that repo's ADR Addendum 12 for the full
  design): deals gained OPTIONAL `:sector`/`:investment-stage` fields
  (no closed enum invented), and a new `vcfund.concentration/
  concentration-report` adapter computes what fraction of deployed
  capital sits in each, across committed/exited deals, with an
  `:unclassified` sentinel for any deal missing either tag.
- **`fundmgmt.registry/register-mandate` grew a 5th positional-arg
  slot**, `guideline-caps` (`{:sector-caps {name fraction ..} :stage-
  caps {name fraction ..}}`) -- OPTIONAL, the 3- and 4-arity forms still
  work unchanged (verified by a dedicated test asserting the 4-arity
  record never contains `sector_caps`/`stage_caps` keys at all). Cap
  names are the SAME free-text sector/stage strings `vcfund.
  concentration` uses -- a cap map with no entry for a given name means
  no limit is enforced for it, not a violation.
- **New op `:guideline/disclose`** -- ingests `:upstream-concentration-
  report` (the exact `{:total-invested-at-cost .. :by-sector .. :by-
  investment-stage ..}` shape `vcfund.concentration/concentration-
  report` returns). TWO new HARD checks, both direct comparisons rather
  than recomputes (there is no formula to independently re-derive a
  portfolio-composition fraction from): `guideline-mandate-missing-
  violations` (a mandate with AT LEAST one of `:sector-caps`/`:stage-
  caps` must be on file -- nothing to disclose compliance against
  otherwise) and `concentration-limit-exceeded-violations` (for every
  sector/stage THIS company's OWN mandate caps, does the upstream-
  reported fraction exceed it? -- a name the mandate does NOT cap is
  never flagged). `:actuation/disclose-guidelines` joins `high-stakes`
  as a THIRD member; `:guideline/disclose` is NEVER auto-eligible at any
  phase, the same permanent structural invariant the other two ops
  already established.
- **This is a genuinely different KIND of cross-check from the other
  two ops**, worth stating precisely: fee-drawdown/carry-distribution
  are RECOMPUTE-and-compare (this company reapplies a known formula to
  the upstream's own inputs and checks the result). Guideline disclosure
  is a two-sided FACT-comparison -- `vcfund` supplies a fact
  (composition) this company cannot derive, this company supplies a
  fact (the cap) `vcfund` does not hold, and the check is simply
  reported-value vs. recorded-limit. Documented explicitly in `fundmgmt.
  governor`'s docstring rather than describing it as "the same pattern"
  when it structurally is not.
- Demo (`clojure -M:dev:run`) ties the clean disclosure scenario to a
  concrete three-sector portfolio (ai/robotics/fintech, seed/series-a
  stages) with caps chosen so the clean case passes and a SEPARATE
  scenario (one sector's fraction pushed to 95%) HARD-holds against an
  80% cap, making both the pass and fail paths concrete rather than
  abstract.

Consequences: `test/fundmgmt/*` grew from 41 tests/166 assertions to 52
tests/220 assertions (new `register-mandate` 5-arity/guideline-caps
tests and `register-guideline-disclosure` tests in `registry_test.clj`;
guideline-disclosure parity in `store_contract_test.clj`; three new
tests -- no-caps-mandate hold, concentration-exceeded hold, escalate-
then-approve/reject -- in `governor_contract_test.clj`; `:guideline/
disclose` never-auto structural test in `phase_test.clj`), still
lint-clean; demo now walks THREE flagship integration flows (fee
drawdown, carry distribution, guideline disclosure) through clean
escalate-then-approve paths plus ten total HARD-hold cases that never
reach a human. This closes the LAST gap named in this repo's original
`docs/business-model.md` Offer list -- the only "not covered" item
remaining (rebalancing/trade execution) is explicitly marked not
applicable to a VC fund, not an unaddressed gap.
