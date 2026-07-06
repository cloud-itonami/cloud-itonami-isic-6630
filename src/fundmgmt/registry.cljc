(ns fundmgmt.registry
  "Pure-function investment-mandate, management-fee-drawdown and carry-
  distribution record construction -- an append-only fund-management-
  company book-of-record draft.

  This is the management company's own registry, distinct from (and
  never a code dependency of) `cloud-itonami-isic-6499`'s `vcfund.
  registry`/`vcfund.nav`. The two repos interoperate ONLY through a
  documented DATA CONTRACT: `fee-accrued` here independently
  REIMPLEMENTS the same flat, non-compounded annual-rate formula
  `vcfund.nav/management-fee-accrued` uses, and `carry-accrued`
  independently reimplements the same profit*rate split
  `vcfund.registry/distribute-waterfall`'s `:gp-carry` computes off the
  post-return-of-capital-and-preferred profit (see `fundmgmt.governor`'s
  docstring for why a shared library would defeat the point of the
  independent re-verification these perform).

  Like `vcfund.registry`, there is no single international identifier
  standard for a management company's mandate/fee-drawdown/carry-
  distribution record, so this namespace does not invent one -- it
  validates required fields and assigns a sequence number.")

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is a
  human GP principal's act, not this actor's."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(defn register-mandate
  "Validate + construct an investment-mandate registration DRAFT -- the
  management company's own record of the LPA-authorized management-fee
  rate ceiling (and OPTIONALLY the carry-rate ceiling) for this fund.
  Pure function -- `annual-fee-rate-cap`/`carry-rate-cap` are REAL facts
  from the actual LPA, never invented here.

  `carry-rate-cap` -- OPTIONAL (nil, the 3-arity form, when this fund
  does not authorize carry distribution through this company, or the
  cap has not been recorded yet); `:carry/distribute` HARD-holds until
  a mandate WITH a `carry-rate-cap` is on file (see
  `fundmgmt.governor`)."
  ([annual-fee-rate-cap effective-date sequence]
   (register-mandate annual-fee-rate-cap nil effective-date sequence))
  ([annual-fee-rate-cap carry-rate-cap effective-date sequence]
   (when-not (<= 0 annual-fee-rate-cap 1)
     (throw (ex-info "mandate: annual-fee-rate-cap must be in [0,1]" {})))
   (when (and carry-rate-cap (not (<= 0 carry-rate-cap 1)))
     (throw (ex-info "mandate: carry-rate-cap must be in [0,1]" {})))
   (when-not (and effective-date (not= effective-date ""))
     (throw (ex-info "mandate: effective-date required" {})))
   (when (< sequence 0)
     (throw (ex-info "mandate: sequence must be >= 0" {})))
   (let [mandate-number (str "MANDATE-" (zero-pad sequence 6))
         record (cond-> {"record_id" mandate-number
                         "kind" "mandate-draft"
                         "annual_fee_rate_cap" (double annual-fee-rate-cap)
                         "effective_date" effective-date
                         "immutable" true}
                  carry-rate-cap (assoc "carry_rate_cap" (double carry-rate-cap)))]
     {"record" record "mandate_number" mandate-number
      "certificate" (unsigned-certificate "MandateCertificate" mandate-number mandate-number)})))

(defn fee-accrued
  "Flat, non-compounded annual-rate management-fee accrual --
  INDEPENDENTLY reimplemented from `vcfund.nav/management-fee-accrued`'s
  base-case formula (see ns docstring for why that is deliberate, not
  duplication). Does NOT reimplement that fn's optional investment-
  period step-down -- an honestly narrower scope, see
  `fundmgmt.governor`'s docstring for what this means for the
  independent-recompute check."
  [fee-basis annual-fee-rate years-elapsed]
  (when (neg? fee-basis) (throw (ex-info "fee-accrued: fee-basis must be >= 0" {})))
  (when (neg? annual-fee-rate) (throw (ex-info "fee-accrued: annual-fee-rate must be >= 0" {})))
  (when (neg? years-elapsed) (throw (ex-info "fee-accrued: years-elapsed must be >= 0" {})))
  (* (double fee-basis) (double annual-fee-rate) (double years-elapsed)))

(defn register-fee-drawdown
  "Validate + construct the management-fee DRAWDOWN DRAFT -- the
  management company's own legal act of drawing the fee an UPSTREAM
  `cloud-itonami-isic-6499` (`vcfund.nav/fund-nav-report`) fee-accrual
  fact computed. Pure function -- does not touch any real banking/wire
  system; it builds the RECORD a GP principal would keep. `fundmgmt.
  governor` independently re-verifies the accrual figure and the rate
  against this company's own recorded mandate, and blocks a double-draw
  of the same `period`, before this is ever allowed to commit."
  [period fee-basis annual-fee-rate years-elapsed accrued-amount sequence]
  (when-not (and period (not= period ""))
    (throw (ex-info "fee-drawdown: period required" {})))
  (when (neg? fee-basis) (throw (ex-info "fee-drawdown: fee-basis must be >= 0" {})))
  (when (neg? annual-fee-rate) (throw (ex-info "fee-drawdown: annual-fee-rate must be >= 0" {})))
  (when (neg? years-elapsed) (throw (ex-info "fee-drawdown: years-elapsed must be >= 0" {})))
  (when (neg? accrued-amount) (throw (ex-info "fee-drawdown: accrued-amount must be >= 0" {})))
  (when (< sequence 0)
    (throw (ex-info "fee-drawdown: sequence must be >= 0" {})))
  (let [drawdown-number (str "DRAWDOWN-" (zero-pad sequence 6))
        record {"record_id" drawdown-number
                "kind" "fee-drawdown-draft"
                "period" period
                "fee_basis" (double fee-basis)
                "annual_fee_rate" (double annual-fee-rate)
                "years_elapsed" (double years-elapsed)
                "accrued_amount" (double accrued-amount)
                "immutable" true}]
    {"record" record "drawdown_number" drawdown-number
     "certificate" (unsigned-certificate "FeeDrawdownCertificate" drawdown-number drawdown-number)}))

(defn carry-accrued
  "GP carried-interest accrual -- INDEPENDENTLY reimplemented from
  `vcfund.registry/distribute-waterfall`'s `:gp-carry` computation
  (`after-preferred-profit * carry-rate`, see ns docstring for why that
  is deliberate, not duplication). `after-preferred-profit` is the
  profit remaining AFTER return-of-capital and the preferred return are
  paid out (`vcfund.registry/distribute-waterfall`'s `:gp-carry` +
  `:lp-residual-profit`, since that fn's `after-preferred` local isn't
  itself exposed in its return -- an operator/integration reconstructs
  it from those two fields, the documented data-contract translation)."
  [after-preferred-profit carry-rate]
  (when (neg? after-preferred-profit) (throw (ex-info "carry-accrued: after-preferred-profit must be >= 0" {})))
  (when-not (<= 0 carry-rate 1) (throw (ex-info "carry-accrued: carry-rate must be in [0,1]" {})))
  (* (double after-preferred-profit) (double carry-rate)))

(defn register-carry-distribution
  "Validate + construct the GP carry-DISTRIBUTION DRAFT -- the
  management company's own legal act of taking its carried-interest
  share off an UPSTREAM `cloud-itonami-isic-6499` (`vcfund.registry/
  register-distribution`) exit-distribution fact. Pure function -- does
  not touch any real banking/wire system; it builds the RECORD a GP
  principal would keep. `fundmgmt.governor` independently re-verifies
  the carry figure and the rate against this company's own recorded
  mandate, and blocks a double-distribution of the same
  `commitment-number`, before this is ever allowed to commit."
  [commitment-number after-preferred-profit carry-rate gp-carry sequence]
  (when-not (and commitment-number (not= commitment-number ""))
    (throw (ex-info "carry-distribution: commitment-number required" {})))
  (when (neg? after-preferred-profit) (throw (ex-info "carry-distribution: after-preferred-profit must be >= 0" {})))
  (when-not (<= 0 carry-rate 1) (throw (ex-info "carry-distribution: carry-rate must be in [0,1]" {})))
  (when (neg? gp-carry) (throw (ex-info "carry-distribution: gp-carry must be >= 0" {})))
  (when (< sequence 0)
    (throw (ex-info "carry-distribution: sequence must be >= 0" {})))
  (let [distribution-number (str "CARRY-" (zero-pad sequence 6))
        record {"record_id" distribution-number
                "kind" "carry-distribution-draft"
                "commitment_number" commitment-number
                "after_preferred_profit" (double after-preferred-profit)
                "carry_rate" (double carry-rate)
                "gp_carry" (double gp-carry)
                "immutable" true}]
    {"record" record "distribution_number" distribution-number
     "certificate" (unsigned-certificate "CarryDistributionCertificate" distribution-number distribution-number)}))

(defn append
  "Append a mandate/drawdown/carry-distribution record, returning a NEW
  list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
