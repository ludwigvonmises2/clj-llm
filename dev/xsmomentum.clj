;; Cross-sectional momentum over the 20 crypto assets: every rebalance go
;; long the top-3 by trailing 30d return and short the bottom-3, each
;; vol-targeted at 20% annualized, net of costs.
;; Run: clojure -M dev/xsmomentum.clj
(require '[trade.data :as data]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(def assets
  (vec (distinct
        (keep #(second (re-find #"(.+?)USDT_1d\.json" (.getName %)))
              (.listFiles (java.io.File. "data/market"))))))
(def look 30) (def rebal 7) (def cost 0.0015)
(def tdv (/ 0.20 (Math/sqrt 365.0)))

(let [closes-map (into {} (map (fn [a] [a (data/closes
                                           (data/load-binance-json
                                            (str "data/market/" a "USDT_1d.json")))])
                               assets))
      n (apply min (map #(count (closes-map %)) assets))
      exposures (atom (into {} (map (fn [a] [a (vec (repeat n 0.0))]) assets)))]
  (doseq [t (range look n rebal)]
    (let [moms (map (fn [a]
                      (- (/ (nth (closes-map a) t) (nth (closes-map a) (- t look))) 1.0))
                    assets)
          ranked (sort-by second > (map-indexed vector moms))
          longs (take 3 (filter #(pos? (second %)) ranked))
          shorts (take 3 (filter #(neg? (second %)) ranked))
          weight (fn [a]
                   (let [c (closes-map a)
                         rr (map (fn [j] (- (/ (nth c j) (nth c (dec j))) 1.0))
                                 (range (inc (- t 30)) (inc t)))
                         rv (if (pos? (count rr))
                              (let [m2 (/ (reduce + rr) (count rr))]
                                (Math/sqrt (/ (reduce + (map #(let [d (- % m2)] (* d d)) rr))
                                              (max 1 (dec (count rr))))))
                              0.0)]
                     (if (and rv (pos? rv) (> rv 1e-9)) (min (/ tdv rv) 0.5) 0.0)))]
      (doseq [[sgn ranked2] [[1.0 longs] [-1.0 shorts]]
              [idx _m] ranked2
              :let [a (nth assets idx) w (weight a) sign sgn]
              d (range t (min n (+ t rebal)))]
        (swap! exposures assoc-in [a d] (double (* sign w))))))
  (let [per-asset (map (fn [a]
                         (backtest/run {:closes (closes-map a)
                                        :sigs (@exposures a)
                                        :cost-rate cost}))
                       assets)
        port-rets (apply mapv (fn [& rs] (reduce + rs))
                         (map #(metrics/net-returns (:curve %)) per-asset))
        curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] port-rets)
        s (metrics/summarize-pro {:curve curve :final-equity (peek curve)
                                  :total-turnover 0.0 :profit-factor 0.0
                                  :hit-rate 0.0 :ppy 365})
        bh-rets (apply mapv (fn [& rs] (/ (reduce + rs) (double (count rs))))
                       (map #(metrics/net-returns (closes-map %)) assets))
        bh-curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] bh-rets)
        b (metrics/summarize-pro {:curve bh-curve :final-equity (peek bh-curve)
                                  :total-turnover 0.0 :profit-factor 0.0
                                  :hit-rate 0.0 :ppy 365})]
    (println (str "\nLong/short momentum: top-3 / bottom-3 de " (count assets)
                  " activos | lookback 30d | rebalanceo 7d | vol-target 20% | costes 15bps"))
    (println (format "  ESTRATEGIA equity %.3f | CAGR %+.1f%% | Sharpe %.2f | Sortino %.2f | maxDD %.1f%% | Calmar %.2f | turn %.1fx/yr"
                     (:final-equity s) (* 100 (:cagr s)) (:sharpe s) (:sortino s)
                     (* 100 (:max-drawdown s)) (:calmar s) (:turnover-yr s)))
    (println (format "  BUY&HOLD  equity %.3f | CAGR %+.1f%% | Sharpe %.2f | maxDD %.1f%%"
                     (:final-equity b) (* 100 (:cagr b)) (:sharpe b) (* 100 (:max-drawdown b))))
    (println (format "  VEREDICTO: %s (Sharpe %s, maxDD %s)"
                     (if (and (> (:sharpe s) (:sharpe b)) (< (:max-drawdown s) (:max-drawdown b)))
                       "GANA" "pierde")
                     (if (> (:sharpe s) (:sharpe b)) "✓" "✗")
                     (if (< (:max-drawdown s) (:max-drawdown b)) "✓" "✗")))))
