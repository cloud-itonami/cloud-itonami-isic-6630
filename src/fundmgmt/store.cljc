(ns fundmgmt.store
  "SSoT for the fund-management-company actor, behind a `Store` protocol
  so the backend is a swap, not a rewrite -- the same seam
  `cloud-itonami-isic-6499`'s `vcfund.store` and `cloud-itonami-isic-6430`'s
  `trustfund.store` use:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert).

  Both implement the same protocol and pass the same contract
  (test/fundmgmt/store_contract_test.clj). The ledger stays append-only
  on every backend: 'what mandate rate ceiling was recorded, what fee was
  actually drawn for which period, off which upstream fee-accrual report,
  approved by whom' is always a query over an immutable log."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [fundmgmt.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (mandate [s] "the CURRENT recorded investment mandate ({:annual-fee-rate-cap :effective-date}), or nil")
  (ledger [s])
  (mandate-history [s] "the append-only mandate-record history (fundmgmt.registry drafts)")
  (drawdown-history [s] "the append-only fee-drawdown history (fundmgmt.registry drafts)")
  (mandate-sequence [s] "next mandate-number sequence")
  (drawdown-sequence [s] "next drawdown-number sequence")
  (period-already-drawn? [s period] "has a fee already been drawn for this period?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact"))

;; ----------------------------- shared commit logic -----------------------------

(defn- record-mandate!
  "Backend-agnostic `:mandate/record` -- drafts the mandate record and
  returns {:result ..} for the caller to persist (append-only history;
  the CURRENT mandate advances to this one)."
  [s {:keys [annual-fee-rate-cap effective-date]}]
  (let [seq-n (mandate-sequence s)
        result (registry/register-mandate annual-fee-rate-cap effective-date seq-n)]
    {:result result}))

(defn- draw-fee!
  "Backend-agnostic `:fee/drawdown` -- INDEPENDENTLY recomputes the
  accrual from the upstream report's own fee-basis/rate/years-elapsed
  (`fundmgmt.registry/fee-accrued`, never trusting the upstream's
  `:accrued-amount` claim as authoritative on its own -- `fundmgmt.
  governor` compares the two before this is ever allowed to commit),
  drafts the drawdown record, and returns {:result ..} for the caller to
  persist."
  [s {:keys [period fee-basis annual-fee-rate years-elapsed]}]
  (let [accrued-amount (registry/fee-accrued fee-basis annual-fee-rate years-elapsed)
        seq-n (drawdown-sequence s)
        result (registry/register-fee-drawdown period fee-basis annual-fee-rate years-elapsed accrued-amount seq-n)]
    {:result result}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (mandate [_] (:mandate @a))
  (ledger [_] (:ledger @a))
  (mandate-history [_] (:mandate-history @a))
  (drawdown-history [_] (:drawdown-history @a))
  (mandate-sequence [_] (count (:mandate-history @a)))
  (drawdown-sequence [_] (count (:drawdown-history @a)))
  (period-already-drawn? [_ period] (boolean (some #(= period (get % "period")) (:drawdown-history @a))))
  (commit-record! [s {:keys [effect payload]}]
    (case effect
      :mandate/recorded
      (let [{:keys [result]} (record-mandate! s payload)]
        (swap! a (fn [state]
                   (-> state
                       (assoc :mandate {:annual-fee-rate-cap (double (:annual-fee-rate-cap payload))
                                        :effective-date (:effective-date payload)})
                       (update :mandate-history registry/append result))))
        result)

      :fee/drawn
      (let [{:keys [result]} (draw-fee! s payload)]
        (swap! a update :drawdown-history registry/append result)
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact))

(defn seed-db
  "An empty MemStore -- no mandate recorded yet, the deterministic default."
  []
  (->MemStore (atom {:mandate nil :ledger [] :mandate-history [] :drawdown-history []})))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  The same convention `vcfund.store`/`trustfund.store` use -- compound
  values are stored as EDN strings."
  {:mandate/current   {:db/unique :db.unique/identity}
   :ledger/seq        {:db/unique :db.unique/identity}
   :mandate-history/seq {:db/unique :db.unique/identity}
   :drawdown-history/seq {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defrecord DatomicStore [conn]
  Store
  (mandate [_]
    (dec* (d/q '[:find ?p . :where [?e :mandate/current true] [?e :mandate/payload ?p]] (d/db conn))))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (mandate-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :mandate-history/seq ?s] [?e :mandate-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (drawdown-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :drawdown-history/seq ?s] [?e :drawdown-history/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (mandate-sequence [s] (count (mandate-history s)))
  (drawdown-sequence [s] (count (drawdown-history s)))
  (period-already-drawn? [s period] (boolean (some #(= period (get % "period")) (drawdown-history s))))
  (commit-record! [s {:keys [effect payload]}]
    (case effect
      :mandate/recorded
      (let [{:keys [result]} (record-mandate! s payload)]
        (d/transact! conn
                     [{:mandate/current true
                       :mandate/payload (enc {:annual-fee-rate-cap (double (:annual-fee-rate-cap payload))
                                              :effective-date (:effective-date payload)})}
                      {:mandate-history/seq (count (mandate-history s))
                       :mandate-history/record (enc (get result "record"))}])
        result)

      :fee/drawn
      (let [{:keys [result]} (draw-fee! s payload)]
        (d/transact! conn [{:drawdown-history/seq (count (drawdown-history s))
                           :drawdown-history/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact))

(defn datomic-store
  "An empty DatomicStore (langchain.db backend)."
  []
  (->DatomicStore (d/create-conn schema)))
