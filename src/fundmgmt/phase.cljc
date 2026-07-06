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
                                 auto-commit. `:fee/drawdown` NEVER
                                 auto-commits, at any phase.

  `:fee/drawdown` is deliberately ABSENT from every phase's `:auto` set,
  including phase 3 -- a permanent structural fact, not a rollout
  milestone still to come. Drawing a management fee is a REAL cash
  movement from the fund to the GP entity; it is always a human GP
  principal's call. `fundmgmt.governor`'s `:actuation/draw-fee`
  high-stakes gate enforces the same invariant independently.
  `:mandate/record` moves no capital (governed by its own HARD checks in
  `fundmgmt.governor`, but never `high-stakes`), so it IS auto-eligible
  at phase 3.")

(def read-ops  #{})
(def write-ops #{:mandate/record :fee/drawdown})

;; NOTE the invariant: `:fee/drawdown` is a member of `write-ops`
;; (governor-gated like any write) but is NEVER a member of any phase's
;; `:auto` set below. Do not add it there.
(def phases
  "phase -> {:label .. :writes <ops allowed to write> :auto <ops allowed to
  auto-commit when governor-clean>}."
  {0 {:label "read-only"       :writes #{}                    :auto #{}}
   1 {:label "assisted-intake" :writes #{:mandate/record}      :auto #{}}
   2 {:label "assisted-intake" :writes #{:mandate/record}      :auto #{}}
   3 {:label "supervised-auto" :writes write-ops
      :auto #{:mandate/record}}})

(def default-phase 3)

(defn gate
  "Adjust a governor disposition for the rollout phase. Returns
  {:disposition kw :reason kw|nil}.

  - a governor HOLD always stays HOLD (compliance wins).
  - a write op not yet enabled in this phase -> HOLD (:phase-disabled).
  - a write op enabled but not auto-eligible -> ESCALATE (:phase-approval),
    even if the governor was clean.
  - `:fee/drawdown` is never auto-eligible at any phase, so it always
    escalates once the governor clears it (or holds if the governor
    doesn't)."
  [phase {:keys [op]} governor-disposition]
  (let [{:keys [writes auto]} (get phases phase (get phases default-phase))]
    (cond
      (= :hold governor-disposition)       {:disposition :hold :reason nil}
      (contains? read-ops op)              {:disposition governor-disposition :reason nil}
      (not (contains? writes op))          {:disposition :hold :reason :phase-disabled}
      (and (= :commit governor-disposition)
           (not (contains? auto op)))      {:disposition :escalate :reason :phase-approval}
      :else                                {:disposition governor-disposition :reason nil})))

(defn verdict->disposition
  "Map a FundManagementGovernor verdict to a base disposition before the
  phase gate."
  [verdict]
  (cond (:hard? verdict) :hold
        (:escalate? verdict) :escalate
        :else :commit))
