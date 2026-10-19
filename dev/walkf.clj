;; Walk-forward learning benchmark: our GPT learning the "language of
;; returns" vs the SMA crossover vs buy&hold — same engine, same costs.
;; Run: clojure -M dev/walkf.clj [asset]
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics]
         '[trade.model :as model])

(def asset (or (first *command-line-args*) "BTCUSDT"))

(let [bars   (data/load-binance-json (str "data/market/" asset "_1d.json"))
      closes (data/closes bars)
      rets   (data/simple-returns closes)
      cost   0.0015
      ;; baselines
      sma    (backtest/run {:closes closes
                            :sigs (signals/sma-crossover
                                   {:closes closes :fast 20 :slow 60
                                    :target-vol 0.20 :vol-window 30
                                    :max-lev 2.0 :ppy 365})
                            :cost-rate cost})
      sma-s  (metrics/summarize sma)
      bh     (backtest/run {:closes closes
                            :sigs (signals/buy-and-hold closes)
                            :cost-rate 0.0})
      bh-s   (metrics/summarize bh)
      ;; the learner
      {:keys [exposures stats]} (model/walk-forward
                                 {:rets rets :k 3 :t0 400 :step 50
                                  :steps 300 :lr 1e-3})
      mdl    (backtest/run {:closes closes :sigs exposures :cost-rate cost})
      mdl-s  (metrics/summarize mdl)]
  (println (str "\n" asset " — walk-forward learning (t0=400, step=50, "
                (count stats) " retrains)"))
  (println (format "  %-16s equity %.3f | CAGR %+.1f%% | Sharpe %.2f | maxDD %.1f%% | turn %.1fx/yr"
                   "GPT walk-forward" (:final-equity mdl-s) (* 100 (:cagr mdl-s))
                   (:sharpe mdl-s) (* 100 (:max-drawdown mdl-s)) (:turnover-yr mdl-s)))
  (println (format "  %-16s equity %.3f | CAGR %+.1f%% | Sharpe %.2f | maxDD %.1f%% | turn %.1fx/yr"
                   "SMA crossover" (:final-equity sma-s) (* 100 (:cagr sma-s))
                   (:sharpe sma-s) (* 100 (:max-drawdown sma-s)) (:turnover-yr sma-s)))
  (println (format "  %-16s equity %.3f | CAGR %+.1f%% | Sharpe %.2f | maxDD %.1f%%"
                   "buy&hold" (:final-equity bh-s) (* 100 (:cagr bh-s))
                   (:sharpe bh-s) (* 100 (:max-drawdown bh-s)))))
