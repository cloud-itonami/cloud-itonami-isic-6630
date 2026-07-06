(ns fundmgmt.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean investment-mandate
  recording (no capital risk; auto-commits) -> management-fee DRAWDOWN
  off an UPSTREAM `cloud-itonami-isic-6499` (`vcfund`) fee-accrual
  report -> GP carry DISTRIBUTION off an upstream exit-distribution fact
  (both always escalate -- real cash movements) -> human approval ->
  commit, then shows eight HARD holds (a fee drawdown attempted with no
  mandate on file, a drawdown whose upstream-claimed rate exceeds the
  recorded mandate's cap, a drawdown whose upstream-claimed accrued
  amount does not match this company's own independent recomputation, a
  double-draw of the same period, a carry distribution attempted with
  no carry-rate-cap mandate on file, a distribution whose upstream-
  claimed carry rate exceeds the recorded cap, a distribution whose
  upstream-claimed gp_carry does not match this company's own
  independent recomputation, and a double-distribution of the same
  commitment) that never reach a human at all, and prints the audit
  ledger + the draft mandate/drawdown/carry-distribution records.

  The `upstream-fee-report`/`upstream-distribution-report` fixtures
  below are literal EDN, hand-shaped to match what an operator would
  read off `vcfund.nav/fund-nav-report`/`vcfund.registry/distribute-
  waterfall` (in the separate `cloud-itonami-isic-6499` repo) -- this
  repo has NO code dependency on that one; see `fundmgmt.governor`'s
  docstring for the documented data contract."
  (:require [langgraph.graph :as g]
            [fundmgmt.store :as store]
            [fundmgmt.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :gp-principal :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== mandate/record (annual-fee-rate-cap 2%, carry-rate-cap 20%; no capital risk; auto-commits) ==")
    (println (exec! actor "t1" {:op :mandate/record :subject "fund"
                                :annual-fee-rate-cap 0.02 :carry-rate-cap 0.20
                                :effective-date "2026-01-01"} operator))

    (println "== fee/drawdown 2026-Q3 (upstream vcfund report: 6,000,000 basis @ 2% for 1y = 120,000; always escalates -- actuation/draw-fee) ==")
    (let [r (exec! actor "t2" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                              :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                    :years-elapsed 1 :accrued-amount 120000}} operator)]
      (println r)
      (println "-- human GP principal approves --")
      (println (approve! actor "t2")))

    (println "== fee/drawdown 2026-Q4 with NO mandate on file (fresh store -> HARD hold, never reaches a human) ==")
    (let [db2 (store/seed-db)
          actor2 (op/build db2)]
      (println (exec! actor2 "t3" {:op :fee/drawdown :subject "fund" :period "2026-Q4"
                                   :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                        :years-elapsed 1 :accrued-amount 120000}} operator)))

    (println "== fee/drawdown 2026-Q4 whose upstream-claimed rate (5%) exceeds the recorded 2% mandate cap -> HARD hold ==")
    (println (exec! actor "t4" {:op :fee/drawdown :subject "fund" :period "2026-Q4"
                               :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.05
                                                     :years-elapsed 1 :accrued-amount 300000}} operator))

    (println "== fee/drawdown 2026-Q4 whose upstream-claimed accrued_amount does not match independent recomputation -> HARD hold ==")
    (println (exec! actor "t5" {:op :fee/drawdown :subject "fund" :period "2026-Q4"
                               :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                     :years-elapsed 1 :accrued-amount 999999}} operator))

    (println "== fee/drawdown 2026-Q3 AGAIN (double-draw of an already-drawn period -> HARD hold) ==")
    (println (exec! actor "t6" {:op :fee/drawdown :subject "fund" :period "2026-Q3"
                               :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                                     :years-elapsed 1 :accrued-amount 120000}} operator))

    (println "== carry/distribute USA-00000000 (upstream vcfund exit-distribution: after-preferred-profit=9,520,000 @ 20% carry = 1,904,000, the same deal-by-deal waterfall example from cloud-itonami-isic-6499's own demo; always escalates -- actuation/distribute-carry) ==")
    (let [r (exec! actor "t7" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                              :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                             :gp-carry 1904000}} operator)]
      (println r)
      (println "-- human GP principal approves --")
      (println (approve! actor "t7")))

    (println "== carry/distribute USA-00000001 with NO carry-rate-cap mandate on file (fresh store -> HARD hold, never reaches a human) ==")
    (let [db3 (store/seed-db)
          actor3 (op/build db3)]
      (println (exec! actor3 "t8" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000001"
                                   :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                                  :gp-carry 1904000}} operator)))

    (println "== carry/distribute USA-00000001 whose upstream-claimed carry rate (30%) exceeds the recorded 20% mandate cap -> HARD hold ==")
    (println (exec! actor "t9" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000001"
                               :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.30
                                                              :gp-carry 2856000}} operator))

    (println "== carry/distribute USA-00000001 whose upstream-claimed gp_carry does not match independent recomputation -> HARD hold ==")
    (println (exec! actor "t10" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000001"
                                :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                               :gp-carry 999999}} operator))

    (println "== carry/distribute USA-00000000 AGAIN (double-distribution of an already-distributed commitment -> HARD hold) ==")
    (println (exec! actor "t11" {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
                                :upstream-distribution-report {:after-preferred-profit 9520000 :carry-rate 0.20
                                                               :gp-carry 1904000}} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft mandate records ==")
    (doseq [r (store/mandate-history db)] (println r))

    (println "== draft fee-drawdown records ==")
    (doseq [r (store/drawdown-history db)] (println r))

    (println "== draft carry-distribution records ==")
    (doseq [r (store/carry-distribution-history db)] (println r))))
