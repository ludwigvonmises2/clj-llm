(ns trade.metrics
  "Professional performance metrics on net daily returns. Crypto trades
  every day, so the annualization factor is 365; switch to 252 for
  equities.")

(set! *warn-on-reflection* true)

(def ^:private annual 365.0)

(defn net-returns
  "Daily net returns implied by an equity curve."
  [curve]
  (vec (for [i (range 1 (count curve))]
         (- (/ (double (nth curve i)) (nth curve (dec i))) 1.0))))

(defn sharpe
  ([rets] (sharpe rets annual))
  ([rets ppy]
   (let [n  (count rets)
         m  (/ (reduce + rets) n)
         sd (Math/sqrt (/ (reduce + (map #(let [d (- % m)] (* d d)) rets)) (max 1 (dec n))))]
     (if (zero? sd) 0.0 (* (/ m sd) (Math/sqrt (double ppy)))))))

(defn sortino
  ([rets] (sortino rets annual))
  ([rets ppy]
   (let [n  (count rets)
         m  (/ (reduce + rets) n)
         dd (Math/sqrt (/ (reduce + (map #(let [d (min % 0.0)] (* d d)) rets))
                          (dec n)))]
     (if (zero? dd) Double/POSITIVE_INFINITY (* (/ m dd) (Math/sqrt (double ppy)))))))

(defn max-drawdown
  "Largest peak-to-trough decline of the equity curve, as a positive
  fraction."
  [curve]
  (loop [i 1 peak (double (first curve)) worst 0.0]
    (if (>= i (count curve))
      worst
      (let [eq (double (nth curve i))
            pk (max peak eq)
            dd (- 1.0 (/ eq pk))]
        (recur (inc i) pk (max worst dd))))))

(defn cagr
  "Compound annual growth rate given the number of calendar days covered."
  [curve n-days]
  (let [nd (long n-days)]
    (if (pos? nd)
      (dec (Math/pow (double (peek curve)) (/ annual nd)))
      0.0)))

(defn annualized-vol
  ([rets] (annualized-vol rets annual))
  ([rets ppy]
   (let [n  (count rets)
         m  (/ (reduce + rets) n)
         vv (/ (reduce + (map #(let [d (- % m)] (* d d)) rets)) (max 1 (dec n)))]
     (* (Math/sqrt vv) (Math/sqrt (double ppy))))))

(defn summarize
  "Full professional summary of a backtest result (from trade.backtest/run).
  :ppy = periods per year of the bar interval (365 daily, 8760 hourly)."
  [{:keys [curve final-equity total-turnover profit-factor hit-rate ppy]
    :or {ppy 365}}]
  (let [rets (net-returns curve)
        bars (dec (count curve))
        days (* bars (/ 365.0 ppy))]
    {:final-equity  final-equity
     :total-return  (dec (double final-equity))
     :cagr          (cagr curve (long (* bars annual (/ 1.0 ppy))))
     :sharpe        (sharpe rets ppy)
     :sortino       (sortino rets ppy)
     :ann-vol       (annualized-vol rets ppy)
     :max-drawdown  (max-drawdown curve)
     :hit-rate      hit-rate
     :profit-factor profit-factor
     :turnover-yr   (* total-turnover (/ annual days))
     :days          (long days)}))

;; ---------------------------------------------------------------------------
;; Professional panel (Calmar, recovery, underwater anatomy, tails)
;; ---------------------------------------------------------------------------

(defn calmar
  "CAGR per unit of max drawdown."
  [cagr-v maxdd]
  (if (zero? maxdd) 0.0 (/ cagr-v maxdd)))

(defn recovery-factor
  "Total return per unit of max drawdown."
  [total-return maxdd]
  (if (zero? maxdd) 0.0 (/ total-return maxdd)))

(defn underwater-stats
  "Drawdown anatomy: {:max-dd :max-duration-bars :underwater-frac} — how
  deep, how LONG, and how often the system is below its peak."
  [curve]
  (loop [i 1, peak (double (first curve)), worst 0.0
         run 0, max-run 0, uw 0, total 0]
    (if (>= i (count curve))
      {:max-dd worst :max-duration-bars max-run
       :underwater-frac (/ uw (double (max 1 total)))}
      (let [eq (double (nth curve i))
            pk (max peak eq)
            dd (- 1.0 (/ eq pk))
            underwater? (pos? dd)]
        (recur (inc i) pk (max worst dd)
               (if underwater? (inc run) 0)
               (max max-run (if underwater? (inc run) run))
               (if underwater? (inc uw) uw)
               (inc total))))))

(defn cvar
  "Expected shortfall: the mean of the worst (1−p) fraction of returns."
  [rets ^double p]
  (let [xs (sort rets)
        kk (max 1 (long (* (count xs) (- 1.0 p))))]
    (/ (reduce + (take kk xs)) (double kk))))

(defn skew
  "Standardized third moment: negative = big losses dominate the tails."
  [rets]
  (let [n  (count rets)
        m  (/ (reduce + rets) n)
        sd (Math/sqrt (/ (reduce + (map #(let [d (- % m)] (* d d)) rets)) (max 1 (dec n))))]
    (if (zero? sd)
      0.0
      (/ (reduce + (map #(let [z (/ (- % m) sd)] (* z z z)) rets)) n))))

(defn excess-kurtosis
  "Standardized fourth moment minus 3: positive = fat tails."
  [rets]
  (let [n  (count rets)
        m  (/ (reduce + rets) n)
        sd (Math/sqrt (/ (reduce + (map #(let [d (- % m)] (* d d)) rets)) (max 1 (dec n))))]
    (if (zero? sd)
      0.0
      (- (/ (reduce + (map #(let [z (/ (- % m) sd)] (* z z z z)) rets)) n) 3.0))))

(defn worst-day [rets] (reduce min rets))

(defn summarize-pro
  "The full professional panel over a backtest result."
  [{:keys [curve final-equity total-turnover profit-factor hit-rate ppy]
    :or {ppy 365}}]
  (let [base  (summarize {:curve curve :final-equity final-equity
                          :total-turnover total-turnover :profit-factor profit-factor
                          :hit-rate hit-rate :ppy ppy})
        rets  (net-returns curve)
        uw    (underwater-stats curve)]
    (assoc base
           :calmar          (calmar (:cagr base) (:max-drawdown base))
           :recovery-factor (recovery-factor (:total-return base) (:max-drawdown base))
           :max-dd-bars     (:max-duration-bars uw)
           :underwater-frac (:underwater-frac uw)
           :cvar95          (cvar rets 0.95)
           :cvar99          (cvar rets 0.99)
           :skew            (skew rets)
           :ex-kurtosis     (excess-kurtosis rets)
           :worst-day       (worst-day rets))))

(defn print-pro
  "One full professional panel line-block."
  [{:keys [final-equity cagr sharpe sortino ann-vol max-drawdown calmar
           recovery-factor max-dd-bars underwater-frac cvar95 cvar99 skew
           ex-kurtosis worst-day profit-factor hit-rate turnover-yr days]}]
  (println (format "  equity %.3f | CAGR %+.1f%% | Sharpe %.2f | Sortino %.2f | Calmar %.2f | vol %.1f%%"
                   final-equity (* 100 cagr) sharpe sortino calmar (* 100 ann-vol)))
  (println (format "  maxDD %.1f%% | DD-barra más larga %d | %%bajo-agua %.0f%% | recovery %.2f | turn %.1fx/yr | %d barras"
                   (* 100 max-drawdown) max-dd-bars (* 100 underwater-frac)
                   recovery-factor turnover-yr days))
  (println (format "  CVaR95 %.2f%% | CVaR99 %.2f%% | peor día %.2f%% | skew %.2f | kurtosis exc. %.2f | hit %.0f%% | PF %.2f"
                   (* 100 cvar95) (* 100 cvar99) (* 100 worst-day)
                   skew ex-kurtosis (* 100 hit-rate) profit-factor)))
