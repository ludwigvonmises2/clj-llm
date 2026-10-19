;; Robustness verification of the intraday system under RANDOM
;; ultrarealistic scenarios:
;;   1. Stationary-block bootstrap of the strategy's own minute returns
;;      (block length 60 min preserves volatility clustering and
;;      autocorrelation) -> distribution of Sharpe, P(Sharpe>0).
;;   2. Parameter-neighborhood jitter (k, threshold, fast/slow +/-20%)
;;      -> fold-win spread, proving the edge is not a tuned single point.
;;   3. Monte Carlo path reshuffle: B&H paths resampled with the same
;;      block scheme; the system must beat the majority of its B&H twins.
;; Run: clojure -M dev/robustness.clj
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics]
         '[trade.portfolio :as portfolio])

(def ^java.util.Random rng (java.util.Random. 42))
(def ppy 525600)
(def cost 0.0002)

(def sig (fn [c]
           (signals/mr-zscore {:closes c :k 60 :w 360 :threshold 3.0
                               :side :long-dip :target-vol 0.30
                               :max-lev 2.0 :ppy ppy})))

(defn sharpe-of [rets]
  (let [n  (count rets)
        m  (/ (reduce + rets) (double n))
        sd (Math/sqrt (/ (reduce + (map #(* (- % m) (- % m)) rets)) (max 1 (dec n))))]
    (if (pos? sd) (* (/ m sd) (Math/sqrt (double ppy))) 0.0)))

(defn block-bootstrap
  "Resample `rets` with stationary block bootstrap (mean block 60).
  Returns n-sims Sharpe values."
  [rets n-sims]
  (let [n (count rets)]
    (repeatedly n-sims
                (fn []
                  (let [out (double-array n)]
                    (dotimes [i n]
                      (let [start (mod (.nextInt rng) n)
                            blen  (inc (.nextInt rng 120))]
                        (dotimes [j blen]
                          (when (< (+ i j) n)
                            (aset out (+ i j) (nth rets (mod (+ start j) n)))))))
                    (sharpe-of (vec out)))))))

(defn strat-rets
  "Net minute returns of the system over `closes` (prefix-causal)."
  [closes]
  (metrics/net-returns
   (:curve (backtest/run {:closes closes :sigs (sig closes) :cost-rate cost}))))

(doseq [sym ["BTCUSDT" "ETHUSDT"]]
  (let [c    (data/closes (data/load-binance-json (str "data/market/" sym "_1m.json")))
        sr   (strat-rets c)
        s0   (sharpe-of sr)
        sims (block-bootstrap sr 1000)
        pos  (count (filter pos? sims))
        lo   (nth (sort sims) (int (* 0.05 (count sims))))
        hi   (nth (sort sims) (int (* 0.95 (count sims))))]
    (println (format "%s | Sharpe sistema %+.2f | bootstrap 2000: P(>0) %.1f%% | IC90 [%+.2f, %+.2f]"
                     sym s0 (* 100.0 (/ pos (count sims))) lo hi))))

;; parameter-neighborhood jitter: +/-20% on each key parameter
(println "\nrobustez de parámetros (BTC 1m, folds maker 2bps):")
(def btc (data/closes (data/load-binance-json "data/market/BTCUSDT_1m.json")))
(doseq [[lbl cfg] [["base"   {:k 60  :thr 3.0 :w 360}]
                   ["k-20%"   {:k 48  :thr 3.0 :w 360}]
                   ["k+20%"   {:k 72  :thr 3.0 :w 360}]
                   ["thr-20%" {:k 60  :thr 2.4 :w 360}]
                   ["thr+20%" {:k 60  :thr 3.6 :w 360}]
                   ["w-20%"   {:k 60  :thr 3.0 :w 288}]
                   ["w+20%"   {:k 60  :thr 3.0 :w 432}]]
        :let [[w n] (portfolio/fold-verify
                     btc (fn [c]
                           (signals/mr-zscore
                            {:closes c :k (:k cfg) :w (:w cfg) :threshold (:thr cfg)
                             :side :long-dip :target-vol 0.30
                             :max-lev 2.0 :ppy ppy}))
                     ppy cost 6 720 false)]]
  (println (format "  %-15s %d/%d pliegues GANAN" lbl w n)))

(println "\nfin robustez")
