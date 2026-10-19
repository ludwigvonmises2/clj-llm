;; Combined professional portfolio: crypto long/short momentum + stocks SMA
;; + inverse-asset SMA, each daily net-return stream equal-weighted.
;; Run: clojure -M dev/combined_portfolio.clj
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(def cost 0.0015)
(def stock-cost 0.0002)
(def smacfg {:fast 20 :slow 60 :target-vol 0.60 :vol-window 30 :max-lev 2.0 :ppy 365})
(def tdv (/ 0.60 (Math/sqrt 365.0)))

(defn curve-rets [closes sigs cost]
  (metrics/net-returns (:curve (backtest/run {:closes closes :sigs sigs :cost-rate cost}))))

(defn bh-rets [closes] (metrics/net-returns (vec (concat [0.0] (repeat (dec (count closes)) 1.0)))))

(defn panel [name rets]
  (let [curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] rets)
        s (metrics/summarize-pro {:curve curve :final-equity (peek curve)
                                  :total-turnover 0.0 :profit-factor 0.0
                                  :hit-rate 0.0 :ppy 365})]
    (println (format "  %-22s Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | Calmar %.2f"
                     name (:sharpe s) (* 100 (:cagr s)) (* 100 (:max-drawdown s)) (:calmar s)))
    s))

;; ---- stream 1: crypto long/short momentum (top3 long / bottom3 short) ----
(def cryptos (vec (sort (keep #(second (re-find #"(.+?)USDT_1d\.json" (.getName %)))
                              (.listFiles (java.io.File. "data/market"))))))
(def c-close (into {} (map (fn [a] [a (data/closes
                                       (data/load-binance-json
                                        (str "data/market/" a "USDT_1d.json")))]) cryptos)))
(def cn (apply min (map #(count (c-close %)) cryptos)))
(def c-exp (atom (into {} (map (fn [a] [a (vec (repeat cn 0.0))]) cryptos))))
(def look 30) (def rebal 7)
(doseq [t (range look cn rebal)]
  (let [moms (map (fn [a] (- (/ (nth (c-close a) t) (nth (c-close a) (- t look))) 1.0)) cryptos)
        ranked (sort-by second > (map-indexed vector moms))
        longs (take 3 (filter #(pos? (second %)) ranked))
        shorts (take 3 (filter #(neg? (second %)) ranked))]
    (doseq [[idx _m sign] (concat (map #(conj % 1.0) longs) (map #(conj % -1.0) shorts))
            :let [a (nth cryptos idx)
                  c (c-close a)
                  rr (map (fn [j] (- (/ (nth c j) (nth c (dec j))) 1.0))
                          (range (inc (- t 30)) (inc t)))
                  m2 (/ (reduce + rr) (count rr))
                  rv (if (pos? (count rr))
                       (Math/sqrt (/ (reduce + (map #(let [d (- % m2)] (* d d)) rr))
                                     (max 1 (dec (count rr)))))
                       0.0)
                  w (if (and rv (> rv 1e-9)) (min (/ tdv rv) 0.33) 0.0)]
            d (range t (min cn (+ t rebal)))]
      (swap! c-exp assoc-in [a d] (double (* sign w))))))
(def crypto-rets
  (apply mapv (fn [& rs] (reduce + rs))
         (map #(curve-rets (c-close %) (@c-exp %) cost) cryptos)))

;; ---- stream 2: stocks SMA crossover (mean of 6) ----
(def stocks ["AAPL" "MSFT" "NVDA" "AMZN" "META" "GOOGL"])
(def stock-rets
  (apply mapv (fn [& rs] (/ (reduce + rs) (double (count rs))))
         (map (fn [s]
                (let [closes (data/closes (data/load-alpaca-json
                                           (str "data/market/stock_" s "_alpaca.json")))
                      sigs (signals/sma-crossover (assoc smacfg :closes closes))]
                  (curve-rets closes sigs stock-cost)))
              stocks)))

;; ---- stream 3: inverse assets SMA crossover (mean of 5) ----
(def inverses ["TLT" "GLD" "VIXY" "SH" "UUP"])
(def inv-rets
  (apply mapv (fn [& rs] (/ (reduce + rs) (double (count rs))))
         (map (fn [s]
                (let [closes (data/closes (data/load-alpaca-json
                                           (str "data/market/stock_" s "_alpaca.json")))
                      sigs (signals/sma-crossover (assoc smacfg :closes closes))]
                  (curve-rets closes sigs stock-cost)))
              inverses)))

;; ---- combine: equal weight across the three streams ----
(def len (apply min (map count [crypto-rets stock-rets inv-rets])))
(def combined (mapv (fn [i] (/ (+ (nth crypto-rets i) (nth stock-rets i)) 2.0))
                    (range len)))
(def bh-combined (mapv (fn [i] (/ (+ (nth crypto-rets i) (nth stock-rets i)) 2.0))
                       (range len)))

(defn show [name rets]
  (let [curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] rets)
        s (metrics/summarize-pro {:curve curve :final-equity (peek curve)
                                  :total-turnover 0.0 :profit-factor 0.0
                                  :hit-rate 0.0 :ppy 365})]
    (println (format "  %-24s Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | Calmar %.2f"
                     name (:sharpe s) (* 100 (:cagr s)) (* 100 (:max-drawdown s)) (:calmar s)))
    s))

(println "\n═══ cartera combinada profesional ═══")
(show "cripto long/short" crypto-rets)
(show "stocks SMA" stock-rets)
(show "inversos SMA" inv-rets)
(def cs (show "COMBINADA" combined))
(println (format "\nOBJETIVO: Sharpe combinada %.2f — %s"
                   (:sharpe cs) (if (> (:sharpe cs) 1.0) "SUPERADO ✓" "en camino"))))
