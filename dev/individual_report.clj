;; Per-asset individual validation: triple-trend vs SMA 20/60 vs buy&hold.
;; Run: clojure -M dev/individual_report.clj
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(def stocks ["AAPL" "MSFT" "NVDA" "AMZN" "META" "GOOGL"])
(def cost 0.0002)

(defn run-sig [closes sigs cost]
  (metrics/summarize-pro
   (assoc (backtest/run {:closes closes :sigs sigs :cost-rate cost}) :ppy 365)))

(println "\nper-asset: triple-trend (multi-scale + absoluto, vol-target) vs SMA 20/60 vs B&H")
(println (format "  %-6s | %-28s | %-28s | %-28s"
                 "stock" "B&H" "SMA 20/60" "TRIPLE-TREND"))
(doseq [s stocks]
  (let [closes (data/closes (data/load-alpaca-json
                             (str "data/market/stock_" s "_alpaca.json")))
        bh  (run-sig closes (vec (concat [0.0] (repeat (dec (count closes)) 1.0))) 0.0)
        sma (run-sig closes
                     (signals/sma-crossover {:closes closes :fast 20 :slow 60
                                             :target-vol 0.20 :vol-window 30
                                             :max-lev 2.0 :ppy 365}) cost)
        tt  (run-sig closes
                     (signals/triple-trend {:closes closes :f1 20 :s1 60 :mid 100
                                            :target-vol 0.20 :vol-window 30
                                            :max-lev 2.0 :ppy 365}) cost)
        line (fn [nm x]
               (format "    %-14s Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | Calmar %.2f"
                       nm (:sharpe x) (* 100 (:cagr x)) (* 100 (:max-drawdown x)) (:calmar x)))]
    (println (format "  %-6s |" s))
    (println (line "B&H" bh))
    (println (line "SMA" sma))
    (println (line "TRIPLE" tt))
    (let [win (and (> (:sharpe tt) (:sharpe bh)) (< (:max-drawdown tt) (:max-drawdown bh)))
          cmw (> (:calmar tt) (:calmar bh))]
      (println (str "    → " (cond win "GANA Sharpe+maxDD" cmw "GANA Calmar" :else "pierde") "\n")))))
