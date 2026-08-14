(ns fundmgmt.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2: this repo previously had NO demo page
  and no generator at all. This namespace drives the REAL actor stack
  (`fundmgmt.operation` -> `fundmgmt.governor` -> `fundmgmt.store`,
  through `langgraph.graph/run*` exactly the way `fundmgmt.sim` does)
  and renders whatever that run actually produced.

  EVERY id, rate, amount and hold reason on the page is lifted from this
  repo's own fixtures -- `fundmgmt.sim`'s upstream `vcfund` report
  fixtures (mandate caps 2%/20%, sector caps ai 80% / fintech 30%, stage
  cap seed 90%, period `2026-Q3`, commitment `USA-00000000`, fee basis
  6,000,000 @ 2% for 1y, after-preferred-profit 9,520,000 @ 20% carry,
  the 2026-07-06 concentration report) and, for the two governor rules
  `fundmgmt.sim` never exercises, `test/fundmgmt/governor_contract_test`'s
  own out-of-range `1.5` rate-cap fixtures. Nothing is invented here: the
  record ids (`MANDATE-000000`, `DRAWDOWN-000000`, `CARRY-000000`,
  `GUIDELINE-000000`) are assigned by `fundmgmt.registry` during the run,
  and every violation row is read back off the store's own append-only
  ledger.

  Deterministic: no timestamps, no randomness, every map iteration
  explicitly sorted, and all number formatting pinned to `Locale/ROOT`
  (a default-locale `%.2f` would emit `0,02` on a comma-decimal host and
  break byte-identity across machines). Byte-identical across reruns --
  verify by diffing two consecutive runs.

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jp-go-dds.skin]
            [langgraph.graph :as g]
            [fundmgmt.advisor :as advisor]
            [fundmgmt.governor :as governor]
            [fundmgmt.operation :as op]
            [fundmgmt.phase :as phase]
            [fundmgmt.registry :as registry]
            [fundmgmt.store :as store]))

;; ----------------------------- scenario fixtures -----------------------------
;; All values below are lifted verbatim from `fundmgmt.sim` (the repo's own
;; demo driver) and `test/fundmgmt/governor_contract_test.cljc`. They are
;; defs, not inline literals, so the gate table below probes the SAME
;; request maps the run actually executed.

(def ^:private operator
  "The same GP-principal operator context `fundmgmt.sim` uses."
  {:actor-id "op-1" :actor-role :gp-principal :phase 3})

(def ^:private approver
  "The human GP principal who approves in this scenario -- `fundmgmt.sim`'s
  own `{:status :approved :by \"op-1\"}`."
  "op-1")

(def ^:private req-mandate
  {:op :mandate/record :subject "fund"
   :annual-fee-rate-cap 0.02 :carry-rate-cap 0.20
   :sector-caps {"ai" 0.80 "fintech" 0.30} :stage-caps {"seed" 0.90}
   :effective-date "2026-01-01"})

(def ^:private clean-fee-report
  "Upstream `vcfund.nav/fund-nav-report` fee-accrual fixture: 6,000,000
  basis @ 2% for one year = 120,000."
  {:fee-basis 6000000 :annual-fee-rate 0.02 :years-elapsed 1 :accrued-amount 120000})

(def ^:private req-fee-q3
  {:op :fee/drawdown :subject "fund" :period "2026-Q3"
   :upstream-fee-report clean-fee-report})

(def ^:private clean-carry-report
  "Upstream `vcfund.registry/distribute-waterfall` exit fixture:
  after-preferred-profit 9,520,000 @ 20% carry = 1,904,000."
  {:after-preferred-profit 9520000 :carry-rate 0.20 :gp-carry 1904000})

(def ^:private req-carry
  {:op :carry/distribute :subject "fund" :commitment-number "USA-00000000"
   :upstream-distribution-report clean-carry-report})

(def ^:private clean-concentration-report
  "Upstream `vcfund.concentration/concentration-report` fixture."
  {:total-invested-at-cost 2800000.0
   :by-sector {"ai" {:amount 2000000.0 :fraction 0.7142857142857143}
               "robotics" {:amount 500000.0 :fraction 0.17857142857142858}
               "fintech" {:amount 300000.0 :fraction 0.10714285714285714}}
   :by-investment-stage {"seed" {:amount 2300000.0 :fraction 0.8214285714285714}
                         "series-a" {:amount 500000.0 :fraction 0.17857142857142858}}})

(def ^:private req-guideline
  {:op :guideline/disclose :subject "fund"
   :upstream-concentration-report clean-concentration-report
   :as-of-date "2026-07-06"})

(def ^:private gate-probe-requests
  "op -> the clean request this run actually executed for it. The action-gate
  table asks the REAL advisor what stake it attaches to each of these, rather
  than restating the README."
  {:mandate/record     req-mandate
   :fee/drawdown       req-fee-q3
   :carry/distribute   req-carry
   :guideline/disclose req-guideline})

;; ----------------------------- the run -----------------------------

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- resume! [actor tid status]
  (g/run* actor {:approval {:status status :by approver}}
          {:thread-id tid :resume? true}))

(defn- approval-of
  "Captures what the graph itself knew at approval time: the
  `:approval-granted` audit fact and the `:record` map that was handed to
  `fundmgmt.store/commit-record!`. The retention analysis below compares
  these against what actually landed in the SSoT."
  [op tid res]
  {:op op
   :thread tid
   :granted-fact (last (filter #(= :approval-granted (:t %)) (get-in res [:state :audit])))
   :handed-payload (get-in res [:state :record :payload])})

(defn run-demo!
  "Runs the real actor over a scenario that reaches BOTH directions and
  every HARD rule `fundmgmt.governor` implements.

  Seeded store: records the investment mandate (auto-commits at phase 3 --
  no capital risk), then draws the 2026-Q3 management fee, distributes
  carry for commitment USA-00000000, and discloses guideline compliance
  as of 2026-07-06 -- all three ALWAYS escalate to a human GP principal
  (`:actuation/*` is never auto at any phase) and are approved here. One
  further clean drawdown is REJECTED by the same human, to show the
  human-refusal direction separately from a governor block. Nine HARD
  holds then never reach a human at all: an upstream rate above the 2%
  mandate cap, an accrued amount that fails this company's independent
  recomputation, a double-draw of 2026-Q3, an upstream carry rate above
  the 20% cap, a gp_carry that fails independent recomputation, a
  double-distribution of USA-00000000, an ai concentration above the 80%
  sector cap, and two out-of-range (1.5) mandate rate caps.

  A second, deliberately EMPTY store carries the three remaining HARD
  rules, which are only reachable with no mandate on file at all.

  Returns both stores plus the captured approval evidence -- every field
  the renderer reads is real governor/store output."
  []
  (let [db      (store/seed-db)
        actor   (op/build db)
        nom-db  (store/seed-db)
        nom-actor (op/build nom-db)]

    ;; -- seeded store: the committed path ------------------------------
    (exec! actor "t1-mandate" req-mandate)          ; auto-commits (phase 3)

    (exec! actor "t2-fee-q3" req-fee-q3)
    (let [a-fee (resume! actor "t2-fee-q3" :approved)

          _ (exec! actor "t3-fee-q4-rejected"
                   {:op :fee/drawdown :subject "fund" :period "2026-Q4"
                    :upstream-fee-report clean-fee-report})
          _ (resume! actor "t3-fee-q4-rejected" :rejected)

          ;; -- seeded store: HARD holds ---------------------------------
          _ (exec! actor "t4-rate-exceeds"
                   {:op :fee/drawdown :subject "fund" :period "2026-Q4"
                    :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.05
                                          :years-elapsed 1 :accrued-amount 300000}})
          _ (exec! actor "t5-accrual-mismatch"
                   {:op :fee/drawdown :subject "fund" :period "2026-Q4"
                    :upstream-fee-report {:fee-basis 6000000 :annual-fee-rate 0.02
                                          :years-elapsed 1 :accrued-amount 999999}})
          _ (exec! actor "t6-double-draw" req-fee-q3)

          _ (exec! actor "t7-carry" req-carry)
          a-carry (resume! actor "t7-carry" :approved)

          _ (exec! actor "t8-carry-rate-exceeds"
                   {:op :carry/distribute :subject "fund" :commitment-number "USA-00000001"
                    :upstream-distribution-report {:after-preferred-profit 9520000
                                                   :carry-rate 0.30 :gp-carry 2856000}})
          _ (exec! actor "t9-carry-mismatch"
                   {:op :carry/distribute :subject "fund" :commitment-number "USA-00000001"
                    :upstream-distribution-report {:after-preferred-profit 9520000
                                                   :carry-rate 0.20 :gp-carry 999999}})
          _ (exec! actor "t10-double-distribution" req-carry)

          _ (exec! actor "t11-guideline" req-guideline)
          a-guideline (resume! actor "t11-guideline" :approved)

          _ (exec! actor "t12-concentration-exceeded"
                   (assoc req-guideline :upstream-concentration-report
                          (assoc-in clean-concentration-report [:by-sector "ai" :fraction] 0.95)))
          ;; the two out-of-range rate-cap rules `fundmgmt.sim` never
          ;; exercises -- fixtures from governor_contract_test
          _ (exec! actor "t13-invalid-rate-cap"
                   {:op :mandate/record :subject "fund"
                    :annual-fee-rate-cap 1.5 :effective-date "2026-01-01"})
          _ (exec! actor "t14-invalid-carry-rate-cap"
                   {:op :mandate/record :subject "fund"
                    :annual-fee-rate-cap 0.02 :carry-rate-cap 1.5
                    :effective-date "2026-01-01"})

          ;; -- empty store: the three "nothing on file" HARD rules ------
          _ (exec! nom-actor "n1-mandate-missing" req-fee-q3)
          _ (exec! nom-actor "n2-carry-mandate-missing" req-carry)
          _ (exec! nom-actor "n3-guideline-mandate-missing" req-guideline)]

      {:db db
       :nomandate-db nom-db
       :approvals [(approval-of :fee/drawdown "t2-fee-q3" a-fee)
                   (approval-of :carry/distribute "t7-carry" a-carry)
                   (approval-of :guideline/disclose "t11-guideline" a-guideline)]})))

;; ----------------------------- derivation helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- nfmt [pattern x]
  (String/format java.util.Locale/ROOT pattern (object-array [(double x)])))

(defn- pct [x] (str (nfmt "%.2f" (* 100.0 (double x))) "%"))
(defn- amt [x] (nfmt "%,.2f" x))

(defn- approver-keys
  "Keys of `m` that DENOTE an approver (name contains \"approv\"), regardless
  of what the value is. Deliberately key-based, not value-based: in this
  scenario the approving principal and the requesting actor-id are the same
  string (`op-1`), so a value scan would report the commit fact's `:actor`
  field as approver provenance and be wrong."
  [m]
  (->> (keys m)
       (filter #(re-find #"(?i)approv" (str %)))
       (sort-by str)
       vec))

(def ^:private record-lookup
  "op -> [history-fn record-id-field record-match-field payload-match-key].
  Used to find the SSoT record a given approval actually produced, by
  matching on the record's own identifying field rather than assuming
  position."
  {:fee/drawdown       [store/drawdown-history "record_id" "period" :period]
   :carry/distribute   [store/carry-distribution-history "record_id" "commitment_number" :commitment-number]
   :guideline/disclose [store/guideline-disclosure-history "record_id" "as_of_date" :as-of-date]})

(defn- committed-record-for
  "The SSoT record this approval produced, found by matching the record's own
  identifying field against the payload the graph handed to the store."
  [db {:keys [op handed-payload]}]
  (let [[hist-fn _ match-field payload-key] (record-lookup op)]
    (first (filter #(= (get % match-field) (get handed-payload payload-key))
                   (hist-fn db)))))

(defn- commit-fact-for [db op]
  (last (filter #(and (= :committed (:t %)) (= op (:op %))) (store/ledger db))))

(defn- retention
  "Derives, at render time, where the approving principal's identity survives
  and where it is dropped -- by inspecting the three real artifacts (the
  payload handed to the store, the record the registry wrote, the commit fact
  the ledger kept) for approver-denoting keys. Nothing here is hard-coded: if
  `fundmgmt.registry` starts carrying the approver into its record, or
  `fundmgmt.operation/commit-fact` starts carrying it into the ledger, these
  cells flip to `kept` on the next regeneration."
  [db {:keys [op handed-payload granted-fact] :as approval}]
  (let [record (committed-record-for db approval)
        fact (commit-fact-for db op)
        auto-fact (commit-fact-for db :mandate/record)]
    {:op op
     :approver (:by granted-fact)
     :record-id (get record "record_id")
     :payload-keys (approver-keys handed-payload)
     :record-keys (approver-keys record)
     :fact-keys (approver-keys fact)
     ;; If an approved commit fact has exactly the same key set as the
     ;; AUTO-committed (never-approved) mandate fact, the ledger shape
     ;; cannot distinguish "approved by a human" from "auto-committed".
     :fact-shape-same-as-auto? (= (set (keys fact)) (set (keys auto-fact)))
     ;; Is the approval fact itself persisted anywhere in the SSoT ledger,
     ;; or does it only ever exist in the graph's in-memory audit channel?
     :granted-fact-persisted? (boolean (some #(= :approval-granted (:t %)) (store/ledger db)))}))

(defn- hold-rows-for
  "Every HARD `:governor-hold` the given store's append-only ledger actually
  recorded, one row per violation, in ledger order."
  [store-label db]
  (for [f (store/ledger db)
        :when (and (= :governor-hold (:t f)) (seq (:violations f)))
        v (:violations f)]
    {:store store-label :op (:op f) :rule (:rule v) :detail (:detail v)
     :confidence (:confidence f) :subject (:subject f)}))

;; ----------------------------- markup -----------------------------

(defn- tr [& cells]
  (str "        <tr>" (str/join (map #(str "<td>" % "</td>") cells)) "</tr>"))

(defn- table [headers rows]
  (str "    <table>\n"
       "      <thead><tr>" (str/join (map #(str "<th>" (esc %) "</th>") headers)) "</tr></thead>\n"
       "      <tbody>\n"
       (str/join "\n" rows) "\n"
       "      </tbody>\n"
       "    </table>\n"))

(defn- section [title note body]
  (str "  <section class=\"card\">\n"
       "    <h2>" title "</h2>\n"
       "    <p class=\"muted\">" note "</p>\n"
       body
       "  </section>\n"))

(defn- ok [s] (str "<span class=\"ok\">" s "</span>"))
(defn- warn [s] (str "<span class=\"warn\">" s "</span>"))
(defn- crit [s] (str "<span class=\"critical\">" s "</span>"))
(defn- muted [s] (str "<span class=\"muted\">" s "</span>"))
(defn- code [s] (str "<code>" (esc s) "</code>"))

;; ----------------------------- sections -----------------------------

(defn- mandate-section [db]
  (let [m (store/mandate db)
        rows (concat
              [(tr (code ":annual-fee-rate-cap") (pct (:annual-fee-rate-cap m))
                   (esc "LPA-authorized management-fee ceiling"))
               (tr (code ":carry-rate-cap")
                   (if-let [c (:carry-rate-cap m)] (pct c) (muted "not recorded"))
                   (esc "carried-interest ceiling — a separate authorization from the fee cap"))]
              (for [[k v] (sort-by key (:sector-caps m))]
                (tr (code (str ":sector-caps " (pr-str k))) (pct v)
                    (esc "portfolio concentration ceiling for this sector")))
              (for [[k v] (sort-by key (:stage-caps m))]
                (tr (code (str ":stage-caps " (pr-str k))) (pct v)
                    (esc "portfolio concentration ceiling for this investment stage")))
              [(tr (code ":effective-date") (esc (:effective-date m))
                   (esc "from the recorded mandate"))])]
    (section "Recorded investment mandate (this company&rsquo;s own fiduciary record)"
             (str "Read back from <code>fundmgmt.store/mandate</code> after the run. "
                  "This is the ceiling the governor checks every upstream "
                  "<code>cloud-itonami-isic-6499</code> (<code>vcfund</code>) claim against — "
                  "the upstream investment actor holds no copy of it.")
             (table ["Mandate term" "Recorded value" "Meaning"] rows))))

(defn- gate-section []
  (let [ph phase/default-phase
        {:keys [writes auto label]} (get phase/phases ph)
        rows (for [op (sort-by str phase/write-ops)
                   :let [req (gate-probe-requests op)
                         stake (:stake (advisor/infer req))
                         high? (boolean (governor/high-stakes stake))
                         auto? (contains? auto op)]]
               (tr (code (str op))
                   (if (contains? writes op) (ok "enabled") (crit "disabled"))
                   (if stake (code (str stake)) (muted "none"))
                   (if high? (warn "high-stakes") (muted "no capital risk"))
                   (if auto?
                     (ok "auto-commit when governor-clean")
                     (warn "ALWAYS human approval · never auto at any phase"))))]
    (section (str "Action gate — phase " ph " (" (esc label) ")")
             (str "Derived at render time from <code>fundmgmt.phase/phases</code> and the stake the "
                  "REAL <code>fundmgmt.advisor</code> attaches to each request this run executed, "
                  "not from prose. HARD governor holds below cannot be overridden by any approver.")
             (table ["Op" "Writes at this phase" "Advisor stake" "Actuation" "Disposition when governor-clean"] rows))))

(defn- recompute-section []
  (let [rows [(tr (code ":fee/drawdown") (esc "2026-Q3")
                  (esc (amt (:accrued-amount clean-fee-report)))
                  (esc (amt (registry/fee-accrued (:fee-basis clean-fee-report)
                                                  (:annual-fee-rate clean-fee-report)
                                                  (:years-elapsed clean-fee-report))))
                  (ok "match — allowed to proceed to a human"))
              (tr (code ":fee/drawdown") (esc "2026-Q4 (tampered claim)")
                  (esc (amt 999999))
                  (esc (amt (registry/fee-accrued 6000000 0.02 1)))
                  (crit "mismatch — HARD hold"))
              (tr (code ":carry/distribute") (esc "USA-00000000")
                  (esc (amt (:gp-carry clean-carry-report)))
                  (esc (amt (registry/carry-accrued (:after-preferred-profit clean-carry-report)
                                                    (:carry-rate clean-carry-report))))
                  (ok "match — allowed to proceed to a human"))
              (tr (code ":carry/distribute") (esc "USA-00000001 (tampered claim)")
                  (esc (amt 999999))
                  (esc (amt (registry/carry-accrued 9520000 0.20)))
                  (crit "mismatch — HARD hold"))]]
    (section "Independent recomputation of every upstream claim"
             (str "The governor never trusts an upstream <code>vcfund</code> number as-is. "
                  "The right-hand column is computed here and now by "
                  "<code>fundmgmt.registry/fee-accrued</code> / <code>carry-accrued</code> — "
                  "deliberately separate re-implementations of the upstream formulas — from the "
                  "upstream report&rsquo;s OWN inputs.")
             (table ["Op" "Subject" "Upstream claim" "Independent recomputation" "Verdict"] rows))))

(defn- records-section [db approvals]
  (let [auto-fact (commit-fact-for db :mandate/record)
        mandate-rec (first (store/mandate-history db))
        by-op (into {} (map (juxt :op identity)) approvals)
        approved-row
        (fn [op label figure]
          (let [r (retention db (by-op op))]
            (tr (code (get r :record-id))
                (code (str op))
                (esc label)
                (esc figure)
                (if (seq (:record-keys r))
                  (ok (str "approved · " (esc (:approver r))))
                  (warn (str "approved by " (esc (:approver r)) " · not on the record")))))) ]
    (section "Committed records (this run&rsquo;s SSoT)"
             (str "Record ids are assigned by <code>fundmgmt.registry</code> during the run and read "
                  "back off the store&rsquo;s append-only history — not typed into this page. "
                  "Each record below is an UNSIGNED draft: signature is a human GP principal&rsquo;s act, "
                  "never this actor&rsquo;s.")
             (table ["Record" "Op" "Kind" "Key figure" "How it committed"]
                    [(tr (code (get mandate-rec "record_id"))
                         (code ":mandate/record")
                         (esc (get mandate-rec "kind"))
                         (esc (str "fee cap " (pct (get mandate-rec "annual_fee_rate_cap"))
                                   " · carry cap " (pct (get mandate-rec "carry_rate_cap"))))
                         (if (seq (approver-keys auto-fact))
                           (warn "auto-commit, yet carries an approver key")
                           (ok "auto-commit · no human approver by design")))
                     (approved-row :fee/drawdown "fee-drawdown-draft"
                                   (str "accrued " (amt (:accrued-amount clean-fee-report))
                                        " for 2026-Q3"))
                     (approved-row :carry/distribute "carry-distribution-draft"
                                   (str "gp_carry " (amt (:gp-carry clean-carry-report))
                                        " for USA-00000000"))
                     (approved-row :guideline/disclose "guideline-disclosure-draft"
                                   (str "total at cost "
                                        (amt (:total-invested-at-cost clean-concentration-report))
                                        " as of 2026-07-06"))]))))

(defn- provenance-section [db approvals]
  (let [rs (map #(retention db %) approvals)
        kept (fn [ks] (if (seq ks)
                        (ok (str "kept · " (esc (str/join ", " ks))))
                        (crit "dropped")))
        rows (for [r rs]
               (tr (code (str (:op r)))
                   (code (:record-id r))
                   (esc (:approver r))
                   (kept (:payload-keys r))
                   (kept (:record-keys r))
                   (kept (:fact-keys r))))
        any-dropped? (some #(and (seq (:payload-keys %)) (empty? (:record-keys %))) rs)
        shape-blind? (every? :fact-shape-same-as-auto? rs)
        unpersisted? (not-any? :granted-fact-persisted? rs)]
    (section "Approver provenance — where the human&rsquo;s identity survives"
             (str "Every cell is derived at render time by scanning each real artifact for a key that "
                  "DENOTES an approver, so this table self-corrects: fix the actor and the next "
                  "regeneration flips these to <em>kept</em>. Key-based, not value-based, on purpose — "
                  "in this scenario the approving principal and the requesting actor-id are the same "
                  "string, so scanning values would mistake the commit fact&rsquo;s "
                  "<code>:actor</code> field for approval provenance.")
             (str (table ["Op" "Record" "Approved by" "Handed to store (:payload)"
                          "Registry record" "Ledger commit fact"]
                         rows)
                  (when any-dropped?
                    (str "    <p class=\"critical\"><strong>Measured defect.</strong> "
                         "<code>fundmgmt.operation</code> DOES hand the approver to the store — "
                         "<code>:request-approval</code> puts <code>:approved-by</code> into the "
                         "<code>:payload</code>, and <code>fundmgmt.store/commit-record!</code> DOES "
                         "read <code>:payload</code> (not <code>:value</code>). The identity is lost one "
                         "layer further down: <code>fundmgmt.registry/register-fee-drawdown</code>, "
                         "<code>register-carry-distribution</code> and "
                         "<code>register-guideline-disclosure</code> take positional arguments and are "
                         "never passed <code>:approved-by</code>, so it is dropped before the immutable "
                         "record is built.</p>\n"))
                  (when shape-blind?
                    (str "    <p class=\"critical\">The append-only ledger is blind to it too: an "
                         "approved <code>:committed</code> fact has exactly the same key set as the "
                         "AUTO-committed mandate fact, so the ledger cannot distinguish "
                         "<em>a human approved this</em> from <em>this auto-committed</em>.</p>\n"))
                  (when unpersisted?
                    (str "    <p class=\"critical\">The <code>:approval-granted</code> fact exists only "
                         "in the graph&rsquo;s in-memory <code>:audit</code> channel for the duration of "
                         "the run — <code>fundmgmt.operation</code>&rsquo;s <code>:commit</code> node "
                         "appends only the commit fact, so no record of WHO approved a real cash "
                         "movement reaches the SSoT at all.</p>\n"))
                  (when-not (or any-dropped? shape-blind? unpersisted?)
                    (str "    <p class=\"ok\">Approver identity survives into the SSoT on every "
                         "committed record.</p>\n"))))))

(defn- concentration-section [db]
  (let [m (store/mandate db)
        rec (first (store/guideline-disclosure-history db))
        row (fn [dimension caps reported]
              (for [[nm {:keys [amount fraction]}] (sort-by key reported)]
                (let [cap (get caps nm)]
                  (tr (esc dimension) (code nm) (esc (amt amount)) (esc (pct fraction))
                      (if cap (esc (pct cap)) (muted "no cap recorded"))
                      (cond
                        (nil? cap) (muted "not limited by this mandate")
                        (<= fraction cap) (ok "within cap")
                        :else (crit "exceeds cap"))))))
        rows (concat (row "sector" (:sector-caps m) (get rec "by_sector"))
                     (row "stage" (:stage-caps m) (get rec "by_investment_stage")))]
    (section "Disclosed portfolio concentration vs this company&rsquo;s own caps"
             (str "The two-sided cross-check: the fractions come from the upstream "
                  "<code>vcfund.concentration</code> report carried verbatim into "
                  (code (get rec "record_id"))
                  ", the caps come from THIS company&rsquo;s recorded mandate. Neither side alone holds "
                  "both halves. A name the mandate does not cap is never flagged. The same report with "
                  "<code>ai</code> at 95% HARD-holds — see below.")
             (table ["Dimension" "Name" "At cost" "Reported fraction" "Mandate cap" "Verdict"] rows))))

(defn- holds-section [holds]
  (let [rows (for [{:keys [store op rule detail confidence]} holds]
               (tr (crit (esc (name rule)))
                   (code (str op))
                   (esc detail)
                   (esc (str confidence))
                   (esc store)))]
    (section (str "Governor HARD holds — " (count holds) " unoverridable blocks")
             (str "Read back off the stores&rsquo; own append-only ledgers. A HARD hold settles "
                  "immediately and <strong>never reaches a human</strong>: there is no approver who can "
                  "override any row here. Advisor confidence is shown to make the point that a "
                  "high-confidence proposal is blocked exactly as hard as a low-confidence one.")
             (table ["Rule" "Op" "Governor detail" "Advisor confidence" "Store"] rows))))

(defn- ledger-section [db nom-db]
  (let [rows (fn [label ledger]
               (for [{:keys [t op subject disposition basis]} ledger]
                 (tr (esc label)
                     (case t
                       :committed (ok (esc (name t)))
                       :governor-hold (crit (esc (name t)))
                       :approval-rejected (warn (esc (name t)))
                       (muted (esc (name t))))
                     (code (str op))
                     (esc subject)
                     (esc (or (some->> basis (map name) (str/join ", "))
                              (some-> disposition name) "")))))
        all (concat (rows "seeded" (store/ledger db))
                    (rows "empty (no mandate)" (store/ledger nom-db)))]
    (section (str "Audit ledger — " (count all) " decision facts")
             (str "The append-only decision log both stores actually accumulated during this run, in "
                  "order. <code>:committed</code> = landed in the SSoT; "
                  "<code>:approval-rejected</code> = a human refused a governor-clean proposal; "
                  "<code>:governor-hold</code> = blocked before any human saw it.")
             (table ["Store" "Fact" "Op" "Subject" "Basis"] all))))

;; ----------------------------- document -----------------------------

(defn render
  "Renders the whole operator console from a completed `run-demo!` result."
  [{:keys [db nomandate-db approvals]}]
  (let [holds (concat (hold-rows-for "seeded" db)
                      (hold-rows-for "empty (no mandate)" nomandate-db))]
    [holds
     (str
      "<!doctype html>\n"
      "<html lang=\"en\"><head><meta charset=\"utf-8\">\n"
      "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
      "<title>cloud-itonami-isic-6630 &middot; fund management &mdash; Operator Console</title>\n"
      "<style>\n" (jp-go-dds.skin/dds+skin) "\n</style></head><body>\n"
      "<header class=\"bar\">\n"
      "  <h1>Fund management activities (ISIC 6630) &mdash; Operator Console</h1>\n"
      "  <p class=\"badge\">read-only sample &middot; governor-gated &middot; "
      "fee drawdown / carry distribution / guideline disclosure are ALWAYS human-approved</p>\n"
      "</header>\n"
      "<main>\n"
      "  <section class=\"card\">\n"
      "    <p>Every figure on this page was produced by running this repo&rsquo;s real actor stack at "
      "build time &mdash; <code>fundmgmt.operation</code> (a <code>langgraph</code> StateGraph) "
      "&rarr; <code>fundmgmt.governor</code> &rarr; <code>fundmgmt.store</code> &mdash; and reading "
      "the result back out of the store. Regenerate with "
      "<code>clojure -M:dev:render-html</code>. Deterministic: byte-identical across reruns.</p>\n"
      "  </section>\n"
      (mandate-section db)
      (gate-section)
      (recompute-section)
      (records-section db approvals)
      (provenance-section db approvals)
      (concentration-section db)
      (holds-section holds)
      (ledger-section db nomandate-db)
      "</main>\n"
      "<footer>\n"
      "  <p>cloud-itonami-isic-6630 &middot; generated by <code>fundmgmt.render-html</code> from a live "
      "actor run &middot; drafts are UNSIGNED &mdash; signature is a human GP principal&rsquo;s act.</p>\n"
      "</footer>\n"
      "</body></html>\n")]))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        result (run-demo!)
        [holds html] (render result)
        hard-count (count holds)]
    ;; Build-time invariant, not a convention: a console that shows no
    ;; unoverridable governor block is not evidence that this actor is
    ;; governed, so refuse to write one.
    (when (zero? hard-count)
      (throw (ex-info "render-html: rendered 0 HARD :governor-hold entries — refusing to write a console that cannot show an unoverridable block"
                      {:hard-holds 0 :out out})))
    (io/make-parents out)
    (spit out html)
    (println "wrote" out
             (str "(" hard-count " HARD governor holds, "
                  (count (store/ledger (:db result))) " seeded ledger facts, "
                  (count (store/ledger (:nomandate-db result))) " empty-store ledger facts, "
                  (count (:approvals result)) " human approvals)"))))
