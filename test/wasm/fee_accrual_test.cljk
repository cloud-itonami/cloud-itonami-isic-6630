(ns wasm.fee-accrual-test
  "Hosts wasm/fee_accrual.wasm (compiled from wasm/fee_accrual.kotoba, see
  wasm/README.md) via kototama.tender -- proves fundmgmt.registry/fee-
  accrued's independent-recompute cross-check (fundmgmt.governor's
  :accrual-mismatch HARD violation) runs as a real WASM guest, not just as
  JVM Clojure.

  ABI: main is 0-arity (kotoba wasm emit rejects a parameterized main --
  :main-arity); the four real i32 inputs are written into the guest's
  exported linear memory at fixed offsets before calling main() -- see
  wasm/fee_accrual.kotoba's ns-adjacent header comment for the offset
  layout and the fixed-point (rate-bps / years-x100) scaling rationale."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [kototama.contract :as contract]
            [kototama.tender :as tender]))

(defn- wasm-bytes []
  (.readAllBytes (io/input-stream (io/file "wasm/fee_accrual.wasm"))))

(defn- run-fee-accrual-matches?
  [fee-basis rate-bps years-x100 claimed-accrued-amount]
  (let [instance (tender/instantiate (wasm-bytes) [] (contract/host-caps {}))
        memory (.memory instance)]
    (.writeI32 memory 0 fee-basis)
    (.writeI32 memory 4 rate-bps)
    (.writeI32 memory 8 years-x100)
    (.writeI32 memory 12 claimed-accrued-amount)
    (tender/call-main instance)))

(deftest fee-accrual-wasm-approves-exact-match
  (testing "recomputed accrual (fee-basis=50000, rate-bps=200 [2.00%], years-x100=150 [1.50yr]) = 1500 cents, matches the claim -> approves"
    ;; hand-verified: 50000 * 0.02 * 1.5 = 1500 cents; fixed-point:
    ;; (50000 * 200 * 150) / 1000000 = 1500000000 / 1000000 = 1500
    (is (= 1 (run-fee-accrual-matches? 50000 200 150 1500)))))

(deftest fee-accrual-wasm-rejects-clear-mismatch
  (testing "claimed accrued amount double the true recompute (3000 vs. 1500) -> rejects"
    (is (= 0 (run-fee-accrual-matches? 50000 200 150 3000)))))

(deftest fee-accrual-wasm-handles-zero-basis
  (testing "zero fee-basis -> recomputed accrual is 0 (not a crash); matches a 0 claim, rejects a nonzero claim"
    (is (= 1 (run-fee-accrual-matches? 0 200 150 0)))
    (is (= 0 (run-fee-accrual-matches? 0 200 150 1)))))

(deftest fee-accrual-wasm-approves-nontrivial-scaling
  (testing "recomputed accrual (fee-basis=30000, rate-bps=250 [2.50%], years-x100=200 [2.00yr]) = 1500 cents, matches the claim -> approves"
    ;; hand-verified: 30000 cents ($300.00) * 0.025 * 2.0 = 15.00 dollars =
    ;; 1500 cents; fixed-point: (30000 * 250 * 200) / 1000000 =
    ;; 1500000000 / 1000000 = 1500 (intermediate product 1.5e9 stays well
    ;; under the i32 ceiling ~2.147e9 -- see wasm/README.md's overflow note)
    (is (= 1 (run-fee-accrual-matches? 30000 250 200 1500)))))
