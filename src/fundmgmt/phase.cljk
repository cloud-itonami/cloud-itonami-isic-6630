(ns fundmgmt.phase
  "Phase 0->3 staged rollout -- the fund-management-company analog of
  `cloud-itonami-isic-6499`'s `vcfund.phase` / `cloud-itonami-isic-6430`'s
  `trustfund.phase`.

    Phase 0  read-only        -- no writes, still governor-gated.
    Phase 1  assisted-intake  -- mandate recording allowed, every write
                                 needs human approval.
    Phase 2  same as 1        -- reserved for a future write category.
    Phase 3  supervised auto  -- governor-clean, high-confidence
                                 `:mandate/record` (no capital risk) may
                                 auto-commit. `:fee/drawdown`/`:carry/
                                 distribute`/`:guideline/disclose` NEVER
                                 auto-commit, at any phase.

  `:fee/drawdown`/`:carry/distribute`/`:guideline/disclose` are
  deliberately ABSENT from every phase's `:auto` set, including phase 3
  -- a permanent structural fact, not a rollout milestone still to come.
  Drawing a management fee and distributing GP carry are REAL cash
  movements between the fund and the GP entity; disclosing guideline
  compliance is a compliance statement LPs will rely on. All three are
  always a human GP principal's call. `fundmgmt.governor`'s
  `:actuation/draw-fee`/`:actuation/distribute-carry`/`:actuation/
  disclose-guidelines` high-stakes gate enforces the same invariant
  independently. `:mandate/record` moves no capital (governed by its own
  HARD checks in `fundmgmt.governor`, but never `high-stakes`), so it IS
  auto-eligible at phase 3.

  The decision core is delegated to the safety kernel
  `fundmgmt.kernels.gate` (integer-coded, fail-closed, safe-kotoba
  subset); this namespace keeps the human-readable phase table (the
  documentation and structural-invariant tests read it) and does the
  keyword<->wire-code mapping at the boundary. The kernel's own battery
  and the parity matrix in `fundmgmt.kernels.gate-test` pin the two
  representations together."
  (:require [fundmgmt.kernels.gate :as kernel]))

(def read-ops  #{})
(def write-ops #{:mandate/record :fee/drawdown :carry/distribute :guideline/disclose})

;; NOTE the invariant: `:fee/drawdown`/`:carry/distribute`/`:guideline/
;; disclose` are members of `write-ops` (governor-gated like any write)
;; but are NEVER members of any phase's `:auto` set below. Do not add
;; them there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                    :auto #{}}
   1 {:label "assisted-intake" :writes #{:mandate/record}      :auto #{}}
   2 {:label "assisted-intake" :writes #{:mandate/record}      :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:mandate/record}}})

(def default-phase 3)

;; ---- kernel wire-code bridges (façade-side, not kernel vocabulary) ----

(defn- op->code
  "Kernel op wire code. This repo's `read-ops` is empty, so 0 (read) is
  never produced here — kept for the fleet-wide wire contract. Unknown
  ops map to 5 (unknown write) — the kernel never write-enables it, so
  an unrecognized op fails closed to HOLD exactly as the old
  set-membership logic did."
  [op]
  (cond
    (contains? read-ops op)       0
    (= op :mandate/record)        1
    (= op :fee/drawdown)          2
    (= op :carry/distribute)      3
    (= op :guideline/disclose)    4
    :else                         5))

(defn- disposition->code [d]
  (cond (= d :commit) 0 (= d :escalate) 1 (= d :hold) 2 :else 2))

(defn- code->disposition [c]
  (if (= c 0) :commit (if (= c 1) :escalate :hold)))

(defn- code->reason [c]
  (if (= c 1) :phase-disabled (if (= c 2) :phase-approval nil)))

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:fee/drawdown`/`:carry/distribute`/`:guideline/disclose` are never
    auto-eligible at any phase, so they always escalate once the
    governor clears them (or hold if the governor doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [p (if (contains? phases phase) phase default-phase)
        op-code (op->code op)
        gov-code (disposition->code governor-disposition)
        d (kernel/phase-disposition p op-code gov-code)
        r (kernel/phase-reason p op-code gov-code)]
    {:disposition (code->disposition d)
     :reason (code->reason r)}))

(defn verdict->disposition
  "Map a FundManagementGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
