(ns fundmgmt.registry-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest mandate-accepts-an-optional-carry-rate-cap
  (let [result (r/register-mandate 0.02 0.20 "2026-01-01" 0)]
    (is (close? 0.02 (get-in result ["record" "annual_fee_rate_cap"])))
    (is (close? 0.20 (get-in result ["record" "carry_rate_cap"])))))

(deftest mandate-3-arity-omits-carry-rate-cap-entirely
  (testing "backward compatible: the 3-arity form never adds a carry_rate_cap key at all"
    (let [result (r/register-mandate 0.02 "2026-01-01" 0)]
      (is (not (contains? (get result "record") "carry_rate_cap"))))))

(deftest mandate-carry-rate-cap-validation-rules
  (is (thrown? Exception (r/register-mandate 0.02 -0.01 "2026-01-01" 0)))
  (is (thrown? Exception (r/register-mandate 0.02 1.5 "2026-01-01" 0))))

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

;; ----------------------------- carry-accrued -----------------------------

(deftest carry-accrued-splits-post-preferred-profit-by-rate
  (is (close? 1904000.0 (r/carry-accrued 9520000 0.20))))

(deftest carry-accrued-validation-rules
  (is (thrown? Exception (r/carry-accrued -1 0.20)))
  (is (thrown? Exception (r/carry-accrued 1 -0.01)))
  (is (thrown? Exception (r/carry-accrued 1 1.5))))

;; ----------------------------- carry-distribution -----------------------------

(deftest carry-distribution-is-a-draft-not-a-real-payment
  (let [result (r/register-carry-distribution "USA-00000000" 9520000 0.20 1904000 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest carry-distribution-assigns-distribution-number
  (let [result (r/register-carry-distribution "USA-00000000" 9520000 0.20 1904000 7)]
    (is (= (get result "distribution_number") "CARRY-000007"))
    (is (= (get-in result ["record" "commitment_number"]) "USA-00000000"))
    (is (close? 1904000.0 (get-in result ["record" "gp_carry"])))
    (is (= (get-in result ["record" "kind"]) "carry-distribution-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest carry-distribution-validation-rules
  (is (thrown? Exception (r/register-carry-distribution "" 9520000 0.20 1904000 0)))
  (is (thrown? Exception (r/register-carry-distribution "USA-00000000" -1 0.20 1904000 0)))
  (is (thrown? Exception (r/register-carry-distribution "USA-00000000" 9520000 1.5 1904000 0)))
  (is (thrown? Exception (r/register-carry-distribution "USA-00000000" 9520000 0.20 -1 0)))
  (is (thrown? Exception (r/register-carry-distribution "USA-00000000" 9520000 0.20 1904000 -1))))

(deftest carry-distribution-history-is-append-only
  (let [c1 (r/register-carry-distribution "USA-00000000" 9520000 0.20 1904000 0)
        hist (r/append [] c1)
        c2 (r/register-carry-distribution "USA-00000001" 5000000 0.20 1000000 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "USA-00000000" (get-in hist2 [0 "commitment_number"])))
    (is (= "USA-00000001" (get-in hist2 [1 "commitment_number"])))))
