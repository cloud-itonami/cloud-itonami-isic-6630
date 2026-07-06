(ns fundmgmt.registry-test
  (:require [clojure.test :refer [deftest is]]
            [fundmgmt.registry :as r]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(deftest mandate-is-a-draft-not-a-real-authorization
  (let [result (r/register-mandate 0.02 "2026-01-01" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest mandate-assigns-mandate-number
  (let [result (r/register-mandate 0.02 "2026-01-01" 7)]
    (is (= (get result "mandate_number") "MANDATE-000007"))
    (is (close? 0.02 (get-in result ["record" "annual_fee_rate_cap"])))
    (is (= (get-in result ["record" "kind"]) "mandate-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest mandate-validation-rules
  (is (thrown? Exception (r/register-mandate -0.01 "2026-01-01" 0)))
  (is (thrown? Exception (r/register-mandate 1.5 "2026-01-01" 0)))
  (is (thrown? Exception (r/register-mandate 0.02 "" 0)))
  (is (thrown? Exception (r/register-mandate 0.02 "2026-01-01" -1))))

;; ----------------------------- fee-accrued -----------------------------

(deftest fee-accrued-is-a-flat-annual-rate-on-the-basis
  (is (close? 120000.0 (r/fee-accrued 6000000 0.02 1))))

(deftest fee-accrued-validation-rules
  (is (thrown? Exception (r/fee-accrued -1 0.02 1)))
  (is (thrown? Exception (r/fee-accrued 1 -0.02 1)))
  (is (thrown? Exception (r/fee-accrued 1 0.02 -1))))

;; ----------------------------- fee-drawdown -----------------------------

(deftest fee-drawdown-is-a-draft-not-a-real-payment
  (let [result (r/register-fee-drawdown "2026-Q3" 6000000 0.02 1 120000 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest fee-drawdown-assigns-drawdown-number
  (let [result (r/register-fee-drawdown "2026-Q3" 6000000 0.02 1 120000 7)]
    (is (= (get result "drawdown_number") "DRAWDOWN-000007"))
    (is (= (get-in result ["record" "period"]) "2026-Q3"))
    (is (close? 120000.0 (get-in result ["record" "accrued_amount"])))
    (is (= (get-in result ["record" "kind"]) "fee-drawdown-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest fee-drawdown-validation-rules
  (is (thrown? Exception (r/register-fee-drawdown "" 6000000 0.02 1 120000 0)))
  (is (thrown? Exception (r/register-fee-drawdown "2026-Q3" -1 0.02 1 120000 0)))
  (is (thrown? Exception (r/register-fee-drawdown "2026-Q3" 6000000 -0.02 1 120000 0)))
  (is (thrown? Exception (r/register-fee-drawdown "2026-Q3" 6000000 0.02 -1 120000 0)))
  (is (thrown? Exception (r/register-fee-drawdown "2026-Q3" 6000000 0.02 1 -1 0)))
  (is (thrown? Exception (r/register-fee-drawdown "2026-Q3" 6000000 0.02 1 120000 -1))))

(deftest drawdown-history-is-append-only
  (let [d1 (r/register-fee-drawdown "2026-Q3" 6000000 0.02 1 120000 0)
        hist (r/append [] d1)
        d2 (r/register-fee-drawdown "2026-Q4" 6000000 0.02 0.25 30000 1)
        hist2 (r/append hist d2)]
    (is (= 2 (count hist2)))
    (is (= "2026-Q3" (get-in hist2 [0 "period"])))
    (is (= "2026-Q4" (get-in hist2 [1 "period"])))))
