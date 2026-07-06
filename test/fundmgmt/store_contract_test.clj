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
      (is (zero? (store/mandate-sequence s)))
      (is (zero? (store/drawdown-sequence s)))
      (is (false? (store/period-already-drawn? s "2026-Q3"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "mandate recording drafts a mandate record and becomes the current mandate"
        (store/commit-record! s {:effect :mandate/recorded
                                 :payload {:annual-fee-rate-cap 0.02 :effective-date "2026-01-01"}})
        (is (= 1 (count (store/mandate-history s))))
        (is (= "MANDATE-000000" (get (first (store/mandate-history s)) "record_id")))
        (is (close? 0.02 (:annual-fee-rate-cap (store/mandate s)))))
      (testing "fee drawdown independently recomputes the accrual and drafts the record"
        (store/commit-record! s {:effect :fee/drawn
                                 :payload {:period "2026-Q3" :fee-basis 6000000
                                          :annual-fee-rate 0.02 :years-elapsed 1}})
        (is (= 1 (count (store/drawdown-history s))))
        (is (= "DRAWDOWN-000000" (get (first (store/drawdown-history s)) "record_id")))
        (is (close? 120000.0 (get (first (store/drawdown-history s)) "accrued_amount")))
        (is (true? (store/period-already-drawn? s "2026-Q3")))
        (is (false? (store/period-already-drawn? s "2026-Q4"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))
