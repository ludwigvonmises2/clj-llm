;; Combined professional portfolio, daily and CALENDAR-ALIGNED across
;; markets: every stream lives on the same UTC days (the intersection of
;; all assets' trading days), so the combined return of day D is the true
;; same-day average — no index-based misalignment between markets.
;;
;;   stream 1: crypto cross-sectional long/short momentum (top-3/bottom-3)
;;   stream 2: stocks SMA 20/60 vol-targeted (6 tech names)
;;   stream 3: diversifier/inverse ETFs SMA 20/60 vol-targeted (5 names)
;;
;; Ends with the walk-forward fold verification: the combined portfolio
;; must beat the combined buy&hold in EVERY fold (Sharpe AND maxDD).
;; Run: clojure -M dev/combined_portfolio.clj
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics]
         '[clojure.set :as cset]
         '[trade.portfolio :as portfolio])

(def cost 0.0015)
(def stock-cost 0.0002)
(def smacfg {:fast 20 :slow 60 :target-vol 0.60 :vol-window 30 :max-lev 2.0 :ppy 365})
(def tdv (/ 0.20 (Math/sqrt 365.0)))

;; ---- universes and aligned data ------------------------------------------
(def cryptos
  (vec (sort (keep #(second (re-find #"(.+?)USDT_1d\.json" (.getName %)))
                   (.listFiles (java.io.File. "data/market"))))))
(def stocks ["AAPL" "MSFT" "NVDA" "AMZN" "META" "GOOGL"])
(def inverses ["TLT" "GLD" "VIXY" "SH" "UUP"])

(def crypto-bars
  (into {} (map (fn [a] [a (data/load-binance-json
                            (str "data/market/" a "USDT_1d.json"))]))
        cryptos))
(def stock-bars
  (into {} (map (fn [s] [s (data/load-alpaca-json
                            (str "data/market/stock_" s "_alpaca.json"))]))
        (concat stocks inverses)))

(def all-days
  "Every UTC day on which EVERY asset in every universe has a close."
  (let [sets (concat (map (comp set keys portfolio/day-close-map) (vals crypto-bars))
                     (map (comp set keys portfolio/day-close-map) (vals stock-bars)))]
    (vec (sort (reduce cset/intersection (first sets) (rest sets))))))

(defn aligned-mat
  "Close matrix: one row per asset, one column per common day."
  [assets]
  (mapv (fn [a]
          (let [m (portfolio/day-close-map (get (if (some #{a} cryptos) crypto-bars stock-bars) a))]
            (mapv #(get m %) all-days)))
        assets))

(def crypto-mat (aligned-mat cryptos))
(def stock-mat (aligned-mat stocks))
(def inv-mat (aligned-mat inverses))
(def nd (count all-days))
(println (str "\ndías comunes alineados entre los 3 universos: " nd
              "  (" (first all-days) " → " (peek all-days) ")"))

;; ---- stream 1: crypto long/short cross-sectional momentum ----------------
(def look 30)
(def rebal 7)
(def crypto-exp
  (let [exp (atom (vec (repeat (count cryptos) (vec (repeat nd 0.0)))))]
    (doseq [t (range look nd rebal)]
      (let [moms (map-indexed (fn [i row] (- (/ (nth row t) (nth row (- t look))) 1.0))
                              crypto-mat)
            ranked (sort-by second > (map-indexed vector moms))
            longs (take 3 (filter #(pos? (second %)) ranked))
            shorts (take 3 (filter #(neg? (second %)) ranked))
            weight (fn [i]
                     (let [row (nth crypto-mat i)
                           rr (map (fn [j] (- (/ (nth row j) (nth row (dec j))) 1.0))
                                   (range (inc (- t 30)) (inc t)))
                           m2 (/ (reduce + rr) (count rr))
                           rv (Math/sqrt (/ (reduce + (map #(let [d (- % m2)] (* d d)) rr))
                                            (max 1 (dec (count rr)))))]
                       (if (pos? rv) (min (/ tdv rv) 0.33) 0.0)))]
        (doseq [[sgn grp] [[1.0 longs] [-1.0 shorts]]
                [idx _m] grp
                :let [w (weight idx)]
                d (range t (min nd (+ t rebal)))]
          (swap! exp assoc-in [idx d] (double (* sgn w))))))
    @exp))

(def crypto-rets
  (apply mapv (fn [& rs] (reduce + rs))
         (map-indexed (fn [i row]
                        (metrics/net-returns
                         (:curve (backtest/run {:closes row
                                                :sigs (nth crypto-exp i)
                                                :cost-rate cost}))))
                      crypto-mat)))

;; ---- streams 2 & 3: SMA crossover on stocks / inverse ETFs ---------------
(defn sma-stream
  "Equal-weighted mean of the SMA-crossover net-return stream of a matrix
  of aligned close rows."
  [mat]
  (apply mapv (fn [& rs] (/ (reduce + rs) (double (count rs))))
         (map (fn [row]
                (let [sigs (signals/sma-crossover (assoc smacfg :closes row))]
                  (metrics/net-returns
                   (:curve (backtest/run {:closes row :sigs sigs :cost-rate stock-cost})))))
              mat)))

(def stock-rets (sma-stream stock-mat))
(def inv-rets (sma-stream inv-mat))

;; ---- combine: equal weight across the three streams ----------------------
;; Every return stream has (dec nd) entries: day i is the return FROM day i
;; TO day i+1 of the aligned calendar (backtest curves are levels; B&H
;; streams drop the index-0 dummy).
(def nr (dec nd))
(def combined
  (mapv (fn [i] (/ (+ (nth crypto-rets i) (nth stock-rets i) (nth inv-rets i)) 3.0))
        (range nr)))

;; ---- combined buy&hold on the SAME days ----------------------------------
(defn mean-rows [mat]
  (apply mapv (fn [& rs] (/ (reduce + rs) (double (count rs))))
         (map #(subvec (data/simple-returns %) 1) mat)))
(def crypto-bh (mean-rows crypto-mat))
(def stocks-bh (mean-rows stock-mat))
(def inv-bh (mean-rows inv-mat))
(def bh-portfolio
  (mapv (fn [i] (/ (+ (nth crypto-bh i) (nth stocks-bh i) (nth inv-bh i)) 3.0))
        (range nr)))

;; ---- panels ---------------------------------------------------------------
(defn show [name rets]
  (let [curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] rets)
        s (metrics/summarize-pro {:curve curve :final-equity (peek curve)
                                  :total-turnover 0.0 :profit-factor 0.0
                                  :hit-rate 0.0 :ppy 365})]
    (println (format "  %-24s Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | Calmar %.2f"
                     name (:sharpe s) (* 100 (:cagr s)) (* 100 (:max-drawdown s)) (:calmar s)))
    s))

(println "\n═══ cartera combinada profesional (días alineados por calendario) ═══")
(show "cripto long/short" crypto-rets)
(show "stocks SMA" stock-rets)
(show "inversos SMA" inv-rets)
(show "B&H combinado" bh-portfolio)
(def cs (show "COMBINADA" combined))
(println (format "\nOBJETIVO: Sharpe combinada %.2f — %s"
                 (:sharpe cs) (if (> (:sharpe cs) 1.0) "SUPERADO ✓" "en camino")))

;; ---------------------------------------------------------------------------
;; Walk-forward fold verification: the combined portfolio must beat the
;; combined B&H in EVERY fold (Sharpe AND maxDD). Both series live on the
;; exact same aligned days; each fold uses only its own window.
;; ---------------------------------------------------------------------------
(println "\n═══ verificación walk-forward: COMBINADA vs B&H combinado ═══")
(let [folds 9
      warm 40
      step (quot (- nd warm) folds)
      rows (for [f (range folds)
                 :let [t0 (+ warm (* f step))
                       t1 (min nd (+ t0 step))
                       mk (fn [rets]
                            (let [fr (subvec rets t0 t1)
                                  fc (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] fr)]
                              (metrics/summarize-pro
                               {:curve fc :final-equity (peek fc)
                                :total-turnover 0.0 :profit-factor 0.0
                                :hit-rate 0.0 :ppy 365})))
                       s-f (mk combined)
                       s-b (mk bh-portfolio)]]
             {:f (inc f)
              :ss (:sharpe s-f) :bs (:sharpe s-b)
              :sd (:max-drawdown s-f) :bd (:max-drawdown s-b)
              :win? (and (> (:sharpe s-f) (:sharpe s-b))
                         (< (:max-drawdown s-f) (:max-drawdown s-b)))})]
  (doseq [r rows]
    (println (format "  pliegue %d | Sharpe %+.2f vs %+.2f %s | maxDD %.1f%% vs %.1f%% %s → %s"
                     (:f r) (:ss r) (:bs r) (if (> (:ss r) (:bs r)) "✓" "✗")
                     (* 100 (:sd r)) (* 100 (:bd r)) (if (< (:sd r) (:bd r)) "✓" "✗")
                     (if (:win? r) "GANA" "pierde"))))
  (let [w (count (filter :win? rows))]
    (println (format "\n  RESULTADO: %d/%d pliegues GANAN a B&H (Sharpe+maxDD)" w (count rows)))
    (if (= w (count rows))
      (println "✓ GANA SIEMPRE (en la historia de datos disponible)")
      (println (str "✗ gana " w " de " (count rows))))))
