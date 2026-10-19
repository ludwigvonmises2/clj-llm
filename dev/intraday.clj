;; Intraday mechanical trading: can the system WIN at intraday horizons
;; after real costs? Taker fee 5 bps per side (Binance USDT-M futures,
;; the natural venue for long/short) with a 10 bps sensitivity row.
;;
;; Part A validates on ~3 years of BTC 1h bars with walk-forward folds.
;; Part B measures the 1m universe (20 cryptos, minute-aligned) net of
;; costs, with rotation per mechanic.
;;
;; Mechanics (all long/short, decided from past bars only; the backtester
;; applies the position to the NEXT bar's return):
;;   trend-band : SMA fast/slow with a no-trade band (kills whipsaw)
;;   mr-zscore  : mean reversion beyond a high z threshold, hysteresis exit
;;   time-of-day: walk-forward hour-of-day seasonality score
;; Run: clojure -M dev/intraday.clj
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics]
         '[trade.portfolio :as portfolio]
         '[clojure.set :as cset])

(def taker-cost 0.0005)

;; ---- mechanics ------------------------------------------------------------
(defn trend-band
  "Long/short SMA trend exposure with a no-trade band: the position only
  updates when the vol-targeted target differs from the current position
  by more than `band` (kills whipsaw turnover at intraday frequencies)."
  [{:keys [closes fast slow target-vol vol-window max-lev band ppy]}]
  (let [fv   (data/sma closes fast)
        sv   (data/sma closes slow)
        rets (data/simple-returns closes)
        tdv  (/ (double target-vol) (Math/sqrt (double ppy)))
        n    (count closes)]
    (loop [i 0, prev 0.0, acc (transient [])]
      (if (= i n)
        (persistent! acc)
        (let [f (nth fv i), s (nth sv i)
              target (if (or (nil? f) (nil? s))
                       prev
                       (let [sgn   (if (> f s) 1.0 -1.0)
                             rv    (data/realized-vol rets i (long vol-window))
                             scale (if (and rv (pos? rv))
                                     (min (/ tdv rv) (double max-lev))
                                     0.0)]
                         (* sgn scale)))
              pos (if (> (Math/abs (- target prev)) (double band)) target prev)]
          (recur (inc i) pos (conj! acc pos)))))))

(defn time-of-day
  "Walk-forward hour-of-day seasonality on hourly bars: the exposure at
  bar i comes from the trailing mean/std of the same hour slot over the
  previous `history-days` days only; the current bar joins the history
  AFTER the decision."
  [closes history-days]
  (let [n    (count closes)
        rets (data/simple-returns closes)
        qs   (atom (vec (repeat 24 [])))
        out  (atom (vec (repeat n 0.0)))]
    (dotimes [i n]
      (let [h (mod i 24)
            q (nth @qs h)]
        (if (< (count q) history-days)
          (do (swap! qs assoc h (conj q (nth rets i)))
              (swap! out assoc i 0.0))
          (let [m (/ (reduce + q) (double (count q)))
                v (Math/sqrt (/ (reduce + (map #(* % %) q))
                                (double (dec (count q)))))
                z (if (pos? v) (/ m v) 0.0)]
            (swap! out assoc i (max -1.0 (min 1.0 z)))
            (swap! qs assoc h (take-last history-days (conj q (nth rets i))))))))
    @out))

;; ---- helpers ---------------------------------------------------------------
(defn run-metrics
  "Summarized backtest of one exposure stream; prints a panel line."
  [closes sigs ppy cost name]
  (let [bt (backtest/run {:closes closes :sigs sigs :cost-rate cost})
        s  (metrics/summarize-pro (assoc bt :ppy ppy))]
    (println (format "  %-16s Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%% | rot %.0fx/yr"
                     name (:sharpe s) (* 100 (:cagr s))
                     (* 100 (:max-drawdown s)) (:turnover-yr s)))
    s))

;; ==== PART A: BTC 1h, ~3 years ==============================================
(println "\n═══ A. BTC 1h — validación con historia (taker 5 bps) ═══")
(def btc-1h (data/closes (data/load-binance-json "data/market/BTCUSDT_1h.json")))
(def ppy-1h 8760)
(println (str "barras 1h: " (count btc-1h)))
(def bh-1h (run-metrics btc-1h (vec (concat [0.0] (repeat (dec (count btc-1h)) 1.0)))
                        ppy-1h 0.0 "B&H"))
(def tb-1h (run-metrics btc-1h
                        (trend-band {:closes btc-1h :fast 60 :slow 240
                                     :target-vol 0.30 :vol-window 336
                                     :max-lev 2.0 :band 0.50 :ppy ppy-1h})
                        ppy-1h taker-cost "trend-band"))
(def mr-1h (run-metrics btc-1h
                        (signals/mr-zscore {:closes btc-1h :k 24 :w 336 :threshold 3.0
                                    :target-vol 0.30 :max-lev 2.0 :ppy ppy-1h})
                        ppy-1h taker-cost "mr-zscore"))
(def td-1h (run-metrics btc-1h (time-of-day btc-1h 60)
                        ppy-1h taker-cost "time-of-day"))

(println "\n── folds walk-forward 1h (criterio doble vs B&H) ──")
(println "  trend-band:")
(portfolio/fold-verify btc-1h
             (fn [c] (trend-band {:closes c :fast 60 :slow 240
                                  :target-vol 0.30 :vol-window 336
                                  :max-lev 2.0 :band 0.50 :ppy ppy-1h}))
             ppy-1h taker-cost 8 500 true)
(println "  mr-zscore:")
(portfolio/fold-verify btc-1h
             (fn [c] (signals/mr-zscore {:closes c :k 24 :w 336 :threshold 3.0
                                 :target-vol 0.30 :max-lev 2.0 :ppy ppy-1h}))
             ppy-1h taker-cost 8 500 true)

;; ==== PART B: 1m universe, minute-aligned ===================================
(println "\n═══ B. universo 1m alineado por minuto (taker 5 bps) ═══")
(def uni (vec (sort (keep #(second (re-find #"(.+?)USDT_1m\.json" (.getName %)))
                          (.listFiles (java.io.File. "data/market"))))))
(def bars-1m
  (into {} (map (fn [a] [a (data/load-binance-json
                            (str "data/market/" a "USDT_1m.json"))]))
        uni))
(def common-ts
  (let [sets (map #(set (map :ts (get bars-1m %))) uni)]
    (vec (sort (reduce cset/intersection (first sets) (rest sets))))))
(def mat-1m
  (mapv (fn [a]
          (let [m (into {} (map (fn [b] [(:ts b) (:c b)])) (get bars-1m a))]
            (mapv #(get m %) common-ts)))
        uni))
(def nm (count mat-1m))
(def nmin (count common-ts))
(def ppy-1m 525600)
(println (str nm " activos × " nmin " minutos comunes"))
(defn port-stream
  "Equal-weight portfolio net-return stream of one mechanic over the 1m
  matrix (mean of per-asset net returns)."
  [sigfn cost]
  (apply mapv (fn [& rs] (/ (reduce + rs) (double nm)))
         (map (fn [row]
                (metrics/net-returns
                 (:curve (backtest/run {:closes row :sigs (sigfn row)
                                        :cost-rate cost}))))
              mat-1m)))
(defn show-port [name rets]
  (let [curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] rets)
        s (metrics/summarize-pro {:curve curve :final-equity (peek curve)
                                  :total-turnover 0.0 :profit-factor 0.0
                                  :hit-rate 0.0 :ppy ppy-1m})]
    (println (format "  %-22s Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%%"
                     name (:sharpe s) (* 100 (:cagr s)) (* 100 (:max-drawdown s))))
    s))
(def bh-1m
  (apply mapv (fn [& rs] (/ (reduce + rs) (double nm)))
         (map #(subvec (data/simple-returns %) 1) mat-1m)))
(show-port "B&H universo" bh-1m)
(def tb-1m (show-port "trend-band"
                      (port-stream (fn [c] (trend-band {:closes c :fast 120 :slow 480
                                                        :target-vol 0.30 :vol-window 360
                                                        :max-lev 2.0 :band 0.50
                                                        :ppy ppy-1m}))
                                   taker-cost)))
(def mr-1m (show-port "mr-zscore"
                      (port-stream (fn [c] (signals/mr-zscore {:closes c :k 60 :w 360
                                                       :threshold 3.0 :target-vol 0.30
                                                       :max-lev 2.0 :ppy ppy-1m}))
                                   taker-cost)))
(println "\n  sensibilidad de costes (maker 2 bps / spot base 10 bps):")
(defn show-port2 [name rets]
  (let [curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] rets)
        s (metrics/summarize-pro {:curve curve :final-equity (peek curve)
                                  :total-turnover 0.0 :profit-factor 0.0
                                  :hit-rate 0.0 :ppy ppy-1m})]
    (println (format "  %-22s Sharpe %+.2f | CAGR %+.1f%% | maxDD %.1f%%"
                     name (:sharpe s) (* 100 (:cagr s)) (* 100 (:max-drawdown s))))
    s))
(show-port2 "mr-zscore 2bps"
            (port-stream (fn [c] (signals/mr-zscore {:closes c :k 60 :w 360
                                                     :threshold 3.0 :side :both
                                                     :target-vol 0.30
                                                     :max-lev 2.0 :ppy ppy-1m}))
                         0.0002))
(def tb-1m-10 (show-port "trend-band 10bps"
                         (port-stream (fn [c] (trend-band {:closes c :fast 120 :slow 480
                                                           :target-vol 0.30 :vol-window 360
                                                           :max-lev 2.0 :band 0.50
                                                           :ppy ppy-1m}))
                                      0.001)))
(def mr-1m-10 (show-port "mr-zscore 10bps"
                         (port-stream (fn [c] (signals/mr-zscore {:closes c :k 60 :w 360
                                                                  :threshold 3.0 :side :both
                                                                  :target-vol 0.30
                                                                  :max-lev 2.0 :ppy ppy-1m}))
                                      0.001)))
;; ==== PART C: mr-zscore 1m — walk-forward folds on 30 days =================
(println "\n═══ C. mr-zscore 1m — folds sobre 30 días de 1m ═══")
(def mr-1m-sig (fn [c] (signals/mr-zscore {:closes c :k 60 :w 360 :threshold 3.0
                                           :side :both
                                           :target-vol 0.30 :max-lev 2.0 :ppy ppy-1m})))
(doseq [sym ["BTCUSDT" "ETHUSDT"]]
  (let [c (data/closes (data/load-binance-json (str "data/market/" sym "_1m.json")))]
    (println (str "  " sym " (" (count c) " barras 1m):"))
    (portfolio/fold-verify c mr-1m-sig ppy-1m taker-cost 8 720 true)))

(println "\n  sensibilidad del umbral — BTC 1m, folds (¿es robusto o afinado?):")
(def btc-1m-c (data/closes (data/load-binance-json "data/market/BTCUSDT_1m.json")))
(doseq [thr [2.0 2.5 3.0 3.5]]
  (print (str "    umbral " thr ": "))
  (let [[w n] (portfolio/fold-verify btc-1m-c
                           (fn [c] (signals/mr-zscore {:closes c :k 60 :w 360 :threshold thr
                                                       :side :both
                                                       :target-vol 0.30 :max-lev 2.0 :ppy ppy-1m}))
                           ppy-1m taker-cost 8 720 false)]
    (println (str w "/" n " ganan"))))

;; ==== PART D: crypto asymmetry — long-dip-only MR ===========================
(println "\n═══ D. mr long-dip-only (compra caídas, nunca corta) ═══")
(defn mr-dip-sig [c]
  (signals/mr-zscore {:closes c :k 60 :w 360 :threshold 3.0 :side :long-dip
                      :target-vol 0.30 :max-lev 2.0 :ppy ppy-1m}))
(doseq [sym ["BTCUSDT" "ETHUSDT"]]
  (let [c (data/closes (data/load-binance-json (str "data/market/" sym "_1m.json")))]
    (print (str "  " sym " taker 5bps: "))
    (let [[w n] (portfolio/fold-verify c mr-dip-sig ppy-1m taker-cost 8 720 false)]
      (println (str w "/" n " ganan")))
    (print (str "  " sym " maker 2bps: "))
    (let [[w n] (portfolio/fold-verify c mr-dip-sig ppy-1m 0.0002 8 720 false)]
      (println (str w "/" n " ganan")))
    (println (str "  detalle " sym " (maker 2bps):"))
    (portfolio/fold-verify c mr-dip-sig ppy-1m 0.0002 8 720 true)))

(println "\n  panel universo 1m (20 activos, long-dip):")
(show-port2 "mr-dip 2bps"
            (port-stream mr-dip-sig 0.0002))
(show-port2 "mr-dip 5bps"
            (port-stream mr-dip-sig taker-cost))

(println "\nfin intradía")
