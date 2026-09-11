(ns fundmgmt.kernels.gate-test
  "The safety kernel's executable spec, three ways:

  1. battery lock — the kernel's own in-subset battery must pass
     case-for-case (`battery-case-count` == `(battery-pass-count)`),
     so a silently dropped case can't survive review.
  2. parity matrix — the kernel's phase core is compared against an
     independent reference copy of the ORIGINAL set-based cond logic
     over the FULL input space (all phases incl. out-of-range, all op
     codes incl. unknown, all governor dispositions). The façade
     delegates, so this is the guard that delegation didn't change
     semantics.
  3. governor boundary — the confidence floor boundary and the
     fail-closed treatment of out-of-range confidence, exercised
     through the real `fundmgmt.governor/check` façade."
  (:require [clojure.test :refer [deftest is testing]]
            [fundmgmt.governor :as governor]
            [fundmgmt.kernels.gate :as gate]))

(deftest battery-lock
  (is (= gate/battery-case-count (gate/battery-pass-count))
      "every battery case must pass; update battery-case-count only when adding cases"))

(deftest confidence-floor-pinned-to-facade-constant
  (is (= gate/confidence-floor-x100
         (Math/round (* 100.0 governor/confidence-floor)))
      "the façade's documented 0.6 and the kernel's deciding 60 must not drift"))

(deftest frac-unit-pinned
  (is (= 1000000 gate/frac-one-x1e6)
      "the kernel's micro-unit scale must match the façade's frac->x1e6 bridge"))

;; ---------------------------------------------------------------
;; Independent oracle for the parity matrix: the pre-kernel phase
;; logic (sets + cond) restated over wire codes, PLUS the kernel's
;; fail-closed contract for out-of-range phases (no writes at all).
;; The original façade normalized an unknown phase to default-phase 3
;; BEFORE this logic and still does — so out-of-range rows here pin
;; the kernel's own contract, not a façade behavior change. This
;; repo's `read-ops` is empty (the façade never emits op 0), but the
;; kernel's wire contract still passes reads through — op 0 rows pin
;; that kernel-level contract. Phase 2 is the reserved duplicate of
;; phase 1 (only :mandate/record writes) — the matrix pins that too.

(def ^:private ref-read-ops #{0})
(def ^:private ref-phases
  {0 {:writes #{}          :auto #{}}
   1 {:writes #{1}         :auto #{}}
   2 {:writes #{1}         :auto #{}}
   3 {:writes #{1 2 3 4}   :auto #{1}}})

(defn- ref-gate [phase op gov]
  (let [{:keys [writes auto]} (get ref-phases phase {:writes #{} :auto #{}})]
    (cond
      (= gov 2)                        {:d 2 :r 0}
      (contains? ref-read-ops op)      {:d gov :r 0}
      (not (contains? writes op))      {:d 2 :r 1}
      (and (= gov 0)
           (not (contains? auto op)))  {:d 1 :r 2}
      :else                            {:d gov :r 0})))

(deftest phase-parity-matrix
  (testing "kernel == reference over the full input space (162 combos)"
    (doseq [phase [-1 0 1 2 3 4 7 100 -99]
            op    [0 1 2 3 4 5]
            gov   [0 1 2]]
      (let [expected (ref-gate phase op gov)]
        (is (= (:d expected) (gate/phase-disposition phase op gov))
            (str "disposition mismatch at phase=" phase " op=" op " gov=" gov))
        (is (= (:r expected) (gate/phase-reason phase op gov))
            (str "reason mismatch at phase=" phase " op=" op " gov=" gov))))))

(deftest actuation-ops-auto-enabled-nowhere
  (testing "ops 2/3/4 (:fee/drawdown, :carry/distribute,
            :guideline/disclose) are auto-enabled at NO phase — kernel
            restates the phase table's permanent structural invariant"
    (doseq [phase [-1 0 1 2 3 4 7]
            op    [2 3 4]]
      (is (= 0 (gate/op-auto-enabled phase op))))))

;; ---------------------------------------------------------------
;; Governor boundary through the real façade. op :mandate/record with
;; a valid in-range cap passes both mandate-proposal checks and
;; touches no other check (all are scoped to other ops), so the
;; verdict is decided purely by confidence/actuation — nil store is
;; safe.

(defn- verdict [proposal]
  (governor/check {:op :mandate/record :subject "mandate-x"} {}
                  (merge {:value {:annual-fee-rate-cap 0.02}} proposal)
                  nil))

(deftest confidence-floor-boundary
  (testing "0.59 escalates, 0.60 clears (kernel decides at integer x100)"
    (is (true?  (:escalate? (verdict {:confidence 0.59}))))
    (is (false? (:ok? (verdict {:confidence 0.59}))))
    (is (true?  (:ok? (verdict {:confidence 0.6}))))
    (is (false? (:escalate? (verdict {:confidence 0.6}))))))

(deftest out-of-range-confidence-fails-closed
  (testing "an advisor reporting impossible confidence gets MORE scrutiny,
            not auto-commit (kernel is deliberately stricter than the old
            inline `(< conf floor)` here)"
    (is (true? (:escalate? (verdict {:confidence 1.5}))))
    (is (false? (:ok? (verdict {:confidence 1.5}))))
    (is (true? (:escalate? (verdict {:confidence -0.2}))))))

(deftest actuation-still-escalates-and-hard-still-wins
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/draw-fee}))))
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/distribute-carry}))))
  (is (true? (:escalate? (verdict {:confidence 0.99 :stake :actuation/disclose-guidelines}))))
  (testing "a hard violation dominates actuation escalation"
    (let [v (governor/check {:op :mandate/record :subject "mandate-x"} {}
                            {:confidence 0.99 :stake :actuation/draw-fee
                             :value {:annual-fee-rate-cap 1.5}}
                            nil)]
      (is (true? (:hard? v)))
      (is (false? (:escalate? v)))
      (is (some #{:invalid-rate-cap} (mapv :rule (:violations v)))))))

(deftest rate-cap-range-boundary-through-facade
  (testing "0.0 and 1.0 are valid caps (inclusive range, kernel decides
            in micro-units); just outside holds"
    (is (false? (:hard? (verdict {:confidence 0.9 :value {:annual-fee-rate-cap 0.0}}))))
    (is (false? (:hard? (verdict {:confidence 0.9 :value {:annual-fee-rate-cap 1.0}}))))
    (is (true?  (:hard? (verdict {:confidence 0.9 :value {:annual-fee-rate-cap -0.01}}))))
    (is (true?  (:hard? (verdict {:confidence 0.9 :value {:annual-fee-rate-cap 1.01}}))))))
