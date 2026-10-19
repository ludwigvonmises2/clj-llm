(ns trade.model
  "Market learning: turn daily returns into a discrete 'language' of bins and
  let our verified GPT learn to predict the next bin from the previous ctx
  days. Improvement is incremental: each walk-forward window warm-starts
  from the previous window's weights (llm.model/continue-params or simply
  reusing the params map — llm.train/train! mutates in place).

  No look-ahead: bin thresholds come only from the training window, and the
  position for day d is decided by a prediction made at close of day d-1."
  (:require [llm.nn]
            [llm.model]
            [llm.train]
            [trade.data]))

(set! *warn-on-reflection* true)

(def model-cfg
  "Tiny on purpose: ~18k params, milliseconds per training step. Tiny models
  overfit less — and the point is the mechanism, not the edge."
  {:ctx 32 :d 32 :layers 2 :heads 2 :dff 64})

(defn bin-thresholds
  "(k-1) quantile cut points over the TRAINING window's returns only."
  [train-rets k]
  (let [xs (vec (sort (drop 1 train-rets)))     ; drop the index-0 placeholder
        n  (count xs)]
    (vec (for [i (range 1 k)]
           (nth xs (long (* n (/ i k))) (peek xs))))))

(defn bin-of
  "Which of k bins does return r fall into, given the sorted cut points."
  [thresholds r]
  (loop [i 0]
    (if (or (>= i (count thresholds)) (< r (nth thresholds i)))
      i
      (recur (inc i)))))

(defn bin-seq
  "Whole return series binned: index 0 -> 0, then each return."
  [rets thresholds]
  (into [0] (map #(bin-of thresholds %) (rest rets))))

(defn predict-bin
  "Greedy next-bin prediction from the last ctx binned days."
  [cfg params bins ^long day]
  (let [ctx (:ctx cfg)
        lo  (max 0 (- day ctx))
        w   (int-array (subvec bins lo day))
        {:keys [logits]} (llm.nn/forward-1 cfg params w)
        V   (:vocab cfg)
        off (* (dec (count w)) V)]
    (loop [j 1 best 0]
      (if (>= j V)
        best
        (recur (inc j)
               (if (> (aget ^floats logits (+ off j)) (aget ^floats logits (+ off best)))
                 j
                 best))))))

(defn walk-forward
  "Expanding-window walk-forward learning.

  For t in [t0, n) in steps of `step`: train the model on bins[0..t) and
  emit the predicted position for each day of [t, t+step). The first window
  trains from scratch; later windows keep training the same weights —
  incremental improvement. Returns the full exposure vector (0 before t0)."
  [{:keys [rets k t0 step steps lr]}]
  (let [n          (count rets)
        thresholds (bin-thresholds (subvec rets 0 t0) k)
        bins       (bin-seq rets thresholds)
        cfg        (assoc model-cfg :vocab k)
        params     (llm.model/init cfg 42)
        tdv        (/ 0.20 (Math/sqrt 365.0))
        exposures  (volatile! (vec (repeat n 0.0)))
        stats      (volatile! [])]
    (loop [t t0
           params params
           round 0]
      (if (>= t n)
        {:exposures @exposures :stats @stats}
        (let [_ (llm.train/train! {:cfg cfg :params params :data (int-array bins)
                                   :steps steps :batch 32 :peak-lr lr
                                   :warmup-frac 0.1 :wd 0.0 :clip 1.0
                                   :print-every (max 1 (long (/ steps 2)))
                                   :seed (+ 42 round)})
              t2 (min n (+ t step))
              ;; predict every day of [t, t2) from the bins seen up to that day
              preds (for [d (range t t2)]
                      (let [b (predict-bin cfg params bins d)
                            scale (let [rv (trade.data/realized-vol rets (dec d) 30)]
                                    (if (and rv (pos? rv)) (min (/ tdv rv) 2.0) 0.0))]
                        (case b
                          0 (- scale)          ; predicted down bin -> short
                          (dec k) scale        ; predicted up bin -> long
                          0.0)))]              ; middle bin -> flat
          (vswap! stats conj {:round round :trained-to t :pred-to t2})
          (vswap! exposures (fn [e] (apply assoc e (interleave (range t t2) preds))))
          (recur t2 params (inc round)))))))
