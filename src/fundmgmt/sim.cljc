(ns fundmgmt.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean investment-mandate
  recording (no capital risk; auto-commits) -> management-fee DRAWDOWN
  off an UPSTREAM `cloud-itonami-isic-6499` (`vcfund`) fee-accrual
  report (always escalates -- a real cash movement) -> human approval
  -> commit, then shows four HARD holds (a fee drawdown attempted with
  no mandate on file, a drawdown whose upstream-claimed rate exceeds the
  recorded mandate's cap, a drawdown whose upstream-claimed accrued
  amount does not match this company's own independent recomputation,
  and a double-draw of the same period) that never reach a human at
  all, and prints the audit ledger + the draft mandate/drawdown records.

  The `upstream-fee-report` fixtures below are literal EDN, hand-shaped
  to match what an operator would read off `vcfund.nav/fund-nav-report`
  (in the separate `cloud-itonami-isic-6499` repo) -- this repo has NO
  code dependency on that one; see `fundmgmt.governor`'s docstring for
  the documented data contract."
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
    (println "== mandate/record (annual-fee-rate-cap 2%; no capital risk; auto-commits) ==")
    (println (exec! actor "t1" {:op :mandate/record :subject "fund"
                                :annual-fee-rate-cap 0.02 :effective-date "2026-01-01"} operator))

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

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft mandate records ==")
    (doseq [r (store/mandate-history db)] (println r))

    (println "== draft fee-drawdown records ==")
    (doseq [r (store/drawdown-history db)] (println r))))
