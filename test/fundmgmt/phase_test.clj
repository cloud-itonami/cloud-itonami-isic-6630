(ns fundmgmt.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:fee/drawdown`/`:carry/distribute` may never be a member
  of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [fundmgmt.phase :as phase]))

(deftest fee-drawdown-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-draws a management fee"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :fee/drawdown))
          (str "phase " n " must not auto-commit :fee/drawdown")))))

(deftest carry-distribute-never-auto-at-any-phase
  (testing "structural invariant: no phase auto-distributes GP carry"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :carry/distribute))
          (str "phase " n " must not auto-commit :carry/distribute")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-the-no-capital-risk-op
  (testing ":mandate/record moves no capital -- auto-eligible"
    (is (= #{:mandate/record} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :mandate/record} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :fee/drawdown} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :carry/distribute} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :mandate/record} :commit)))))
