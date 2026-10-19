(ns trade.portfolio
  "Portfolio-level primitives shared by every validator: calendar
  alignment across markets (the intersection of UTC trading days, so a
  combined day-D return is the true same-day average), portfolio
  aggregation, professional panels and the walk-forward fold verdict
  that defines \"beats buy&hold\": higher Sharpe AND lower maxDD in
  every fold, with signals recomputed per fold from past bars only."
  (:require [clojure.set :as cset]
            [trade.data :as data]
            [trade.backtest :as backtest]
            [trade.metrics :as metrics]))

(set! *warn-on-reflection* true)

;; ---- calendar alignment ----------------------------------------------------
(defn day-key
  "UTC calendar date (yyyy-mm-dd) of a bar timestamp: Binance gives epoch
  milliseconds, Alpaca an RFC3339 string."
  [ts]
  (if (string? ts)
    (subs ts 0 10)
    (.. (java.time.Instant/ofEpochMilli (long ts))
        (atZone java.time.ZoneOffset/UTC)
        (toLocalDate)
        (toString))))

(defn day-close-map
  "Map of UTC day string -> close price for one daily bar series."
  [bars]
  (into {} (map (fn [b] [(day-key (:ts b)) (:c b)])) bars))

(defn common-days
  "Sorted vector of the days present in EVERY bar series (intersection
  of their UTC trading days)."
  [bar-series]
  (let [sets (map #(set (map day-key (map :ts %))) bar-series)]
    (vec (sort (reduce cset/intersection (first sets) (rest sets))))))

(defn close-matrix
  "One row of aligned closes per asset over `days`."
  [bars-vectors days]
  (mapv (fn [bars]
          (let [m (day-close-map bars)]
            (mapv #(get m %) days)))
        bars-vectors))

;; ---- aggregation and panels -------------------------------------------------
(defn curve-of
  "Equity curve compounding a net daily return stream."
  [rets]
  (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] rets))

(defn summary
  "Professional summary of a daily net-return stream."
  ([rets] (summary rets 365))
  ([rets ppy]
   (let [c (curve-of rets)]
     (metrics/summarize-pro {:curve c :final-equity (peek c)
                             :total-turnover 0.0 :profit-factor 0.0
                             :hit-rate 0.0 :ppy ppy}))))

(defn print-panel
  "One formatted panel line; returns the summary."
  ([name rets] (print-panel name rets 365))
  ([name rets ppy]
   (let [s (summary rets ppy)]
     (println (format "  %-24s Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | Calmar %.2f"
                      name (:sharpe s) (* 100 (:cagr s))
                      (* 100 (:max-drawdown s)) (:calmar s)))
     s)))

(defn mean-stream
  "Element-wise mean of return streams (equal-weight portfolio)."
  [streams]
  (apply mapv (fn [& rs] (/ (reduce + rs) (double (count rs)))) streams))

(defn sum-stream
  "Element-wise sum of return streams (each stream is a sleeve sized in
  full, e.g. one vol-targeted position per asset)."
  [streams]
  (apply mapv (fn [& rs] (reduce + rs)) streams))

(defn bh-returns
  "Buy&hold daily returns of one close series, aligned with the other
  return streams (no index-0 dummy)."
  [closes]
  (subvec (data/simple-returns closes) 1))

;; ---- walk-forward fold verification -----------------------------------------
(defn fold-verify
  "The rigorous meaning of \"gana siempre\": the series is split into
  `folds` consecutive windows after `warm` bars; in each fold the signals
  are recomputed on the PREFIX ending at the fold (decisions see past
  bars only), the strategy trades that prefix, and only the fold window's
  returns are scored. Beats buy&hold when both its Sharpe is higher and
  its maxDD lower. Prints per-fold lines when `verbose`; returns
  [wins total]."
  [closes sigfn ppy cost folds warm verbose]
  (let [n    (count closes)
        step (quot (- n warm) folds)
        fold-row
        (fn [f]
          (let [t0    (+ warm (* f step))
                t1    (min n (+ t0 step))
                c-sub (subvec closes 0 t1)
                bt    (backtest/run {:closes c-sub :sigs (sigfn c-sub)
                                     :cost-rate cost})
                fr    (subvec (metrics/net-returns (:curve bt)) (dec t0) (dec t1))
                sf    (summary fr ppy)
                br    (subvec (data/simple-returns c-sub) t0 t1)
                sb    (summary br ppy)]
            {:f    (inc f)
             :ss   (:sharpe sf) :bs (:sharpe sb)
             :sd   (:max-drawdown sf) :bd (:max-drawdown sb)
             :win? (and (> (:sharpe sf) (+ (:sharpe sb) 1e-9))
                        (< (:max-drawdown sf) (- (:max-drawdown sb) 1e-9)))}))]
    (let [rows (mapv fold-row (range folds))]
      (when verbose
        (doseq [r rows]
          (println (format "    pliegue %d | Sharpe %+.2f vs %+.2f %s | maxDD %.1f%% vs %.1f%% %s → %s"
                           (:f r) (:ss r) (:bs r) (if (> (:ss r) (:bs r)) "✓" "✗")
                           (* 100 (:sd r)) (* 100 (:bd r))
                           (if (< (:sd r) (:bd r)) "✓" "✗")
                           (if (:win? r) "GANA" "pierde")))))
      (let [w (count (filter :win? rows))]
        (when verbose
          (println (format "    → %d/%d pliegues GANAN" w (count rows))))
        [w (count rows)]))))
