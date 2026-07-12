(ns fundmgmt.kernels.gate
  "Safety kernel for the fund-management governor + phase gate — the
  decision CORE of `fundmgmt.governor/check` and `fundmgmt.phase/gate`,
  extracted into the safe-kotoba subset (cloud-itonami kernels
  discipline, ADR-0016 / superproject ADR-2607101200).

  Everything here is integer-coded and stays inside the emit-ready
  vocabulary: `defn`, `def` constants, nested `if`, `=`, `<`, integer
  arithmetic, recursion-free composition through named combinators. No
  keywords, strings, maps, atoms, host interop or I/O — the façades
  (`fundmgmt.governor`, `fundmgmt.phase`) reduce their inputs to
  flags/codes at the boundary and map the result codes back to
  keywords. The accrual/carry float-epsilon recomputations
  (`registry/fee-accrued`, `registry/carry-accrued` + the 1e-6
  tolerance) deliberately stay façade-side: they are double arithmetic
  over upstream-reported figures, not threshold constants — the kernel
  receives their RESULTS as hard flags. The rate/fraction THRESHOLD
  comparisons, by contrast, live in-kernel over micro-unit ints (x1e6)
  with the façade computing the scaled ints as bridges.
  `.kotoba`/wasm emission is deliberately NOT wired yet (owner
  decision 2026-07-12: ClojureScript + kotoba-datomic first); staying
  inside the subset is what keeps that door open without a rewrite.

  Wire codes:
    flag        0 = no, anything else = yes (norm-flag, fail-closed)
    confidence  int x100 (0..100); out-of-range counts as LOW (fail-closed)
    rate/frac   int x1e6 (micro-units; the façade rounds via Math/round —
                finer than the repo's own 1e-6 float tolerance, so every
                mandate cap / upstream rate / concentration fraction in
                the fixtures is exact or noise-level)
    op          0 read (this repo's read-ops set is EMPTY, so the façade
                never produces 0 — the code is kept for the fleet-wide
                wire contract and reads pass through if one ever appears)
                1 :mandate/record           2 :fee/drawdown
                3 :carry/distribute         4 :guideline/disclose
                5+ unknown write (never enabled)
    phase       0..3 (anything else: no writes at all — the façade
                normalizes unknown phases to its own default BEFORE the
                kernel, so an out-of-range phase reaching the kernel is
                a bug and fails closed). Phase 2 is the reserved
                duplicate of phase 1 (`fundmgmt.phase`): only
                :mandate/record writes.
    verdict     0 ok/commit-eligible  1 escalate  2 hard-hold
    disposition 0 commit  1 escalate  2 hold
    reason      0 none  1 phase-disabled  2 phase-approval

  Fail-closed direction: every invalid/unknown input degrades toward
  LESS autonomy (hold/escalate), never more. `:fee/drawdown` (op 2),
  `:carry/distribute` (op 3) and `:guideline/disclose` (op 4) are
  auto-enabled at NO phase — the same structural invariant the phase
  table and the governor's actuation gate state independently (real
  cash movements and LP-facing compliance statements are always a
  human GP principal's call)."
  )

;; --------------------------- combinators ---------------------------

(defn not-flag [a] (if (= a 0) 1 0))
(defn norm-flag
  "Fail-closed flag normalization: only exact 0 counts as 'no'."
  [a]
  (if (= a 0) 0 1))
(defn and2 [a b] (if (= a 1) (if (= b 1) 1 0) 0))
(defn or2 [a b] (if (= a 1) 1 (if (= b 1) 1 0)))
(defn or3 [a b c] (or2 a (or2 b c)))
(defn or4 [a b c d] (or2 (or2 a b) (or2 c d)))
(defn or12 [a b c d e f g h i j k l]
  (or3 (or4 a b c d) (or4 e f g h) (or4 i j k l)))

;; --------------------------- governor core -------------------------

(def confidence-floor-x100 60)

(def frac-one-x1e6
  "1.0 in micro-units — the upper bound of a valid rate/fraction cap."
  1000000)

(defn confidence-low
  "1 when the advisor confidence requires a human look. Out-of-range
  values (negative, or above 100) are treated as LOW — an advisor
  reporting impossible confidence is a reason for MORE scrutiny, not
  auto-commit."
  [x100]
  (if (< x100 0)
    1
    (if (< 100 x100)
      1
      (if (< x100 confidence-floor-x100) 1 0))))

(defn frac-out-of-range
  "1 when a proposed rate/fraction cap (int x1e6) is outside [0, 1] —
  the range check of `:invalid-rate-cap` / `:invalid-carry-rate-cap`.
  A MISSING cap is handled by the façade before the kernel (missing
  `:annual-fee-rate-cap` is a violation; missing `:carry-rate-cap` is
  a valid fee-only mandate)."
  [x1e6]
  (if (< x1e6 0)
    1
    (if (< frac-one-x1e6 x1e6) 1 0)))

(defn rate-exceeds-cap
  "1 when a claimed rate/fraction (int x1e6) EXCEEDS this company's own
  recorded cap (same scale). Equality is compliant — the cap is a
  ceiling, not an open bound. Used for the fee-rate, carry-rate and
  concentration-fraction ceiling checks alike; the façade only calls
  it when the relevant cap is actually on file."
  [rate-x1e6 cap-x1e6]
  (if (< cap-x1e6 rate-x1e6) 1 0))

(defn hard-violation
  "1 when any HARD (human-un-overridable) violation flag is set — one
  flag per governor check, in the façade's own priority order:
  invalid fee-rate cap / invalid carry-rate cap / mandate missing /
  carry mandate missing / fee rate exceeds mandate / carry rate
  exceeds mandate / accrual mismatch / carry mismatch / double draw /
  double distribution / guideline mandate missing / concentration
  limit exceeded."
  [invalid-cap invalid-carry-cap mandate-missing carry-mandate-missing
   rate-over carry-rate-over accrual-mismatch carry-mismatch
   double-draw double-distribution guideline-missing concentration-over]
  (or12 (norm-flag invalid-cap)
        (norm-flag invalid-carry-cap)
        (norm-flag mandate-missing)
        (norm-flag carry-mandate-missing)
        (norm-flag rate-over)
        (norm-flag carry-rate-over)
        (norm-flag accrual-mismatch)
        (norm-flag carry-mismatch)
        (norm-flag double-draw)
        (norm-flag double-distribution)
        (norm-flag guideline-missing)
        (norm-flag concentration-over)))

(defn verdict-code
  "Governor verdict: 2 hard-hold wins over 1 escalate wins over 0 ok."
  [invalid-cap invalid-carry-cap mandate-missing carry-mandate-missing
   rate-over carry-rate-over accrual-mismatch carry-mismatch
   double-draw double-distribution guideline-missing concentration-over
   confidence-x100 actuation]
  (if (= 1 (hard-violation invalid-cap invalid-carry-cap mandate-missing
                           carry-mandate-missing rate-over carry-rate-over
                           accrual-mismatch carry-mismatch double-draw
                           double-distribution guideline-missing
                           concentration-over))
    2
    (if (= 1 (or2 (confidence-low confidence-x100) (norm-flag actuation)))
      1
      0)))

;; ---------------------------- phase core ---------------------------

(defn op-write-enabled
  "1 when `op` may WRITE at `phase` (phase table row, :writes column).
  Phases 1 and 2 are the same row on purpose (phase 2 is reserved for
  a future write category — see `fundmgmt.phase`)."
  [phase op]
  (if (= phase 1)
    (if (= op 1) 1 0)
    (if (= phase 2)
      (if (= op 1) 1 0)
      (if (= phase 3)
        (if (= op 1) 1 (if (= op 2) 1 (if (= op 3) 1 (if (= op 4) 1 0))))
        0))))

(defn op-auto-enabled
  "1 when `op` may AUTO-COMMIT at `phase` (phase table row, :auto
  column). Exactly one cell is ever 1: phase 3 x :mandate/record.
  ops 2/3/4 (:fee/drawdown, :carry/distribute, :guideline/disclose)
  are 0 at every phase — permanent structural fact, not a rollout
  milestone."
  [phase op]
  (if (= phase 3) (if (= op 1) 1 0) 0))

(defn phase-disposition
  "Resolve the final disposition code from phase, op code and the
  governor's disposition code. Mirrors `fundmgmt.phase/gate`:
  governor hold always wins; reads pass through; a write not enabled
  at this phase holds; a governor-clean write without auto rights
  escalates; otherwise the governor's disposition stands."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    2
    (if (= op 0)
      governor-disposition
      (if (= 0 (op-write-enabled phase op))
        2
        (if (= governor-disposition 0)
          (if (= 1 (op-auto-enabled phase op)) 0 1)
          governor-disposition)))))

(defn phase-reason
  "Reason code companion of `phase-disposition` (same branch order)."
  [phase op governor-disposition]
  (if (= governor-disposition 2)
    0
    (if (= op 0)
      0
      (if (= 0 (op-write-enabled phase op))
        1
        (if (= governor-disposition 0)
          (if (= 1 (op-auto-enabled phase op)) 0 2)
          0)))))

;; ----------------------------- battery -----------------------------
;; Executable spec, kernels-style: each check returns 1 on pass, the
;; battery sums them, and the test suite locks the sum against
;; `battery-case-count` so a silently-skipped case can't pass review.

(defn check-verdict
  [c1 c2 c3 c4 c5 c6 c7 c8 c9 c10 c11 c12 conf act expected]
  (if (= (verdict-code c1 c2 c3 c4 c5 c6 c7 c8 c9 c10 c11 c12 conf act)
         expected)
    1
    0))

(defn check-flag [actual expected]
  (if (= actual expected) 1 0))

(defn check-phase [phase op gov expected-disposition expected-reason]
  (and2 (if (= (phase-disposition phase op gov) expected-disposition) 1 0)
        (if (= (phase-reason phase op gov) expected-reason) 1 0)))

(def battery-case-count 56)

(defn battery-pass-count []
  (+
   ;; -- verdict: clean, then each of the 12 hard flags alone dominates
   ;;    (conf 100, act 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 1 0 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 1 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 1 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 1 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 1 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 1 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 1 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 1 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 1 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 1 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 1 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 1 100 0 2)
   ;; -- verdict: hard-flag combos (a pair, all twelve at once)
   (check-verdict 1 0 0 0 0 1 0 0 0 0 0 0 100 0 2)
   (check-verdict 1 1 1 1 1 1 1 1 1 1 1 1 100 0 2)
   ;; -- verdict: confidence floor boundary + fail-closed range
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 59 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 60 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 100 0 0)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 -5 0 1)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 150 0 1)
   ;; -- verdict: actuation always escalates; hard still wins over it
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 100 1 1)
   (check-verdict 1 0 0 0 0 0 0 0 0 0 0 0 100 1 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 40 1 1)
   ;; -- verdict: non-0/1 flags normalize to violation (fail-closed)
   (check-verdict 7 0 0 0 0 0 0 0 0 0 0 0 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 9 100 0 2)
   (check-verdict 0 0 0 0 0 0 0 0 0 0 0 0 100 9 1)
   ;; -- numeric core: [0,1] cap range in micro-units
   (check-flag (frac-out-of-range -1) 1)
   (check-flag (frac-out-of-range 0) 0)
   (check-flag (frac-out-of-range 500000) 0)
   (check-flag (frac-out-of-range 1000000) 0)
   (check-flag (frac-out-of-range 1000001) 1)
   ;; -- numeric core: claimed rate vs recorded cap (equality compliant)
   (check-flag (rate-exceeds-cap 20000 20000) 0)
   (check-flag (rate-exceeds-cap 20001 20000) 1)
   (check-flag (rate-exceeds-cap 19999 20000) 0)
   (check-flag (rate-exceeds-cap 300000 200000) 1)
   ;; -- phase: governor hold always wins
   (check-phase 3 1 2 2 0)
   ;; -- phase: reads pass through every governor disposition
   (check-phase 0 0 0 0 0)
   (check-phase 0 0 1 1 0)
   (check-phase 1 0 1 1 0)
   ;; -- phase: write disabled at this phase -> hold, phase-disabled
   (check-phase 0 1 0 2 1)
   (check-phase 1 2 0 2 1)
   (check-phase 2 2 0 2 1)
   (check-phase 2 3 0 2 1)
   (check-phase 3 5 0 2 1)
   ;; -- phase: enabled but not auto -> escalate, phase-approval
   (check-phase 1 1 0 1 2)
   (check-phase 2 1 0 1 2)
   (check-phase 3 2 0 1 2)
   (check-phase 3 3 0 1 2)
   (check-phase 3 4 0 1 2)
   ;; -- phase: the single auto cell
   (check-phase 3 1 0 0 0)
   ;; -- phase: governor escalate passes through an enabled write
   (check-phase 3 1 1 1 0)
   (check-phase 2 1 1 1 0)
   (check-phase 3 4 1 1 0)
   ;; -- phase: out-of-range phases have no writes (fail-closed)
   (check-phase -1 1 0 2 1)
   (check-phase 4 1 0 2 1)))
