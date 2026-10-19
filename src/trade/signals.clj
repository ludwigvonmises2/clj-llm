(ns trade.signals
  "Strategy signals: pure functions from bar history to a desired exposure
  per bar. Exposure is decided at bar i using only bars 0..i; the
  backtester applies it to bar i+1's return."
  (:require [trade.data :as data]))

(set! *warn-on-reflection* true)

(defn sma-crossover
  "Trend-following exposure: long when the fast SMA is above the slow SMA,
  flat otherwise, scaled by a volatility target once enough history exists
  (position = target-daily-vol / realized-daily-vol, capped at max-lev).
  Returns a vector of desired exposures aligned with closes."
  [{:keys [closes fast slow target-vol vol-window max-lev ppy]}]
  (let [fast-sma (trade.data/sma closes fast)
        slow-sma (trade.data/sma closes slow)
        rets     (trade.data/simple-returns closes)
        tdv      (/ (double target-vol) (Math/sqrt (double (or ppy 365))))]
    (mapv (fn [i]
            (let [f (nth fast-sma i)
                  s (nth slow-sma i)]
              (if (or (nil? f) (nil? s))
                0.0
                (let [sign  (if (> f s) 1.0 0.0)
                      rv    (trade.data/realized-vol rets i (long vol-window))
                      scale (if (and rv (pos? rv))
                              (min (/ tdv rv) (double max-lev))
                              0.0)]
                  (* sign scale)))))
          (range (count closes)))))

(defn buy-and-hold
  "Benchmark exposure: always 1.0 (after bar 0)."
  [closes]
  (vec (concat [0.0] (repeat (dec (count closes)) 1.0))))

(declare sma-vec-fast)
(defn triple-trend
  "Per-asset best-practice exposure: long only when three scales agree
  (SMA fast>slow, SMA mid>anchor, price>anchor = absolute momentum),
  vol-targeted. Flat otherwise. Pure per-day function — the engine adds
  the execution lag."
  [{:keys [closes f1 s1 mid target-vol vol-window max-lev ppy]}]
  (let [fv  (sma-vec-fast closes f1)
        sv  (sma-vec-fast closes s1)
        mv  (sma-vec-fast closes mid)
        rets (data/simple-returns closes)
        tdv (/ (double target-vol) (Math/sqrt (double (or ppy 365))))]
    (mapv (fn [i]
            (let [f (nth fv i) s (nth sv i) m (nth mv i)
                  p (nth closes i)]
              (if (or (nil? f) (nil? s) (nil? m))
                0.0
                (let [rv (data/realized-vol rets i (long vol-window))
                      scale (if (and rv (> rv 1e-9)) (min (/ tdv rv) (double max-lev)) 0.0)]
                  (if (and (> f s) (> m s) (> p m)) scale 0.0)))))
          (range (count closes)))))
(defn sma-vec-fast
  "SMA with prefix sums (O(n)); nil until the first full window."
  [closes n]
  (let [prefix (reduce (fn [a c] (conj a (+ (peek a) c))) [0.0] closes)]
    (vec (map (fn [i] (when (>= i (dec n))
                        (/ (- (nth prefix (inc i)) (nth prefix (inc (- i n)))) (double n))))
              (range (count closes))))))

(defn mr-zscore
  "Intraday mean reversion with hysteresis (validated on the 1m crypto
  portfolio in dev/intraday.clj). z is the k-bar cumulative log return
  normalized by 1-bar realized vol times sqrt(k); while z < -threshold the
  state goes long until z crosses back through zero. `side` :long-dip
  (the winning portfolio configuration — dips mean-revert, pumps run,
  stay flat otherwise) or :both to short extreme pumps as well.
  Exposure vol-targeted; decided from past bars only."
  [{:keys [closes k w threshold target-vol max-lev ppy side] :or {side :long-dip}}]
  (let [n      (count closes)
        ;; prefix sums of LOG RETURNS: the difference k entries apart is
        ;; the k-bar cumulative log return log(P_i / P_{i-k})
        lrets  (into [0.0] (map (fn [i] (- (Math/log (nth closes i))
                                           (Math/log (nth closes (dec i)))))
                                (range 1 n)))
        prefix (reduce (fn [a x] (conj a (+ (peek a) x))) [0.0] lrets)
        rets   (data/simple-returns closes)
        tdv    (/ (double target-vol) (Math/sqrt (double ppy)))
        sk     (Math/sqrt (double k))]
    (loop [i 0, state 0.0, acc (transient [])]
      (if (= i n)
        (persistent! acc)
        (let [rv (data/realized-vol rets i (long w))
              z  (if (and (>= i k) rv (pos? rv))
                   (/ (- (nth prefix (inc i)) (nth prefix (inc (- i k))))
                      (* rv sk))
                   0.0)
              state (cond (= 0.0 state)
                          (cond (< z (- (double threshold))) 1.0
                                (and (= side :both) (> z (double threshold))) -1.0
                                :else 0.0)
                          (pos? state) (if (>= z 0.0) 0.0 1.0)
                          :else        (if (<= z 0.0) 0.0 -1.0))
              scale (if (and rv (pos? rv)) (min (/ tdv rv) (double max-lev)) 0.0)]
          (recur (inc i) state (conj! acc (* state scale))))))))
