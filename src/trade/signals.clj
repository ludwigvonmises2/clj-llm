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
  [{:keys [closes f1 s1 mid anchor target-vol vol-window max-lev ppy]}]
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
