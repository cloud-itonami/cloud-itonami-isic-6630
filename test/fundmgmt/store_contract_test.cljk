(ns fundmgmt.store-contract-test
  "The Store contract, run against BOTH backends -- proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract, the
  same pattern `cloud-itonami-isic-6499`'s `vcfund.store-contract-test`
  / `cloud-itonami-isic-6430`'s `trustfund.store-contract-test` use."
  (:require [clojure.test :refer [deftest is testing]]
            [fundmgmt.store :as store]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-store)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (nil? (store/mandate s)))
      (is (= [] (store/ledger s)))
      (is (= [] (store/mandate-history s)))
      (is (= [] (store/drawdown-history s)))
      (is (= [] (store/carry-distribution-history s)))
      (is (= [] (store/guideline-disclosure-history s)))
      (is (zero? (store/mandate-sequence s)))
      (is (zero? (store/drawdown-sequence s)))
      (is (zero? (store/carry-distribution-sequence s)))
      (is (zero? (store/guideline-disclosure-sequence s)))
      (is (false? (store/period-already-drawn? s "2026-Q3")))
      (is (false? (store/commitment-already-distributed? s "USA-00000000"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "mandate recording drafts a mandate record and becomes the current mandate"
        (store/commit-record! s {:effect :mandate/recorded
                                 :payload {:annual-fee-rate-cap 0.02 :carry-rate-cap 0.20
                                          :sector-caps {"ai" 0.80} :stage-caps {"seed" 0.90}
                                          :effective-date "2026-01-01"}})
        (is (= 1 (count (store/mandate-history s))))
        (is (= "MANDATE-000000" (get (first (store/mandate-history s)) "record_id")))
        (is (close? 0.02 (:annual-fee-rate-cap (store/mandate s))))
        (is (close? 0.20 (:carry-rate-cap (store/mandate s))))
        (is (close? 0.80 (get-in (store/mandate s) [:sector-caps "ai"])))
        (is (close? 0.90 (get-in (store/mandate s) [:stage-caps "seed"]))))
      (testing "fee drawdown independently recomputes the accrual and drafts the record"
        (store/commit-record! s {:effect :fee/drawn
                                 :payload {:period "2026-Q3" :fee-basis 6000000
                                          :annual-fee-rate 0.02 :years-elapsed 1}})
        (is (= 1 (count (store/drawdown-history s))))
        (is (= "DRAWDOWN-000000" (get (first (store/drawdown-history s)) "record_id")))
        (is (close? 120000.0 (get (first (store/drawdown-history s)) "accrued_amount")))
        (is (true? (store/period-already-drawn? s "2026-Q3")))
        (is (false? (store/period-already-drawn? s "2026-Q4"))))
      (testing "carry distribution independently recomputes the accrual and drafts the record"
        (store/commit-record! s {:effect :carry/distributed
                                 :payload {:commitment-number "USA-00000000"
                                          :after-preferred-profit 9520000 :carry-rate 0.20}})
        (is (= 1 (count (store/carry-distribution-history s))))
        (is (= "CARRY-000000" (get (first (store/carry-distribution-history s)) "record_id")))
        (is (close? 1904000.0 (get (first (store/carry-distribution-history s)) "gp_carry")))
        (is (true? (store/commitment-already-distributed? s "USA-00000000")))
        (is (false? (store/commitment-already-distributed? s "USA-00000001"))))
      (testing "guideline disclosure carries the upstream concentration breakdown through into a draft record"
        (store/commit-record! s {:effect :guideline/disclosed
                                 :payload {:total-invested-at-cost 2800000.0
                                          :by-sector {"ai" {:amount 2000000.0 :fraction 0.7142857142857143}}
                                          :by-investment-stage {"seed" {:amount 2300000.0 :fraction 0.8214285714285714}}
                                          :as-of-date "2026-07-06"}})
        (is (= 1 (count (store/guideline-disclosure-history s))))
        (is (= "GUIDELINE-000000" (get (first (store/guideline-disclosure-history s)) "record_id")))
        (is (close? 2800000.0 (get (first (store/guideline-disclosure-history s)) "total_invested_at_cost")))
        (is (= 1 (store/guideline-disclosure-sequence s))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))
