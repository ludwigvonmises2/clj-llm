(ns trade.live
  "Realtime engine: ticks in, same pure signals out.

  Three layers, each replaceable, none duplicated:

    bar-builder : folds raw trades {:p price :t epoch-ms :q qty} into
                  TIME-window bars (:ts :o :h :l :c :v) — the same shape
                  trade.data produces from historical klines.
    engine      : a bounded rolling window of closes; on every COMPLETED
                  bar it recomputes the SAME pure signal functions the
                  backtest uses (signals/*) over that window. A decision
                  at close i is applied to bar i+1 — identical to the
                  backtester's lag, so there is no live/backtest drift.
    executor    : converts a target exposure into orders through a
                  no-trade band (|target - position| <= band holds),
                  killing sub-cost churn from tick noise.

  HONESTY CONTRACT, enforced by tests: given the same bars, the engine's
  decided exposures equal the causal signal computed on the growing
  prefix once the window covers the signal's warmup. If a future change
  breaks that equality, live trading would NOT be what the backtest
  validated — the parity test fails loudly before any money moves."
  (:require [trade.data :as data]
            [trade.signals :as signals]))

(set! *warn-on-reflection* true)

;; ---- layer 1: bar builder ---------------------------------------------------
(defn ticks->bars
  "Fold a trade stream (seq of {:p :t :q}, any order in the same window
  is resolved by sorting on :t) into time-window bars. Bars carry the
  window's START epoch-ms as :ts. Partial trailing windows are dropped
  (only completed bars drive decisions)."
  [trades window-ms]
  (let [sorted (sort-by :t trades)
        groups (partition-by (fn [{:keys [t]}] (quot (long t) window-ms)) sorted)]
    (into []
          (comp (remove nil?)
                (map (fn [gs]
                       (let [ps (mapv :p gs)
                             vs (mapv #(double (:q %)) gs)]
                         {:ts (* (quot (long (:t (first gs))) window-ms) window-ms)
                          :o (first ps) :h (reduce max ps)
                          :l (reduce min ps) :c (peek ps)
                          :v (reduce + vs)}))))
          groups)))

;; ---- layer 2: rolling engine -------------------------------------------------
(defn live-sigs
  "What the engine would decide, bar by bar, using only a rolling window
  of the last `window` closes. Returns the full exposure vector (the
  first bars, before the window is full, are 0.0). This is the replay
  oracle: with window >= signal warmup it must equal the pure signal —
  the parity test in trade.core-test enforces it."
  [closes window sigfn]
  (let [n (count closes)
        w (long window)]
    (mapv (fn [i]
            (if (< i (dec w))
              0.0
              (let [a  (max 0 (- i (dec w)))
                    win (subvec closes a (inc i))]
                (last (sigfn win)))))
          (range n))))

;; ---- layer 3: executor --------------------------------------------------------
(defn band-executor
  "Turn target exposures into executed positions through a no-trade
  band: hold the previous position unless the target differs by more
  than `band`. Returns the executed position stream (what would hit the
  exchange, and what the cost model should be charged on)."
  [targets band]
  (loop [i 0, pos 0.0, acc (transient [])]
    (if (= i (count targets))
      (persistent! acc)
      (let [t   (double (nth targets i))
            pos (if (> (Math/abs (- t pos)) (double band)) t pos)]
        (recur (inc i) pos (conj! acc pos))))))

(defn run-loop
  "Realtime loop skeleton. `source` is a zero-arg fn returning the next
  trade or nil (exhausted / poll timeout); `on-position` receives the
  executed position after each COMPLETED bar. Everything else — bar
  building, signal recomputation, banding — reuses the layers above so
  live decisions are exactly the parity-tested ones."
  [{:keys [window-ms window sigfn band source on-position]}]
  (loop [trades-acc []
         history     []]
    (if-some [t (source)]
      (let [acc (conj trades-acc t)
            bars (ticks->bars acc window-ms)]
        (if (seq bars)
          (let [history (into history (map :c) bars)]
            (when on-position
              (on-position (band-executor (live-sigs history window sigfn) band)))
            (recur [] history))
          (recur acc history)))
      history)))
