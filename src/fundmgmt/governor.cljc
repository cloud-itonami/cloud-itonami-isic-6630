(ns fundmgmt.governor
  "FundManagementGovernor -- the independent compliance layer for the
  fund-management-company actor. Matches `:itonami.blueprint/governor
  :fund-management-governor` in this repo's `blueprint.edn`.

  This is the venue where `cloud-itonami-isic-6499`'s (the VC-fund
  investment actor's) computed management-fee accrual
  (`vcfund.nav/fund-nav-report`'s `:management-fees-accrued`) becomes a
  real fee the GP entity actually draws. This governor NEVER trusts the
  upstream fee-accrual report's own numbers as-is: it independently
  RECOMPUTES the same flat-rate formula (`fundmgmt.registry/fee-accrued`,
  a deliberately separate re-implementation -- see its docstring) from
  the report's OWN `:fee-basis`/`:annual-fee-rate`/`:years-elapsed`
  inputs and compares the result against the report's claimed
  `:accrued-amount`, exactly the same 'never trust the advisor's
  self-check' discipline `vcfund.governor/overcall-violations` applies
  inside its own repo, now applied ACROSS the repo boundary.

  Honestly bounded: unlike `trustfund.governor`'s allocation-mismatch
  check (which independently re-derives `:fee-basis` too, from its own
  subscription ledger), this governor has NO LP directory of its own --
  it cannot independently re-derive `:fee-basis` (the sum of LP
  commitments), only reapply the rate*basis*years FORMULA to whatever
  basis the upstream report claims. It also independently checks the
  upstream's claimed `:annual-fee-rate` against THIS company's own
  recorded mandate ceiling -- a check `vcfund.governor` has no concept of
  at all, since rate-cap compliance is fundamentally this company's own
  fiduciary responsibility, not the investment actor's.

  The SAME pattern governs `:carry/distribute`: `cloud-itonami-isic-
  6499`'s `vcfund.registry/distribute-waterfall` computes `:gp-carry`
  (the GP's carried-interest share of profit after return-of-capital and
  the preferred return) for one exit. This governor independently
  RECOMPUTES that split (`fundmgmt.registry/carry-accrued`, a separate
  re-implementation) from the upstream fact's own `:after-preferred-
  profit`/`:carry-rate`, and independently checks the claimed carry-rate
  against THIS company's own recorded `:carry-rate-cap` -- a SEPARATE
  ceiling from the fee-rate cap, since a fund's LPA can authorize a
  management fee without (yet) authorizing carry distribution through
  this company at all.

  Nine checks, in priority order. The first eight are HARD violations: a
  human approver CANNOT override them.

    1. Invalid rate cap -- for `:mandate/record`, is the proposed
       `:annual-fee-rate-cap` a fraction in [0,1]? Checked here so an
       out-of-range value HOLDS gracefully rather than throwing later,
       uncaught, when `fundmgmt.registry/register-mandate` validates it
       again at commit time.
    2. Invalid carry rate cap -- for `:mandate/record`, IF a `:carry-
       rate-cap` is proposed, is it a fraction in [0,1]? Same
       graceful-hold posture as check 1. Omitting `:carry-rate-cap`
       entirely is NOT a violation (a fee-only mandate is valid; it
       just does not yet authorize `:carry/distribute` -- see check 4).
    3. Mandate missing -- for `:fee/drawdown`, has an investment mandate
       (the LPA-authorized fee-rate ceiling) actually been recorded with
       this company? A fee cannot be drawn with no mandate on file.
    4. Carry mandate missing -- for `:carry/distribute`, has a mandate
       WITH a recorded `:carry-rate-cap` actually been registered? A
       fee-only mandate does not authorize carry distribution.
    5. Rate exceeds mandate -- does the upstream report's claimed
       `:annual-fee-rate` exceed THIS company's own recorded
       `:annual-fee-rate-cap`? Independently checked against the
       company's own fiduciary ceiling, never trusting that the upstream
       investment actor used a compliant rate.
    6. Carry rate exceeds mandate -- does the upstream fact's claimed
       `:carry-rate` exceed THIS company's own recorded `:carry-rate-
       cap`? Same independent check, for the carry side.
    7. Accrual mismatch -- does the upstream report's claimed
       `:accrued-amount` match what `fundmgmt.registry/fee-accrued`
       INDEPENDENTLY recomputes from the report's own fee-basis/rate/
       years-elapsed (within float tolerance)? A mismatch means either
       side's math has drifted, or the report was tampered with.
    8. Carry mismatch -- does the upstream fact's claimed `:gp-carry`
       match what `fundmgmt.registry/carry-accrued` INDEPENDENTLY
       recomputes from the fact's own after-preferred-profit/carry-rate
       (within float tolerance)?
    9. Confidence floor / actuation gate -- LLM confidence below
       threshold, OR the op is `:fee/drawdown`/`:carry/distribute` (REAL
       cash movements -- see README `Actuation`) -> escalate.

  Two more guards, double-draw and double-distribution prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream-fact comparison at all -- `double-draw-violations`
  refuses to draw the SAME `:period` twice, and `double-distribution-
  violations` refuses to distribute carry for the SAME `:commitment-
  number` twice, each off this company's OWN history."
  (:require [fundmgmt.registry :as registry]
            [fundmgmt.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Drawing a management fee and distributing GP carry are the two
  real-world actuation events this actor performs -- real cash moving
  between the fund and the GP entity, in either of the two economic
  forms a management company collects (fee, and carried interest)."
  #{:actuation/draw-fee :actuation/distribute-carry})

(def ^:private accrual-tolerance 1e-6)

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) accrual-tolerance))

(defn- invalid-rate-cap-violations
  "For `:mandate/record`, the proposed `:annual-fee-rate-cap` must be a
  fraction in [0,1] -- checked here so an out-of-range value HOLDS
  gracefully at the governor rather than throwing later, uncaught, when
  `fundmgmt.registry/register-mandate` validates it again at commit time."
  [{:keys [op]} proposal]
  (when (= op :mandate/record)
    (let [rate (get-in proposal [:value :annual-fee-rate-cap])]
      (when-not (and rate (<= 0 rate 1))
        [{:rule :invalid-rate-cap
          :detail "annual-fee-rate-capは[0,1]の範囲でなければならない"}]))))

(defn- invalid-carry-rate-cap-violations
  "For `:mandate/record`, IF a `:carry-rate-cap` is proposed, it must be
  a fraction in [0,1] -- same graceful-hold-before-throw posture as
  `invalid-rate-cap-violations`. Omitting `:carry-rate-cap` entirely is
  NOT a violation -- a fee-only mandate is valid."
  [{:keys [op]} proposal]
  (when (= op :mandate/record)
    (let [rate (get-in proposal [:value :carry-rate-cap])]
      (when (and rate (not (<= 0 rate 1)))
        [{:rule :invalid-carry-rate-cap
          :detail "carry-rate-capは[0,1]の範囲でなければならない"}]))))

(defn- mandate-missing-violations
  "For `:fee/drawdown`, an investment mandate (the LPA-authorized fee-rate
  ceiling) must actually be recorded with this company."
  [{:keys [op]} st]
  (when (= op :fee/drawdown)
    (when-not (store/mandate st)
      [{:rule :mandate-missing
        :detail "投資マンデート(報酬料率上限)が未登録の状態でのfee drawdown提案"}])))

(defn- carry-mandate-missing-violations
  "For `:carry/distribute`, a mandate WITH a recorded `:carry-rate-cap`
  must actually be on file -- a fee-only mandate (no carry-rate-cap) does
  not authorize carry distribution through this company."
  [{:keys [op]} st]
  (when (= op :carry/distribute)
    (when-not (:carry-rate-cap (store/mandate st))
      [{:rule :carry-mandate-missing
        :detail "carry-rate-cap付きの投資マンデートが未登録の状態でのcarry distribution提案"}])))

(defn- rate-exceeds-mandate-violations
  "For `:fee/drawdown`, the upstream report's claimed `:annual-fee-rate`
  must not exceed THIS company's own recorded `:annual-fee-rate-cap`."
  [{:keys [op upstream-fee-report]} st]
  (when (= op :fee/drawdown)
    (when-let [m (store/mandate st)]
      (when (> (double (:annual-fee-rate upstream-fee-report))
               (:annual-fee-rate-cap m))
        [{:rule :rate-exceeds-mandate
          :detail "upstream報告の料率がmandateの上限を超過"}]))))

(defn- carry-rate-exceeds-mandate-violations
  "For `:carry/distribute`, the upstream fact's claimed `:carry-rate`
  must not exceed THIS company's own recorded `:carry-rate-cap`."
  [{:keys [op upstream-distribution-report]} st]
  (when (= op :carry/distribute)
    (when-let [cap (:carry-rate-cap (store/mandate st))]
      (when (> (double (:carry-rate upstream-distribution-report)) cap)
        [{:rule :carry-rate-exceeds-mandate
          :detail "upstream報告のcarry率がmandateの上限を超過"}]))))

(defn- accrual-mismatch-violations
  "For `:fee/drawdown`, independently recompute the accrual
  (`fundmgmt.registry/fee-accrued`) from the upstream report's OWN
  fee-basis/rate/years-elapsed and compare it against the report's
  claimed `:accrued-amount` -- never trust that claim as-is."
  [{:keys [op upstream-fee-report]}]
  (when (= op :fee/drawdown)
    (let [{:keys [fee-basis annual-fee-rate years-elapsed accrued-amount]} upstream-fee-report
          recomputed (registry/fee-accrued fee-basis annual-fee-rate years-elapsed)]
      (when-not (close? recomputed accrued-amount)
        [{:rule :accrual-mismatch
          :detail (str "upstream報告の accrued_amount=" accrued-amount
                      " が独立再計算結果=" recomputed " と一致しない")}]))))

(defn- carry-mismatch-violations
  "For `:carry/distribute`, independently recompute the carry
  (`fundmgmt.registry/carry-accrued`) from the upstream fact's OWN
  after-preferred-profit/carry-rate and compare it against the fact's
  claimed `:gp-carry` -- never trust that claim as-is."
  [{:keys [op upstream-distribution-report]}]
  (when (= op :carry/distribute)
    (let [{:keys [after-preferred-profit carry-rate gp-carry]} upstream-distribution-report
          recomputed (registry/carry-accrued after-preferred-profit carry-rate)]
      (when-not (close? recomputed gp-carry)
        [{:rule :carry-mismatch
          :detail (str "upstream報告の gp_carry=" gp-carry
                      " が独立再計算結果=" recomputed " と一致しない")}]))))

(defn- double-draw-violations
  "For `:fee/drawdown`, refuses to draw the SAME `:period` twice, off
  this company's own drawdown history -- needs no upstream comparison at
  all."
  [{:keys [op period]} st]
  (when (= op :fee/drawdown)
    (when (store/period-already-drawn? st period)
      [{:rule :double-draw
        :detail (str period " は既にfee drawdown済み")}])))

(defn- double-distribution-violations
  "For `:carry/distribute`, refuses to distribute carry for the SAME
  `:commitment-number` twice, off this company's own carry-distribution
  history -- needs no upstream comparison at all."
  [{:keys [op commitment-number]} st]
  (when (= op :carry/distribute)
    (when (store/commitment-already-distributed? st commitment-number)
      [{:rule :double-distribution
        :detail (str commitment-number " は既にcarry distribution済み")}])))

(defn check
  "Censors a FundManager-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (invalid-rate-cap-violations request proposal)
                           (invalid-carry-rate-cap-violations request proposal)
                           (mandate-missing-violations request st)
                           (carry-mandate-missing-violations request st)
                           (rate-exceeds-mandate-violations request st)
                           (carry-rate-exceeds-mandate-violations request st)
                           (accrual-mismatch-violations request)
                           (carry-mismatch-violations request)
                           (double-draw-violations request st)
                           (double-distribution-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
