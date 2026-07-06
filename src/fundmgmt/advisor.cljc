(ns fundmgmt.advisor
  "FundManager-LLM client -- the *contained intelligence node* for the
  fund-management-company actor. It normalizes investment-mandate intake,
  drafts a management-fee DRAWDOWN off an UPSTREAM `cloud-itonami-isic-
  6499` (`vcfund.nav/fund-nav-report`) fee-accrual report, a GP carry-
  DISTRIBUTION off an upstream (`vcfund.registry/register-distribution`)
  exit-distribution fact, AND a guideline-COMPLIANCE DISCLOSURE off an
  upstream (`vcfund.concentration/concentration-report`) portfolio-
  composition fact. CRITICAL: it is a smart-but-untrusted advisor -- it
  returns a *proposal*, never a committed record or a real cash
  movement. Every output is censored downstream by `fundmgmt.governor`
  before anything touches the SSoT, and `:fee/drawdown`/`:carry/
  distribute`/`:guideline/disclose` NEVER auto-commit at any phase -- see
  README `Actuation`.

  Like `vcfund.ddllm`/`trustfund.advisor`, this is a deterministic mock
  so the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM with the same proposal
  shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by validation gates
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/draw-fee | :actuation/distribute-carry | :actuation/disclose-guidelines | nil
     :confidence 0..1}")

(defn- normalize-mandate
  "Investment-mandate intake -- the LLM only normalizes/validates the
  patch; it does not invent the LPA-authorized fee-rate ceiling (or the
  OPTIONAL carry-rate ceiling/sector-caps/stage-caps). High confidence,
  low stakes."
  [{:keys [annual-fee-rate-cap carry-rate-cap sector-caps stage-caps effective-date]}]
  {:summary    (str "投資マンデート登録 (annual-fee-rate-cap=" annual-fee-rate-cap
                    (when carry-rate-cap (str ", carry-rate-cap=" carry-rate-cap))
                    (when (seq sector-caps) (str ", sector-caps=" sector-caps))
                    (when (seq stage-caps) (str ", stage-caps=" stage-caps)) ")")
   :rationale  "入力されたマンデート事実の正規化のみ。新規事実の生成なし。"
   :cites      [:annual-fee-rate-cap :effective-date]
   :effect     :mandate/recorded
   :value      {:annual-fee-rate-cap annual-fee-rate-cap :carry-rate-cap carry-rate-cap
               :sector-caps sector-caps :stage-caps stage-caps
               :effective-date effective-date}
   :stake      nil
   :confidence 0.95})

(defn- propose-fee-drawdown
  "Draft the management-fee DRAWDOWN action -- the GP entity's own act of
  drawing the fee an UPSTREAM `vcfund.nav/fund-nav-report`-shaped fee-
  accrual report claims is due (`:upstream-fee-report`, the exact
  `{:fee-basis .. :annual-fee-rate .. :years-elapsed .. :accrued-amount
  ..}` an operator would read off that report -- a REAL fact this
  advisor reads, never invents). ALWAYS `:stake :actuation/draw-fee` --
  a REAL-WORLD cash movement, never a draft the actor may auto-run. See
  README `Actuation`."
  [{:keys [period upstream-fee-report]}]
  (let [{:keys [fee-basis annual-fee-rate years-elapsed accrued-amount]} upstream-fee-report]
    {:summary    (str period " 向けfee drawdown提案 (accrued=" accrued-amount ")")
     :rationale  (str "upstream vcfund fee-accrual report: fee-basis=" fee-basis
                      " rate=" annual-fee-rate " years=" years-elapsed)
     :cites      [period]
     :effect     :fee/drawn
     :value      {:period period :fee-basis fee-basis
                 :annual-fee-rate annual-fee-rate :years-elapsed years-elapsed}
     :stake      :actuation/draw-fee
     :confidence (if (and fee-basis annual-fee-rate years-elapsed) 0.9 0.2)}))

(defn- propose-carry-distribution
  "Draft the GP carry-DISTRIBUTION action -- the GP entity's own act of
  taking its carried-interest share of an UPSTREAM `vcfund.registry/
  register-distribution`-shaped exit-distribution fact
  (`:upstream-distribution-report`, the exact `{:after-preferred-profit
  .. :carry-rate .. :gp-carry ..}` an operator would derive from that
  fact's waterfall (`:gp-carry` + `:lp-residual-profit` = after-
  preferred-profit; `:carry-rate` and `:gp-carry` themselves are read
  straight off it) -- a REAL fact this advisor reads, never invents).
  ALWAYS `:stake :actuation/distribute-carry` -- a REAL-WORLD cash
  movement, never a draft the actor may auto-run. See README
  `Actuation`."
  [{:keys [commitment-number upstream-distribution-report]}]
  (let [{:keys [after-preferred-profit carry-rate gp-carry]} upstream-distribution-report]
    {:summary    (str commitment-number " 向けcarry distribution提案 (gp_carry=" gp-carry ")")
     :rationale  (str "upstream vcfund exit-distribution fact: after-preferred-profit=" after-preferred-profit
                      " carry-rate=" carry-rate)
     :cites      [commitment-number]
     :effect     :carry/distributed
     :value      {:commitment-number commitment-number
                 :after-preferred-profit after-preferred-profit :carry-rate carry-rate}
     :stake      :actuation/distribute-carry
     :confidence (if (and after-preferred-profit carry-rate) 0.9 0.2)}))

(defn- propose-guideline-disclosure
  "Draft the guideline-COMPLIANCE DISCLOSURE action -- the GP entity's
  own act of disclosing that the fund's actual portfolio composition, as
  reported by an UPSTREAM `vcfund.concentration/concentration-report`-
  shaped fact (`:upstream-concentration-report`, the exact
  `{:total-invested-at-cost .. :by-sector .. :by-investment-stage ..}`
  map -- a REAL fact this advisor reads, never invents), complies with
  THIS company's own recorded sector/stage concentration caps. ALWAYS
  `:stake :actuation/disclose-guidelines` -- a REAL-WORLD compliance
  statement, never a draft the actor may auto-run. See README
  `Actuation`."
  [{:keys [upstream-concentration-report as-of-date]}]
  (let [{:keys [total-invested-at-cost by-sector by-investment-stage]} upstream-concentration-report]
    {:summary    (str "guideline compliance disclosure提案 (as_of=" as-of-date
                      ", total=" total-invested-at-cost ")")
     :rationale  (str "upstream vcfund concentration report: as_of=" as-of-date)
     :cites      [as-of-date]
     :effect     :guideline/disclosed
     :value      {:total-invested-at-cost total-invested-at-cost
                 :by-sector by-sector :by-investment-stage by-investment-stage
                 :as-of-date as-of-date}
     :stake      :actuation/disclose-guidelines
     :confidence (if (and total-invested-at-cost by-sector by-investment-stage) 0.9 0.2)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [{:keys [op] :as request}]
  (case op
    :mandate/record     (normalize-mandate request)
    :fee/drawdown       (propose-fee-drawdown request)
    :carry/distribute   (propose-carry-distribution request)
    :guideline/disclose (propose-guideline-disclosure request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default
  everywhere. `store` is unused -- this actor has no per-request store
  lookups (its only state, the current mandate, is read by the
  governor, not the advisor)."
  [] (reify Advisor (-advise [_ _st req] (infer req))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :advisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
