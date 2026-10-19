(ns trade.learn
  "Real-time learning WITHOUT chasing your own P&L bar by bar (that
  measured a −14.8 Sharpe on 1m data — trailing performance is noise at
  intraday frequency). Learning here happens at three audited layers:

    1. EXECUTION LEARNING  — the journal records every decision with the
       fill actually obtained; realized slippage per bar updates the cost
       model the backtest uses. This is pure measurement, always valid.
    2. CHAMPION/CHALLENGER — a variant mechanic runs in SHADOW (decisions
       logged, no orders). It is promoted only when it beats the champion
       on the LIVE-accumulated window under the same fold-verify criterion
       used everywhere else in this repo. Same data, same standard, no
       exceptions.
    3. PARAMETER REVALIDATION — production parameters live in config;
       when enough new live bars accumulate, the champion re-runs its
       fold verification on the extended history. A parameter that no
       longer passes is demoted, not tuned.

  Every layer consumes only COMPLETED bars — the causality guarantee of
  the whole system applies unchanged in real time."
  (:require [trade.portfolio :as portfolio]))

(set! *warn-on-reflection* true)

;; ---- layer 1: execution journal ---------------------------------------------
(defn new-journal [] (atom {:decisions [] :fills []}))

(defn record-decision!
  "One completed bar: the exposure the signal wanted and the bar's own
  realized net return (known only AFTER the bar closes — the journal
  never sees a decision and its outcome at the same instant)."
  [journal ts target realized-net-return]
  (swap! journal update :decisions conj
         {:ts ts :target (double target) :ret (double realized-net-return)}))

(defn record-fill!
  "Real execution: intended vs actual price. Slippage is signed by the
  side of the trade (buying above intent / selling below intent is
  negative)."
  [journal ts side intended actual qty]
  (let [slip (case side :buy (/ (- (double actual) (double intended)) (double intended))
                   :sell (/ (- (double intended) (double actual)) (double intended)))]
    (swap! journal update :fills conj
           {:ts ts :side side :slippage slip :qty (double qty)})))

(defn realized-slippage
  "Mean realized slippage in bps — the number that replaces the assumed
  cost-rate in the next backtest round."
  [journal]
  (let [fs (:fills @journal)]
    (if (seq fs)
      (* 10000.0 (/ (reduce + (map :slippage fs)) (count fs)))
      nil)))

(defn decision-stats
  "Rolling hit-rate and per-bar Sharpe (annualized with `ppy`) of the
  recorded decisions — the live twin of summarize-pro."
  [journal ppy]
  (let [ds (:decisions @journal)
        rs (mapv :ret ds)]
    (when (>= (count rs) 2)
      (let [n  (count rs)
            m  (/ (reduce + rs) (double n))
            sd (Math/sqrt (/ (reduce + (map #(* (- % m) (- % m)) rs))
                             (double (dec n))))
            wins (count (filter pos? rs))]
        {:bars n :hit-rate (/ wins (double n))
         :sharpe (if (pos? sd) (* (/ m sd) (Math/sqrt (double ppy))) 0.0)}))))

;; ---- layer 2: champion / challenger -------------------------------------------
(defn shadow-score
  "Run a shadow mechanic over the live-accumulated closes with the SAME
  fold criterion as the offline system. Returns [wins total]."
  [closes sigfn ppy cost folds warm]
  (portfolio/fold-verify closes sigfn ppy cost folds warm false))

(defn promote?
  "Promote the challenger only on dominant evidence: strictly more fold
  wins than the champion over the same live window. Ties keep the
  champion (a tie is not knowledge)."
  [champion-wins challenger-wins]
  (> challenger-wins champion-wins))
