;; Ensemble policy evaluation: the mean of the four mechanical experts.
;; By construction it never bets the account on one regime classification.
;; Acceptance: Sharpe > B&H AND maxDD < B&H, on synthetic and real data.
;; Run: clojure -M dev/ensemble_eval.clj
(require '[trade.regime :as reg]
         '[trade.data :as data]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(defn ensemble-exposures
  "Mean of the four mechanical experts over a price series."
  [closes]
  (let [vs (mapv #(reg/expert-vec % closes) reg/regimes)]
    (mapv (fn [d]
            (/ (reduce + (map #(nth % d) vs)) (double (count vs))))
          (range (count closes)))))

(defn run-sig
  [closes sigs]
  (metrics/summarize-pro
   (assoc (backtest/run {:closes closes :sigs sigs :cost-rate 0.0015}) :ppy 365)))

(defn bh-sig [closes]
  (metrics/summarize-pro
   (assoc (backtest/run {:closes closes :sigs (vec (repeat (count closes) 1.0))
                         :cost-rate 0.0}) :ppy 365)))

(defn row [name s b]
  (let [pass (and (> (:sharpe s) (:sharpe b)) (< (:max-drawdown s) (:max-drawdown b)))]
    (println (format "  %-8s Sharpe %+.2f (%+.2f) | maxDD %.1f%% (%.1f%%) | %s"
                     name (:sharpe s) (:sharpe b)
                     (* 100 (:max-drawdown s)) (* 100 (:max-drawdown b))
                     (if pass "GANA" "pierde")))
    pass))

(println "═══ ENSEMBLE de mecánicas (media de 4 expertos, sin apalancamiento) ═══")
;; synthetic matrix
(doseq [kind reg/regimes]
  (let [rows (for [i (range 20)]
               (let [rets (vec (reg/gen-scenario kind (+ 300000 i)))
                     closes (reduce (fn [acc r] (conj acc (* (peek acc) (+ 1.0 r)))) [100.0] rets)
                     ens (ensemble-exposures closes)
                     bh (vec (repeat (count closes) 1.0))]
                 {:s (run-sig closes ens) :b (bh-sig closes)}))
        med (fn [f k] (let [xs (sort (map #(f (k %)) rows))] (nth xs (quot (count xs) 2))))
        verdict (row (name kind)
                     {:sharpe (med :sharpe :s) :max-drawdown (med :max-drawdown :s)}
                     {:sharpe (med :sharpe :b) :max-drawdown (med :max-drawdown :b)})]
    verdict))
;; real BTC
(let [closes (data/closes (data/load-binance-json "data/market/BTCUSDT_1d.json"))
      ens (ensemble-exposures closes)
      s (run-sig closes ens)
      b (bh-sig closes)]
  (row "BTC real" s b)
  (println (format "  ensemble BTC: CAGR %+.1f%% | Sharpe %.2f | Sortino %.2f | maxDD %.1f%% | %%bajo-agua %.0f%%"
                   (* 100 (:cagr s)) (:sharpe s) (:sortino s)
                   (* 100 (:max-drawdown s)) (* 100 (:underwater-frac s)))))
