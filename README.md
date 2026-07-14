# cloud-itonami-isic-6630

Open Business Blueprint for **ISIC Rev.5 6630**: Fund management activities.

This repository is the **management company (GP entity)** in a
three-actor VC-fund system, the other two being `cloud-itonami-isic-6499`
(the investment decision-maker: DD, deal sourcing, capital-call/
commitment/distribution PROPOSALS) and `cloud-itonami-isic-6430` (the
fund vehicle: LP subscriptions, capital-call notice issuance). This repo
is the legal entity that earns management fees for running the fund and
discloses guideline (sector/stage concentration) compliance to LPs --
run by a qualified, licensed operator so a community or independent
professional never surrenders customer data and ledgers to a closed
SaaS.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot performs
the physical domain work**. Here a document-custody robot manages physical mandate/prospectus custody,
under an actor that proposes actions and an independent **FundManagementGovernor**
that gates them. The governor never dispatches hardware itself;
`:high`/`:safety-critical` actions require human sign-off.

## Relationship to `cloud-itonami-isic-6499` (the investment actor)

**Three separate legal entities, three separate repos, no shared code --
only a documented DATA CONTRACT.** See `cloud-itonami-isic-6430`'s
README/ADR for the full three-actor picture. This repo's role: `vcfund.
nav/fund-nav-report` (in the SEPARATE `cloud-itonami-isic-6499` repo)
computes a management-fee accrual figure -- an ADVISORY calculation, not
itself a real cash movement. It becomes one only when THIS company
actually draws the fee.

`fundmgmt.governor` NEVER trusts that upstream figure as-is. It
independently re-derives the SAME flat-rate formula
(`fundmgmt.registry/fee-accrued`, a deliberately SEPARATE
re-implementation of `vcfund.nav/management-fee-accrued`'s base-case
math, not a shared-library call) from the upstream report's own
fee-basis/rate/years-elapsed, and compares the result against the
report's claimed accrued amount. It ALSO independently checks the
upstream-claimed rate against THIS company's own recorded mandate
ceiling -- a check the investment actor has no concept of at all, since
rate-cap compliance is fundamentally the management company's own
fiduciary responsibility. And it refuses to draw the SAME billing period
twice, off its own drawdown history -- a guard that needs no upstream
comparison at all. Honestly bounded: unlike `cloud-itonami-isic-6430`'s
governor (which independently re-derives its OWN `fee-basis`-equivalent,
the pro-rata allocation, from its own subscription ledger), this company
has no LP directory of its own and cannot independently re-derive
`fee-basis` -- only reapply the rate*basis*years formula to whatever
basis the upstream report claims (see `fundmgmt.governor`'s docstring).

The SAME pattern governs GP carry: `vcfund.registry/distribute-
waterfall`'s `:gp-carry` (the GP's carried-interest share of profit
after return-of-capital and the preferred return) becomes a real cash
movement only when THIS company distributes it. `fundmgmt.governor`
independently reapplies the SAME profit*rate split
(`fundmgmt.registry/carry-accrued`, again a separate re-implementation)
from the upstream fact's own after-preferred-profit/carry-rate, checks
the claimed carry-rate against a SEPARATE `:carry-rate-cap` this company
records (a fund's LPA can authorize a management fee without yet
authorizing carry distribution through this company at all), and
refuses to distribute the SAME commitment's carry twice.

A THIRD pattern, narrower still but a genuine two-sided cross-check
rather than a one-sided recompute: `vcfund.concentration/concentration-
report` reports what fraction of deployed capital sits in each
portfolio `:sector`/`:investment-stage` -- a fact this company has no
deal data of its own to independently recompute. `:guideline/disclose`
compares that upstream-reported fraction against THIS company's OWN
mandate-defined `:sector-caps`/`:stage-caps` -- a fact `vcfund` does not
hold either (an LPA-authorized concentration limit is this company's
own fiduciary record). Neither repo holds both halves of this check
alone.

## Core Contract

```text
upstream vcfund fee-accrual report / exit-distribution fact / concentration report (a separate repo's calculation, read as a fact)
        |
        v
   ┌───────────────┐   proposal      ┌───────────────────────┐
   │ FundManager-LLM│ ─────────────▶ │ FundManagementGovernor  │  (independent system)
   │  (sealed)     │  + citations    │ invalid-(carry-)rate-cap│
   └───────────────┘                 │ (carry-)mandate-missing │
                             commit ◀────┼──────────▶ hold │ (carry-)rate-exceeds-mandate ·
                                 │             │              │ accrual/carry-mismatch ·
                           record + ledger  escalate ─▶ human │ double-draw/-distribution ·
                                             (ALWAYS for      │ guideline-mandate-missing ·
                                              :fee/drawdown,    concentration-limit-exceeded
                                              :carry/distribute
                                              AND :guideline/disclose)
```

No automated proposal, by itself, can draw a management fee,
distribute GP carry, or disclose guideline compliance without
`FundManagementGovernor` approval and a human GP principal's sign-off.

## Actuation

**Drawing a management fee, distributing GP carry, or disclosing
guideline compliance is never autonomous, at any phase, by
construction.** The first two are real cash movements between the fund
and the GP entity; guideline disclosure is a compliance statement LPs
will rely on. `fundmgmt.governor/high-stakes` has three members,
`:actuation/draw-fee`, `:actuation/distribute-carry` and `:actuation/
disclose-guidelines`; `fundmgmt.phase` never puts any of them in any
phase's `:auto` set. Two independent layers enforce this.
`:mandate/record` moves no capital (still HARD-gated -- an out-of-range
rate cap is un-overridable -- but not `high-stakes`), so it IS
auto-eligible at phase 3.

## Run

```bash
clojure -M:dev:run     # walk one clean mandate+drawdown+carry+guideline-disclosure lifecycle + ten HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Layout

| File | Role |
|---|---|
| `src/fundmgmt/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + mandate/fee-drawdown/carry-distribution/guideline-disclosure history + double-draw-by-period AND double-distribution-by-commitment checks |
| `src/fundmgmt/registry.cljc` | Investment-mandate draft (optional carry-rate-cap, sector-caps, stage-caps) + fee-drawdown draft + carry-distribution draft + guideline-disclosure draft records, `fee-accrued`/`carry-accrued` (INDEPENDENT re-implementations of `vcfund.nav/management-fee-accrued`'s base-case math and `vcfund.registry/distribute-waterfall`'s `:gp-carry` split -- see "Relationship") |
| `src/fundmgmt/advisor.cljc` | **FundManager-LLM** -- `mock-advisor`; mandate-intake/fee-drawdown/carry-distribution/guideline-disclosure proposals (the latter three read an upstream `vcfund` fact as-is) |
| `src/fundmgmt/governor.cljc` | **FundManagementGovernor** -- 10 HARD checks (invalid-(carry-)rate-cap · (carry-)mandate-missing · (carry-)rate-exceeds-mandate · accrual/carry-mismatch · guideline-mandate-missing · concentration-limit-exceeded) + double-draw/-distribution guards + 1 soft (confidence/actuation gate) |
| `src/fundmgmt/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → supervised (fee drawdown/carry distribution/guideline disclosure always human; mandate intake auto-eligible, no capital risk) |
| `src/fundmgmt/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/fundmgmt/sim.cljc` | demo driver -- includes literal upstream fee-report/exit-distribution/concentration-report fixtures matching `vcfund.nav/fund-nav-report`'s/`vcfund.registry/distribute-waterfall`'s/`vcfund.concentration/concentration-report`'s exact shapes |
| `test/fundmgmt/*_test.clj` | governor contract · phase invariants · store parity · registry conformance |
| `wasm/fee_accrual.kotoba` | PoC: a WASM-compiled (`kotoba-lang/kotoba` -> `kotoba-lang/kototama`'s `actor:host` ABI) fixed-point (rate-bps/years-x100) port of `fundmgmt.registry/fee-accrued`'s independent-recompute formula, i.e. `fundmgmt.governor`'s `:accrual-mismatch` HARD check -- see `wasm/README.md` for the offset layout and scaling rationale |

## Business-process coverage (honest)

This actor covers THREE flagship cross-repo integration points
(management-fee drawdown, GP carry distribution AND guideline-
compliance disclosure, all off upstream investment-actor facts) plus
the mandate-intake foundation they depend on. This is now every offer
this blueprint's `docs/business-model.md` originally listed:

| Covered | Not covered (out of scope by design) |
|---|---|
| Investment-mandate (LPA-authorized fee-rate ceiling, OPTIONALLY a SEPARATE carry-rate ceiling, OPTIONALLY sector/stage concentration caps) intake, HARD-gated on every rate/cap being a valid [0,1] fraction (`:mandate/record`) | Rebalancing/trade execution (this blueprint's generic Offer lists it; not applicable to a VC fund, which does not rebalance public-market positions), real fund-accounting-system integration, tax/regulatory reporting |
| Management-fee DRAWDOWN off an upstream `vcfund` (`cloud-itonami-isic-6499`) fee-accrual report, independently re-verified (formula recompute + rate-cap check) and double-draw-protected (`:fee/drawdown`) | |
| GP carry (profit-share) DISTRIBUTION off an upstream `vcfund` exit-distribution waterfall's `:gp-carry`/`:lp-residual-profit`, independently re-verified (formula recompute + a SEPARATE carry-rate-cap check) and double-distribution-protected by commitment number (`:carry/distribute`) | |
| Investment-guideline (sector/stage concentration) COMPLIANCE DISCLOSURE off an upstream `vcfund` portfolio-concentration report (`:guideline/disclose`) -- a genuine two-sided cross-check, not a one-sided carry-through: this company checks the upstream-reported fraction against caps only IT holds, since `vcfund` has no concept of an LPA-authorized limit | |
| Immutable audit ledger for every mandate/drawdown/carry-distribution/guideline-disclosure decision | |

Extending coverage is additive: add the next gate as its own governed
op with its own HARD checks and tests, following the SAME cross-repo
"read an upstream fact, never trust it, independently re-verify (or,
where the check is genuinely two-sided, compare against a fact only
THIS company holds)" pattern this repo's three flagship ops already
establish.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`6630`). `fundmgmt.*` is a self-contained governed implementation -- it
does not require the sibling `kotoba-lang/securities` capability lib
directly, the same "self-contained sibling" relationship `vcfund.*` has
to `kotoba-lang/insurance`.

See [`docs/business-model.md`](docs/business-model.md),
[`docs/operator-guide.md`](docs/operator-guide.md) and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md).

## Maturity

`:implemented` -- `FundManager-LLM` + `FundManagementGovernor` run as
real, tested code (see `Run` above), promoted from the originally-
published `:blueprint`-tier scaffold. See `docs/adr/0001-architecture.md`
for the history and the cross-repo integration design.

## License

Code and implementation templates are AGPL-3.0-or-later.
