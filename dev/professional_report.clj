;; Full professional report: strategy panel at 1x and 2x costs per asset.
;; Cost sensitivity: the edge must survive at 2x costs or it was simulation
;; artifact. Run: clojure -M dev/professional_report.clj
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(let [assets ["BTCUSDT" "ETHUSDT" "SOLUSDT"]
      base-cfg {:fast 20 :slow 60 :target-vol 0.20 :vol-window 30 :max-lev 2.0 :ppy 365}]
  (doseq [a assets]
    (let [closes (data/closes (data/load-binance-json (str "data/market/" a "_1d.json")))
          sigs (signals/sma-crossover (assoc base-cfg :closes closes))
          run (fn [cost] (metrics/summarize-pro
                          (backtest/run {:closes closes :sigs sigs :cost-rate cost})))
          s1 (run 0.0015)
          s2 (run 0.0030)]
      (println (str "\n═══ " a " — costes 1× (15 bps) ═══"))
      (metrics/print-pro s1)
      (println (str "═══ " a " — costes 2× (30 bps): la ventaja debe sobrevivir ═══"))
      (metrics/print-pro s2)
      (println (format "  SENSIBILIDAD: Sharpe 1×=%.2f  2×=%.2f  → %s"
                       (:sharpe s1) (:sharpe s2)
                       (if (and (pos? (:sharpe s1)) (> (:sharpe s2) 0.5))
                         "sobrevive al doble de costes" "frágil a costes"))))))
