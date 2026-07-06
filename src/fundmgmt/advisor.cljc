(ns fundmgmt.advisor
  "FundManager-LLM client -- the *contained intelligence node* for the
  fund-management-company actor. It normalizes investment-mandate intake
  and drafts a management-fee DRAWDOWN off an UPSTREAM
  `cloud-itonami-isic-6499` (`vcfund.nav/fund-nav-report`) fee-accrual
  report. CRITICAL: it is a smart-but-untrusted advisor -- it returns a
  *proposal*, never a committed record or a real cash movement. Every
  output is censored downstream by `fundmgmt.governor` before anything
  touches the SSoT, and `:fee/drawdown` NEVER auto-commits at any phase
  -- see README `Actuation`.

  Like `vcfund.ddllm`/`trustfund.advisor`, this is a deterministic mock
  so the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM with the same proposal
  shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by validation gates
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/draw-fee | nil
     :confidence 0..1}")

(defn- normalize-mandate
  "Investment-mandate intake -- the LLM only normalizes/validates the
  patch; it does not invent the LPA-authorized fee-rate ceiling. High
  confidence, low stakes."
  [{:keys [annual-fee-rate-cap effective-date]}]
  {:summary    (str "投資マンデート登録 (annual-fee-rate-cap=" annual-fee-rate-cap ")")
   :rationale  "入力されたマンデート事実の正規化のみ。新規事実の生成なし。"
   :cites      [:annual-fee-rate-cap :effective-date]
   :effect     :mandate/recorded
   :value      {:annual-fee-rate-cap annual-fee-rate-cap :effective-date effective-date}
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

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [{:keys [op] :as request}]
  (case op
    :mandate/record (normalize-mandate request)
    :fee/drawdown   (propose-fee-drawdown request)
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
