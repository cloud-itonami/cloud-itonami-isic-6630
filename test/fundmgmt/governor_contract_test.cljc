(ns fundmgmt.governor-contract-test
  "The governor contract as executable tests -- the fund-management-
  company analog of `cloud-itonami-isic-6499`'s `vcfund.governor-
  contract-test` / `cloud-itonami-isic-6430`'s `trustfund.governor-
  contract-test`. The single invariant under test:

    FundManager-LLM never draws a fee the FundManagementGovernor would
    reject, `:fee/drawdown` NEVER auto-commits at any phase,
    `:mandate/record` (no capital risk) MAY auto-commit when clean, and
    every decision (commit OR hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [fundmgmt.store :as store]
            [fundmgmt.operation :as op]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :gp-principal :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- record-mandate! [actor tid]
  (exec-op actor tid {:op :mandate/record :subject "fund"
                      :annual-fee-rate-cap 0.02 :effective-date "2026-01-01"} operator))

(defn- record-mandate-with-carry! [actor tid]
  (exec-op actor tid {:op :mandate/record :subject "fund"
                      :annual-fee-rate-cap 0.02 :carry-rate-cap 0.20
                      :effective-date "2026-01-01"} operator))

(defn- record-mandate-with-guideline-caps! [actor tid]
  (exec-op actor tid {:op :mandate/record :subject "fund"
                      :annual-fee-rate-cap 0.02 :carry-rate-cap 0.20
                      :sector-caps {"ai" 0.80} :stage-caps {"seed" 0.90}
                      :effective-date "2026-01-01"} operator))

(def clean-concentration-report
  {:total-invested-at-cost 2800000.0
   :by-sector {"ai" {:amount 2000000.0 :fraction 0.7142857142857143}}
   :by-investment-stage {"seed" {:amount 2300000.0 :fraction 0.8214285714285714}}})

(deftest clean-mandate-record-auto-commits
  (let [[db actor] (fresh)
        res (record-mandate! actor "t1")]
    (is (= :commit (get-in res [:state :disposition])))
    (is (close? 0.02 (:annual-fee-rate-cap (store/mandate db))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest invalid-rate-cap-is-held-and-unoverridable
  (testing "an out-of-range rate cap -> HOLD, settles immediately, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :mandate/record :subject "fund"
                                   :annual-fee-rate-cap 1.5 :effective-date "2026-01-01"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (not= :interrupted (:status res)))
      (is (some #{:invalid-rate-cap} (-> (store/ledger db) first :basis)))
      (is (nil? (store/mandate db)) "no mandate written"))))

(deftest fee-drawdown-with-no-mandate-is-held
  (testing "no mandate ever recorded -> HARD hold"
    (let [[db actor] (fresh)
          res (exec-op actor "t3" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                                   :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                        :years-elapsed 1 :accrued-amount 120000}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:mandate-missing} (-> (store/ledger db) first :basis)))
      (is (empty? (store/drawdown-history db))))))

(deftest fee-drawdown-exceeding-mandate-rate-is-held
  (testing "upstream-claimed rate (5%) exceeds the recorded 2% mandate cap -> HARD hold"
    (let [[db actor] (fresh)
          _ (record-mandate! actor "t4a")
          res (exec-op actor "t4" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                                   :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.05
                                                        :years-elapsed 1 :accrued-amount 300000}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:rate-exceeds-mandate} (-> (store/ledger db) last :basis)))
      (is (empty? (store/drawdown-history db))))))

(deftest fee-drawdown-with-mismatched-accrual-is-held
  (testing "upstream-claimed accrued_amount does not match independent recomputation -> HARD hold"
    (let [[db actor] (fresh)
          _ (record-mandate! actor "t5a")
          res (exec-op actor "t5" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                                   :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                        :years-elapsed 1 :accrued-amount 999999}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:accrual-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/drawdown-history db))))))

(deftest fee-drawdown-always-escalates-then-human-decides
  (testing "a clean, matching drawdown still ALWAYS interrupts for human approval -- actuation/draw-fee is never auto"
    (let [[db actor] (fresh)
          _ (record-mandate! actor "t6a")
          r1 (exec-op actor "t6" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                                  :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                       :years-elapsed 1 :accrued-amount 120000}} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, drawdown record drafted"
        (let [r2 (approve! actor "t6")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/drawdown-history db)))
              "one draft drawdown record")))))
  (testing "reject -> hold, nothing drawn"
    (let [[db actor] (fresh)
          _ (record-mandate! actor "t7a")
          _ (exec-op actor "t7" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                                 :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                      :years-elapsed 1 :accrued-amount 120000}} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t7" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/drawdown-history db)) "nothing drawn on reject"))))

(deftest fee-drawdown-double-draw-of-the-same-period-is-held
  (testing "a period already drawn -> HARD hold, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (record-mandate! actor "t8a")
          _ (exec-op actor "t8b" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                                  :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                       :years-elapsed 1 :accrued-amount 120000}} operator)
          _ (approve! actor "t8b")
          res (exec-op actor "t8" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                                   :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                        :years-elapsed 1 :accrued-amount 120000}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-draw} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/drawdown-history db))) "still only the one earlier draw"))))

(deftest invalid-carry-rate-cap-is-held-and-unoverridable
  (testing "an out-of-range carry rate cap -> HOLD, settles immediately, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t20" {:op :mandate/record :subject "fund"
                                    :annual-fee-rate-cap 0.02 :carry-rate-cap 1.5
                                    :effective-date "2026-01-01"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:invalid-carry-rate-cap} (-> (store/ledger db) first :basis)))
      (is (nil? (store/mandate db)) "no mandate written"))))

(deftest carry-distribute-with-fee-only-mandate-is-held
  (testing "a mandate exists but has no carry-rate-cap -> HARD hold, a fee-only mandate does not authorize carry"
    (let [[db actor] (fresh)
          _ (record-mandate! actor "t21a")
          res (exec-op actor "t21" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                                    :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                                   :gp-carry 1904000}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:carry-mandate-missing} (-> (store/ledger db) last :basis)))
      (is (empty? (store/carry-distribution-history db))))))

(deftest carry-distribute-exceeding-mandate-rate-is-held
  (testing "upstream-claimed carry rate (30%) exceeds the recorded 20% mandate cap -> HARD hold"
    (let [[db actor] (fresh)
          _ (record-mandate-with-carry! actor "t22a")
          res (exec-op actor "t22" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                                    :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.30
                                                                   :gp-carry 2856000}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:carry-rate-exceeds-mandate} (-> (store/ledger db) last :basis)))
      (is (empty? (store/carry-distribution-history db))))))

(deftest carry-distribute-with-mismatched-gp-carry-is-held
  (testing "upstream-claimed gp_carry does not match independent recomputation -> HARD hold"
    (let [[db actor] (fresh)
          _ (record-mandate-with-carry! actor "t23a")
          res (exec-op actor "t23" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                                    :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                                   :gp-carry 999999}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:carry-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/carry-distribution-history db))))))

(deftest carry-distribute-always-escalates-then-human-decides
  (testing "a clean, matching carry distribution still ALWAYS interrupts for human approval -- actuation/distribute-carry is never auto"
    (let [[db actor] (fresh)
          _ (record-mandate-with-carry! actor "t24a")
          r1 (exec-op actor "t24" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                                   :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                                  :gp-carry 1904000}} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, carry-distribution record drafted"
        (let [r2 (approve! actor "t24")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/carry-distribution-history db)))
              "one draft carry-distribution record")))))
  (testing "reject -> hold, nothing distributed"
    (let [[db actor] (fresh)
          _ (record-mandate-with-carry! actor "t25a")
          _ (exec-op actor "t25" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                                  :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                                 :gp-carry 1904000}} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t25" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/carry-distribution-history db)) "nothing distributed on reject"))))

(deftest carry-distribute-double-distribution-of-the-same-commitment-is-held
  (testing "a commitment already distributed -> HARD hold, even though the figures match cleanly"
    (let [[db actor] (fresh)
          _ (record-mandate-with-carry! actor "t26a")
          _ (exec-op actor "t26b" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                                   :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                                  :gp-carry 1904000}} operator)
          _ (approve! actor "t26b")
          res (exec-op actor "t26" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                                    :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                                   :gp-carry 1904000}} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:double-distribution} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/carry-distribution-history db))) "still only the one earlier distribution"))))

(deftest guideline-disclose-with-no-caps-mandate-is-held
  (testing "a mandate exists but has no sector-caps/stage-caps -> HARD hold, nothing to disclose compliance against"
    (let [[db actor] (fresh)
          _ (record-mandate-with-carry! actor "t30a")
          res (exec-op actor "t30" {:op :guideline/disclose :subject "fund"
                                    :upstream-concentration-report clean-concentration-report
                                    :as-of-date "2026-07-06"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:guideline-mandate-missing} (-> (store/ledger db) last :basis)))
      (is (empty? (store/guideline-disclosure-history db))))))

(deftest guideline-disclose-exceeding-mandate-cap-is-held
  (testing "upstream-reported ai concentration (95%) exceeds the recorded 80% sector cap -> HARD hold"
    (let [[db actor] (fresh)
          _ (record-mandate-with-guideline-caps! actor "t31a")
          exceeded-report (assoc-in clean-concentration-report [:by-sector "ai" :fraction] 0.95)
          res (exec-op actor "t31" {:op :guideline/disclose :subject "fund"
                                    :upstream-concentration-report exceeded-report
                                    :as-of-date "2026-07-06"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:concentration-limit-exceeded} (-> (store/ledger db) last :basis)))
      (is (empty? (store/guideline-disclosure-history db))))))

(deftest guideline-disclose-always-escalates-then-human-decides
  (testing "a clean, within-cap disclosure still ALWAYS interrupts for human approval -- actuation/disclose-guidelines is never auto"
    (let [[db actor] (fresh)
          _ (record-mandate-with-guideline-caps! actor "t32a")
          r1 (exec-op actor "t32" {:op :guideline/disclose :subject "fund"
                                   :upstream-concentration-report clean-concentration-report
                                   :as-of-date "2026-07-06"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, guideline-disclosure record drafted"
        (let [r2 (approve! actor "t32")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (= 1 (count (store/guideline-disclosure-history db)))
              "one draft guideline-disclosure record")))))
  (testing "reject -> hold, nothing disclosed"
    (let [[db actor] (fresh)
          _ (record-mandate-with-guideline-caps! actor "t33a")
          _ (exec-op actor "t33" {:op :guideline/disclose :subject "fund"
                                  :upstream-concentration-report clean-concentration-report
                                  :as-of-date "2026-07-06"} operator)
          r2 (g/run* actor {:approval {:status :rejected :by "op-1"}}
                     {:thread-id "t33" :resume? true})]
      (is (= :hold (get-in r2 [:state :disposition])))
      (is (empty? (store/guideline-disclosure-history db)) "nothing disclosed on reject"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (record-mandate! actor "a")
      (exec-op actor "b" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                          :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                               :years-elapsed 1 :accrued-amount 999999}} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
