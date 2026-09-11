(ns fundmgmt.registry
  "Pure-function investment-mandate, management-fee-drawdown, carry-
  distribution and guideline-disclosure record construction -- an
  append-only fund-management-company book-of-record draft.

  This is the management company's own registry, distinct from (and
  never a code dependency of) `cloud-itonami-isic-6499`'s `vcfund.
  registry`/`vcfund.nav`/`vcfund.concentration`. The two repos
  interoperate ONLY through a documented DATA CONTRACT: `fee-accrued`
  here independently REIMPLEMENTS the same flat, non-compounded
  annual-rate formula `vcfund.nav/management-fee-accrued` uses, and
  `carry-accrued` independently reimplements the same profit*rate split
  `vcfund.registry/distribute-waterfall`'s `:gp-carry` computes off the
  post-return-of-capital-and-preferred profit (see `fundmgmt.governor`'s
  docstring for why a shared library would defeat the point of the
  independent re-verification these perform). Guideline disclosure is
  narrower still -- there is no formula to reimplement, only a direct
  comparison of the upstream `vcfund.concentration/concentration-
  report`'s reported fractions against THIS company's own mandate-
  defined caps (see `fundmgmt.governor`'s docstring).

  Like `vcfund.registry`, there is no single international identifier
  standard for a management company's mandate/fee-drawdown/carry-
  distribution/guideline-disclosure record, so this namespace does not
  invent one -- it validates required fields and assigns a sequence
  number.")

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

(defn- caps->str-doubles
  "Coerce a `{name fraction ..}` guideline-cap map's values to doubles.
  Keys are already the sector/investment-stage NAME strings (the same
  free-text values `vcfund.concentration/concentration-report`'s
  `:by-sector`/`:by-investment-stage` breakdown uses)."
  [caps]
  (into {} (map (fn [[k v]] [k (double v)])) caps))

(defn register-mandate
  "Validate + construct an investment-mandate registration DRAFT -- the
  management company's own record of the LPA-authorized management-fee
  rate ceiling (OPTIONALLY the carry-rate ceiling, OPTIONALLY sector/
  investment-stage concentration caps) for this fund. Pure function --
  every cap is a REAL fact from the actual LPA, never invented here.

  `carry-rate-cap` -- OPTIONAL (nil, the 3-arity form, when this fund
  does not authorize carry distribution through this company, or the
  cap has not been recorded yet); `:carry/distribute` HARD-holds until
  a mandate WITH a `carry-rate-cap` is on file (see
  `fundmgmt.governor`).

  `guideline-caps` -- OPTIONAL `{:sector-caps {name fraction ..}
  :stage-caps {name fraction ..}}` (the 5-arity form); `:guideline/
  disclose` HARD-holds until a mandate with AT LEAST one of these caps
  is on file (see `fundmgmt.governor`). A cap map with no entry for a
  given sector/stage means no limit is enforced for it -- omitting a cap
  is not itself a violation."
  ([annual-fee-rate-cap effective-date sequence]
   (register-mandate annual-fee-rate-cap nil effective-date sequence))
  ([annual-fee-rate-cap carry-rate-cap effective-date sequence]
   (register-mandate annual-fee-rate-cap carry-rate-cap nil effective-date sequence))
  ([annual-fee-rate-cap carry-rate-cap guideline-caps effective-date sequence]
   (when-not (<= 0 annual-fee-rate-cap 1)
     (throw (ex-info "mandate: annual-fee-rate-cap must be in [0,1]" {})))
   (when (and carry-rate-cap (not (<= 0 carry-rate-cap 1)))
     (throw (ex-info "mandate: carry-rate-cap must be in [0,1]" {})))
   (let [{:keys [sector-caps stage-caps]} guideline-caps]
     (doseq [[_ v] sector-caps]
       (when-not (<= 0 v 1) (throw (ex-info "mandate: every sector-caps value must be in [0,1]" {}))))
     (doseq [[_ v] stage-caps]
       (when-not (<= 0 v 1) (throw (ex-info "mandate: every stage-caps value must be in [0,1]" {}))))
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
                    carry-rate-cap (assoc "carry_rate_cap" (double carry-rate-cap))
                    (seq sector-caps) (assoc "sector_caps" (caps->str-doubles sector-caps))
                    (seq stage-caps) (assoc "stage_caps" (caps->str-doubles stage-caps)))]
       {"record" record "mandate_number" mandate-number
        "certificate" (unsigned-certificate "MandateCertificate" mandate-number mandate-number)}))))

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

(defn register-guideline-disclosure
  "Validate + construct the INVESTMENT-GUIDELINE-COMPLIANCE DISCLOSURE
  DRAFT -- the management company's own act of disclosing that the
  fund's actual portfolio composition, as reported by an UPSTREAM
  `cloud-itonami-isic-6499` (`vcfund.concentration/concentration-
  report`) fact, complies with the sector/investment-stage concentration
  limits THIS company's own recorded mandate defines. Pure function --
  does not touch any real compliance-reporting system; it builds the
  RECORD a GP principal would keep and actually send to LPs.
  `fundmgmt.governor` independently checks every CAPPED sector/stage's
  reported fraction against THIS company's own mandate ceiling before
  this is ever allowed to commit -- see its docstring.

  `by-sector`/`by-investment-stage` are the upstream report's OWN
  `{name-or-:unclassified {:amount .. :fraction ..}}` maps, carried
  through into the record verbatim (the same 'embed the upstream shape
  as-is' precedent `trustfund.sim`'s mixed-key waterfall fixture uses --
  no re-keying invented here)."
  [total-invested-at-cost by-sector by-investment-stage as-of-date sequence]
  (when (neg? total-invested-at-cost)
    (throw (ex-info "guideline-disclosure: total-invested-at-cost must be >= 0" {})))
  (when-not (and as-of-date (not= as-of-date ""))
    (throw (ex-info "guideline-disclosure: as-of-date required" {})))
  (when (< sequence 0)
    (throw (ex-info "guideline-disclosure: sequence must be >= 0" {})))
  (let [disclosure-number (str "GUIDELINE-" (zero-pad sequence 6))
        record {"record_id" disclosure-number
                "kind" "guideline-disclosure-draft"
                "total_invested_at_cost" (double total-invested-at-cost)
                "by_sector" by-sector
                "by_investment_stage" by-investment-stage
                "as_of_date" as-of-date
                "immutable" true}]
    {"record" record "disclosure_number" disclosure-number
     "certificate" (unsigned-certificate "GuidelineDisclosureCertificate" disclosure-number disclosure-number)}))

(defn append
  "Append a mandate/drawdown/carry-distribution/guideline-disclosure
  record, returning a NEW list (never mutate history in place)."
  [history result]
  (conj (vec history) (get result "record")))
