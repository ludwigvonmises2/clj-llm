;; Stocks professional validation: Alpaca 5y daily bars, SMA crossover +
;; vol targeting vs buy&hold, with realistic large-cap costs (2 bps).
;; Run: clojure -M dev/stocks_report.clj
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(def stocks ["AAPL" "MSFT" "NVDA" "AMZN" "META" "GOOGL"])
(def cost 0.0002)

(defn load-alpaca [path] (data/load-alpaca-json path))

(println "\nstocks — SMA 20/60 + vol targeting 20% | costes 2 bps | Alpaca 5y daily")
(doseq [s stocks]
  (let [closes (data/closes (load-alpaca (str "data/market/stock_" s "_alpaca.json")))
        sigs (signals/sma-crossover {:closes closes :fast 20 :slow 60
                                     :target-vol 0.20 :vol-window 30
                                     :max-lev 2.0 :ppy 252})
        st (metrics/summarize-pro
            (backtest/run {:closes closes :sigs sigs :cost-rate cost}))
        bh (metrics/summarize-pro
            (backtest/run {:closes closes
                           :sigs (vec (concat [0.0] (repeat (dec (count closes)) 1.0)))
                           :cost-rate 0.0}))]
    (println (format "\n═══ %s (%d barras) ═══" s (count closes)))
    (println (format "  estrategia : Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | Calmar %.2f | underwater %.0f%% | CVaR95 %.2f%%"
                     (:sharpe st) (* 100 (:cagr st)) (* 100 (:max-drawdown st))
                     (:calmar st) (* 100 (:underwater-frac st)) (* 100 (:cvar95 st))))
    (println (format "  buy&hold   : Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | Calmar %.2f"
                     (:sharpe bh) (* 100 (:cagr bh)) (* 100 (:max-drawdown bh)) (:calmar bh)))
    (println (str "  veredicto  : "
                  (cond
                    (and (> (:sharpe st) (:sharpe bh)) (< (:max-drawdown st) (:max-drawdown bh))) "GANA (Sharpe y maxDD)"
                    (> (:calmar st) (:calmar bh)) "GANA en Calmar (riesgo-ajustado)"
                    :else "pierde")))))
