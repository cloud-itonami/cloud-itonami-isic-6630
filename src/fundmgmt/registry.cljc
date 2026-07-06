(ns fundmgmt.registry
  "Pure-function investment-mandate and management-fee-drawdown record
  construction -- an append-only fund-management-company book-of-record
  draft.

  This is the management company's own registry, distinct from (and
  never a code dependency of) `cloud-itonami-isic-6499`'s `vcfund.
  registry`/`vcfund.nav`. The two repos interoperate ONLY through a
  documented DATA CONTRACT: `fee-accrued` here independently
  REIMPLEMENTS the same flat, non-compounded annual-rate formula
  `vcfund.nav/management-fee-accrued` uses (see
  `fundmgmt.governor`'s docstring for why a shared library would defeat
  the point of the independent re-verification it performs).

  Like `vcfund.registry`, there is no single international identifier
  standard for a management company's mandate/fee-drawdown record, so
  this namespace does not invent one -- it validates required fields and
  assigns a sequence number.")

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
  rate ceiling for this fund. Pure function -- `annual-fee-rate-cap` is a
  REAL fact from the actual LPA, never invented here."
  [annual-fee-rate-cap effective-date sequence]
  (when-not (<= 0 annual-fee-rate-cap 1)
    (throw (ex-info "mandate: annual-fee-rate-cap must be in [0,1]" {})))
  (when-not (and effective-date (not= effective-date ""))
    (throw (ex-info "mandate: effective-date required" {})))
  (when (< sequence 0)
    (throw (ex-info "mandate: sequence must be >= 0" {})))
  (let [mandate-number (str "MANDATE-" (zero-pad sequence 6))
        record {"record_id" mandate-number
                "kind" "mandate-draft"
                "annual_fee_rate_cap" (double annual-fee-rate-cap)
                "effective_date" effective-date
                "immutable" true}]
    {"record" record "mandate_number" mandate-number
     "certificate" (unsigned-certificate "MandateCertificate" mandate-number mandate-number)}))

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

(defn append
  "Append a mandate/drawdown record, returning a NEW list (never mutate
  history in place)."
  [history result]
  (conj (vec history) (get result "record")))
