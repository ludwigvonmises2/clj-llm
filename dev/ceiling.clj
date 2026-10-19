;; Perfect-foresight ceiling: the MAXIMUM theoretical alpha for a daily
;; system that knows tomorrow's direction. Unreachable — it quantifies
;; how much of the gap our mechanical systems can ever close.
;; Run: clojure -M dev/ceiling.clj
(require '[trade.data :as data]
         '[trade.metrics :as metrics])

(doseq [a ["BTCUSDT" "ETHUSDT" "SOLUSDT" "AAPL" "META" "GOOGL" "NVDA"]]
  (let [f1 (str "data/market/" a "_1d.json")
        f2 (str "data/market/stock_" a "_alpaca.json")
        file (if (.exists (java.io.File. f1)) f1 f2)
        loader (if (.exists (java.io.File. f1)) data/load-binance-json data/load-alpaca-json)
        closes (data/closes (loader file))
        rets (data/simple-returns closes)
        ;; oracle: long if tomorrow > 0 else short — earns |r| every day
        oracle (map #(Math/abs %) (rest rets))
        curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] oracle)
        s (metrics/summarize-pro {:curve curve :final-equity (peek curve)
                                  :total-turnover 0.0 :profit-factor 0.0
                                  :hit-rate 0.0 :ppy 365})]
    (println (format "  %-8s TECHO TEÓRICO: CAGR %+.0f%% | Sharpe %.1f (visión perfecta diaria)"
                     a (* 100 (:cagr s)) (:sharpe s)))))
