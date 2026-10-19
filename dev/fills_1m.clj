;; 1-minute fill-realistic backtest: AAPL via Alpaca IEX bars, SMA 2h/8h
;; crossover, vol-targeted, 1bp cost per turnover, execution at next
;; minute close (the engine's 1-bar lag = the real fill delay).
;; Run: clojure -M dev/fills_1m.clj
(require '[trade.data :as data]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics]
         '[trade.signals :as signals])

(def ppy 98280)                                     ; 390 min/day × 252 days
(def tdv (/ 0.20 (Math/sqrt (double ppy))))

(defn load-alpaca [path] (data/load-alpaca-json path))

(let [bars   (load-alpaca "data/market/AAPL_1m_alpaca.json")
      closes (mapv (fn [b] (double (:c b))) bars)
      sigs   (signals/sma-crossover {:closes closes :fast 120 :slow 480
                                     :target-vol 0.20 :vol-window 390
                                     :max-lev 1.0 :ppy ppy})
      s      (metrics/summarize-pro
              (assoc (backtest/run {:closes closes :sigs sigs :cost-rate 0.0001})
                     :ppy ppy))
      b      (metrics/summarize-pro
              (assoc (backtest/run {:closes closes
                                    :sigs (vec (concat [0.0] (repeat (dec (count closes)) 1.0)))
                                    :cost-rate 0.0}) :ppy ppy))]
  (println (format "\nAAPL 1m — %d barras | fills con lag de 1 minuto | costes 1 bp/turnover" (count closes)))
  (println (format "  tendencia  : Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | %%bajo-agua %.0f%% | turn %.0fx/yr"
                   (:sharpe s) (* 100 (:cagr s)) (* 100 (:max-drawdown s))
                   (* 100 (:underwater-frac s)) (:turnover-yr s)))
  (println (format "  buy&hold   : Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%%"
                   (:sharpe b) (* 100 (:cagr b)) (* 100 (:max-drawdown b))))
  (println (format "  CVaR95 tendencia %.3f%% | peor minuto %.2f%%"
                   (* 100 (:cvar95 s)) (* 100 (:worst-day s)))))
