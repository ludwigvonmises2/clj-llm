(ns trade.families
  "Strategy families beyond mean reversion, all on the same contract as
  signals/mr-zscore: a vector of target exposures aligned with closes,
  decided from bars <= i only (the backtester applies bar i to i+1),
  vol-targeted and capped at max-lev. Primitive O(n) kernels."
  (:require [trade.data :as data]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)

(defn- darr ^doubles [closes] (double-array (map double closes)))

(defn vol-scale
  "Per-bar exposure scale target-vol/realized-vol over the last w simple
  returns (rolling sums: O(n)); 0 until w returns exist."
  ^doubles [^doubles c w target-vol ppy max-lev]
  (let [w (long w) max-lev (double max-lev)
        n (alength c)
        out (double-array n)
        tdv (/ (double target-vol) (Math/sqrt (double ppy)))
        r (double-array n)]
    (dotimes [i n]
      (when (pos? i) (aset r i (- (/ (aget c i) (aget c (dec i))) 1.0))))
    (loop [i 1 s 0.0 s2 0.0]
      (when (< i n)
        (let [x (aget r i)
              s (+ s x) s2 (+ s2 (* x x))
              [s s2] (if (> i w)
                       (let [y (aget r (- i w))] [(- s y) (- s2 (* y y))])
                       [s s2])
              s (double s) s2 (double s2)]
          (when (>= i w)
            (let [m (/ s w)
                  var (max 0.0 (- (/ s2 w) (* m m)))
                  sd (Math/sqrt var)]
              (aset out i (if (pos? sd) (min (/ tdv sd) max-lev) 0.0))))
          (recur (inc i) s s2))))
    out))

(defn- finish [^doubles state ^doubles scale]
  (let [n (alength state)]
    (loop [i 0 acc (transient [])]
      (if (= i n) (persistent! acc)
          (recur (inc i) (conj! acc (* (aget state i) (aget scale i))))))))

(defn tsmom
  "Time-series momentum: sign of the L-bar return (long-only clamps < 0)."
  [{:keys [closes lookback w target-vol max-lev ppy side]}]
  (let [c (darr closes) n (alength c) L (long lookback)
        st (double-array n)]
    (dotimes [i n]
      (when (>= i L)
        (let [ret (- (aget c i) (aget c (- i L)))]
          (aset st i (double (cond (pos? ret) 1.0
                                   (and (neg? ret) (= side :both)) -1.0
                                   :else 0.0))))))
    (finish st (vol-scale c w target-vol ppy max-lev))))

(defn- hi ^double [^doubles c ^long i ^long k]
  (loop [j (- i k) m Double/NEGATIVE_INFINITY]
    (if (< j i) (recur (inc j) (max m (aget c j))) m)))

(defn- lo ^double [^doubles c ^long i ^long k]
  (loop [j (- i k) m Double/POSITIVE_INFINITY]
    (if (< j i) (recur (inc j) (min m (aget c j))) m)))

(defn donchian
  "Breakout: long when close > max of the prior `entry` closes, exit when
  close < min of the prior `exit` closes (short mirror when :both)."
  [{:keys [closes entry exit w target-vol max-lev ppy side]}]
  (let [c (darr closes) n (alength c) en (long entry) ex (long exit)
        st (double-array n)
]
    (loop [i (max en ex) s 0.0]
      (when (< i n)
        (let [px (aget c i)
              s (double (cond
                  (== s 0.0) (cond (> px (hi c i en)) 1.0
                                   (and (= side :both) (< px (lo c i en))) -1.0
                                   :else 0.0)
                  (pos? s) (if (< px (lo c i ex)) 0.0 1.0)
                  :else (if (> px (hi c i ex)) 0.0 -1.0)))]
          (aset st i s)
          (recur (inc i) s))))
    (finish st (vol-scale c w target-vol ppy max-lev))))

(defn- zscores
  "z[i] = k-bar log return / (1-bar vol over w × sqrt k), as mr-zscore."
  ^doubles [^doubles c ^long k ^long w]
  (let [n (alength c)
        out (double-array n)
        lr (double-array n)]
    (dotimes [i n] (when (pos? i) (aset lr i (Math/log (/ (aget c i) (aget c (dec i)))))))
    (loop [i 1 s 0.0 s2 0.0 sk 0.0]
      (when (< i n)
        (let [x (aget lr i)
              s (+ s x) s2 (+ s2 (* x x)) sk (+ sk x)
              s (if (> i w) (- s (aget lr (- i w))) s)
              s2 (if (> i w) (let [y (aget lr (- i w))] (- s2 (* y y))) s2)
              sk (if (> i k) (- sk (aget lr (- i k))) sk)]
          (when (and (>= i w) (>= i k))
            (let [m (/ s w) sd (Math/sqrt (max 0.0 (- (/ s2 w) (* m m))))]
              (when (pos? sd) (aset out i (/ sk (* sd (Math/sqrt k)))))))
          (recur (inc i) s s2 sk))))
    out))

(defn zmom
  "Momentum on z: enter WITH a strong k-bar move (z > thr long, z < -thr
  short when :both) and hold until z crosses back through zero."
  [{:keys [closes k w threshold target-vol max-lev ppy side]}]
  (let [c (darr closes) n (alength c)
        ^doubles z (zscores c (long k) (long w)) thr (double threshold)
        st (double-array n)]
    (loop [i 0 s 0.0]
      (when (< i n)
        (let [zi (aget z i)
              s (double (cond (== s 0.0) (cond (> zi thr) 1.0
                                       (and (= side :both) (< zi (- thr))) -1.0
                                       :else 0.0)
                      (pos? s) (if (<= zi 0.0) 0.0 1.0)
                      :else (if (>= zi 0.0) 0.0 -1.0)))]
          (aset st i s)
          (recur (inc i) s))))
    (finish st (vol-scale c w target-vol ppy max-lev))))

(defn mr-trend
  "Mean reversion only inside an uptrend: buy dips (z < -thr) while the
  close is above its `trend`-bar SMA; exit at z >= 0 or trend loss."
  [{:keys [closes k w threshold trend target-vol max-lev ppy]}]
  (let [c (darr closes) n (alength c)
        ^doubles z (zscores c (long k) (long w)) thr (double threshold) T (long trend)
        st (double-array n)]
    (loop [i 0 s 0.0 sum 0.0]
      (when (< i n)
        (let [sum (+ sum (aget c i))
              sum (if (>= i T) (- sum (aget c (- i T))) sum)
              up? (and (>= i T) (> (aget c i) (/ sum T)))
              zi (aget z i)
              s (double (if (== s 0.0)
                  (if (and up? (< zi (- thr))) 1.0 0.0)
                  (if (or (>= zi 0.0) (not up?)) 0.0 1.0)))]
          (aset st i s)
          (recur (inc i) s sum))))
    (finish st (vol-scale c w target-vol ppy max-lev))))

(defn pair-ratio
  "Synthetic close of the ETH/BTC log-ratio spread (same venue, rule 4):
  trading it = long A / short B. Feed it to any family (mean reversion
  on a cointegrated pair is the classic stat-arb)."
  [a b]
  (mapv (fn [x y] (/ (double x) (double y))) a b))

(def families
  {:tsmom tsmom :donchian donchian :zmom zmom :mr-trend mr-trend})
