# wasm/ — kotoba-wasm deployment of the fee-accrual recompute

`fee_accrual.kotoba` is a port of `fundmgmt.registry/fee-accrued`'s flat,
non-compounded annual-rate management-fee accrual formula (the
independent recompute `fundmgmt.governor`'s `:accrual-mismatch` HARD check
runs against an upstream `vcfund` fee report's claimed `:accrued-amount`
-- see `src/fundmgmt/governor.cljc`'s ns docstring, check 7) into the
minimal `.kotoba` language subset, compiled to a real WASM module via
`kotoba wasm emit`, and hosted via `kototama.tender`
(`test/wasm/fee_accrual_test.clj`).

This follows the same `kotoba wasm emit` → `kototama.tender` pipeline
`cloud-itonami-isic-6492`'s `wasm/affordability.kotoba` and
`cloud-itonami-isic-6511`'s `wasm/underwriting_decision.kotoba`
established (ADR-2607062330 addendum 5) -- `fee_accrual.kotoba` is the
closest analog to `affordability.kotoba`: a formula recompute over
integer inputs, no host imports.

## Why the source differs from `fundmgmt.registry/fee-accrued`

The `.kotoba` compiler's actual WASM code-generator supports only a small,
empirically-verified subset: the special forms `do`/`let`/`if` plus
`+ - * quot / rem mod = < > <= >= zero? not inc dec` (confirmed by reading
`compile-wasm-expr` in `kotoba-lang/kotoba/src/kotoba/runtime.clj` -- no
`pos?`/`neg?`/`and`/`or`/`when`, unlike the broader tree-walking
interpreter, same finding `cloud-itonami-isic-6492`/`-6511` document). The
port therefore:

- Uses plain positional args instead of `{:keys [...]}` map destructuring
  (no maps in the wasm-compilable subset).
- Drops the three `throw`/`ex-info` precondition guards (`fee-basis`/
  `annual-fee-rate`/`years-elapsed` must be `>= 0`) entirely -- a WASM
  export can't throw a JVM exception, and unlike `affordability.kotoba`
  (which substitutes an explicit `(> annual-income 0)` guard because a
  non-positive income changes which branch the formula takes), the
  accrual formula itself is a straight-line product with no branch to
  guard: a negative input just flows through the integer multiply/divide
  and produces a recomputed value that will not match a well-formed
  positive claim, so the mismatch falls out of the comparison rather than
  needing its own `if`. Precondition validation stays the real
  `fundmgmt.registry/fee-accrued`'s job, same "the guest only ever sees
  facts a governor already validated" posture `underwriting_decision.kotoba`
  documents.
- Represents `annual-fee-rate` (a `[0,1]` fraction, e.g. `0.02` for 2%) as
  integer **basis points** (`rate-bps`, e.g. `200` = 2.00%) and
  `years-elapsed` (e.g. `1.5`) as integer **hundredths of a year**
  (`years-x100`, e.g. `150` = 1.50 years) instead of doubles -- avoids
  floating point entirely, the same "no floats in the wasm-compilable
  subset" constraint `affordability.kotoba`'s integer cross-multiplication
  works around. `fee-basis` was already an integer (smallest currency
  unit) in the original formula, unchanged here.
- Recomputes `accrued = fee-basis * rate-bps * years-x100 / 1_000_000` in
  one `quot`, instead of the two separate fraction-scaling divisions
  `rate-bps / 10000` and `years-x100 / 100` implied by the fixed-point
  design: `(rate-bps / 10000) * (years-x100 / 100) = (rate-bps *
  years-x100) / 1_000_000`, and multiplying that by `fee-basis` gives the
  exact same product the float formula (`fee-basis * annual-fee-rate *
  years-elapsed`) computes, in the same currency unit as `fee-basis` --
  doing it as a single multiply-then-divide (rather than two intermediate
  divisions) avoids compounding integer-truncation error into the result.
- Compares the recomputed accrual against the fourth input,
  `claimed-accrued-amount`, with a plain `=` -- `fundmgmt.governor`'s own
  `accrual-mismatch-violations` check uses `close?`, a `1e-6` float
  tolerance, but that tolerance exists purely to absorb IEEE-754 double
  rounding noise in the JVM's float multiply, not to intentionally permit
  a real currency-unit discrepancy. The wasm port's fixed-point formula is
  exact integer arithmetic with no rounding noise of that kind, so the
  float-epsilon tolerance has no integer analog to port -- an exact `=` is
  the faithful reduction, not an invented allowance.

**Known scope limit (i32 range):** the guest computes the full three-way
product `fee-basis * rate-bps * years-x100` in a single 32-bit WASM
`i32.mul` fold *before* dividing (mirroring the single-`quot` design
above), so that intermediate product must stay under the signed i32
ceiling (~2.147e9) or it silently wraps instead of trapping. The
illustrative `fee-basis` values this module and its tests use are modest
(hundreds to low thousands of dollars, in cents) precisely to stay inside
that bound with headroom -- this is a PoC-scale limitation, not a design
claim that the formula holds for realistic fund-sized (many-million-
dollar) fee bases. Raising it would mean promoting to `i64` arithmetic
(`i64*`/`i64-` exist in the subset, see `compile-wasm-expr`) or dividing
earlier at the cost of compounding truncation, either a follow-up, not
attempted in this pass.

## ABI — parameterized invocation

`kotoba wasm emit` rejects any `main` with parameters (`:main-arity` --
the compiler only ever exports a 0-arity `main`, see `compile-wasm-expr`
in `kotoba-lang/kotoba/src/kotoba/runtime.clj`), so real inputs are passed
through the guest's exported linear memory instead -- the same convention
`cloud-itonami-isic-6492`'s `affordability.kotoba` and
`cloud-itonami-isic-6511`'s `underwriting_decision.kotoba` use. A host
writes four little-endian i32 values before calling `main()`:

| offset | field                     | unit                                        |
|--------|---------------------------|----------------------------------------------|
| 0      | `fee-basis`                | smallest currency unit (cents)               |
| 4      | `rate-bps`                 | basis points (`annual-fee-rate * 10000`)     |
| 8      | `years-x100`               | hundredths of a year (`years-elapsed * 100`) |
| 12     | `claimed-accrued-amount`   | smallest currency unit (cents)               |

`main()` returns `1` (the recomputed accrual matches the claim) or `0`
(mismatch -- `fundmgmt.governor`'s `:accrual-mismatch` HARD violation).
All four offsets are well below `heap-base` (2048), so they never
collide with anything the compiler itself places in memory.

## Rebuilding

```sh
cd ../../kotoba-lang/kotoba   # sibling checkout, west-managed
bin/kotoba-clj wasm emit ../../cloud-itonami/cloud-itonami-isic-6630/wasm/fee_accrual.kotoba \
  --package-lock kotoba.lock.edn \
  --output ../../cloud-itonami/cloud-itonami-isic-6630/wasm/fee_accrual.wasm --json
```

Fleet deployment: not attempted in this pass -- see
`cloud-itonami-isic-6492`/`cloud-itonami-isic-6511` for the established
pattern.
