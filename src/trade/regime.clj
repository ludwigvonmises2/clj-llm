(ns trade.regime
  "Regime-switching mechanical trading.

  A small GPT (trade.model config) learns to classify the market regime
  from the last 32 binned returns, using ONLY synthetic scenarios whose
  labels are known by construction. Each regime maps to one mechanical
  expert; the composed policy switches mechanically:

    :bull  -> trend-following long  (hysteresis exit band, vol-targeted)
    :bear  -> trend-following short (same state machine, mirrored)
    :chop  -> ladder mean-reversion (±2% rungs off the anchor)
    :crash -> cash

  Experts are stateful (turnover-controlled): vol scaling frozen in 5-bar
  blocks, reapplied only on >15% drift; trend exits need a 2% band. No
  look-ahead anywhere: bins and classifications at day d use data ≤ d-1.")

(set! *warn-on-reflection* true)

(require '[llm.model]
         '[llm.train :as llm-train]
         '[llm.nn :as llm-nn]
         '[trade.model]
         '[trade.data :as data]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(def ctx 32)
(def k 3)
(def vocab 7)                                     ; ids 0-2 bins, 3-6 regime tokens
(def regimes [:bull :bear :chop :crash])
(def tdv (/ 0.20 (Math/sqrt 365.0)))

;; ---------------------------------------------------------------------------
;; Synthetic scenario generators (every scenario the system must survive)
;; ---------------------------------------------------------------------------

(defn gen-scenario [kind ^long seed]
  (let [rng    (java.util.Random. seed)
        g      #(.nextGaussian rng)
        prices (vec (case kind
                      :bull  (reductions (fn [p _] (* p (Math/exp (+ 0.0015 (* 0.012 (g)))))) 100.0 (range 400))
                      :bear  (reductions (fn [p _] (* p (Math/exp (+ -0.0015 (* 0.016 (g)))))) 100.0 (range 400))
                      :crash (let [rs (concat (repeat 120 0.0002) (repeat 40 -0.03) (repeat 240 0.0003))]
                               (reductions (fn [p r] (* p (Math/exp (+ r (* 0.010 (g)))))) 100.0 rs))
                      :chop  (let [anchor (volatile! 100.0)]
                               (reductions (fn [p _]
                                             (let [p2 (* p (Math/exp (* 0.008 (g))))]
                                               (+ p2 (* 0.05 (- @anchor p2)))))
                                           100.0 (range 400)))))]
    (map (fn [i] (- (/ (nth prices i) (nth prices (dec i))) 1.0))
         (range 1 400))))

(defn bin-seq-of [rets]
  (into [1] (map (fn [r] (cond (< r -0.004) 0 (> r 0.004) 2 :else 1)) (rest rets))))

;; ---------------------------------------------------------------------------
;; Mechanical experts (stateful, turnover-controlled)
;; ---------------------------------------------------------------------------

(defn sma-vec [closes n]
  (let [prefix (reduce (fn [a c] (conj a (+ (peek a) c))) [0.0] closes)]
    (vec (map (fn [i] (when (>= i (dec n))
                        (/ (- (nth prefix (inc i)) (nth prefix (inc (- i n)))) (double n))))
              (range (count closes))))))

(defn trend-exposure
  "Hysteresis trend exposure for :long or :short. Enter on SMA cross, exit
  with a 2% band; the vol scale is frozen in 5-bar blocks and reapplied
  only on >15% drift."
  [closes mode fast slow]
  (let [fv (sma-vec closes fast)
        sv (sma-vec closes slow)
        rets (data/simple-returns closes)
        n (count closes)]
    (loop [i 0, pos 0.0, state :out, frozen 1.0, acc (transient [])]
      (if (>= i n)
        (persistent! acc)
        (let [f (nth fv i) s (nth sv i)
              scale (let [rv (data/realized-vol rets i 20)]
                      (if (and rv (> rv 1e-9)) (min (/ tdv rv) 2.0) frozen))
              frozen2 (if (zero? (mod i 5)) scale frozen)
              [state2 pos2]
              (cond
                (or (nil? f) (nil? s)) [state pos]
                (= state :out)
                (cond
                  (> f s) (if (= mode :long) [:in frozen2] [:in (- frozen2)])
                  (< f s) (if (= mode :short) [:in (- frozen2)] [:in frozen2])
                  :else [state pos])
                (and (= state :in) (pos? pos) (< f (* 0.98 s))) [:out 0.0]
                (and (= state :in) (neg? pos) (> f (* 1.02 s))) [:out 0.0]
                (and (pos? pos) (> frozen2 (* 1.15 pos))) [state frozen2]
                (and (pos? pos) (< frozen2 (* 0.85 pos))) [state frozen2]
                (and (neg? pos) (> (- frozen2) (* 1.15 (- pos)))) [state (- frozen2)]
                (and (neg? pos) (< (- frozen2) (* 0.85 (- pos)))) [state (- frozen2)]
                :else [state pos])]
          (recur (inc i) (double pos2) state2 frozen2 (conj! acc (double pos2))))))))

(defn ladder-exposure
  "Ladder mean-reversion: buy a vol-scaled rung per -2% step below the
  anchor, flatten on a +2% bounce."
  [closes]
  (loop [i 0, pos 0.0, anchor (double (first closes)), acc (transient [])]
    (if (>= i (count closes))
      (persistent! acc)
      (let [c (double (nth closes i))
            rv (data/realized-vol (data/simple-returns closes) i 20)
            scale (if (and rv (> rv 1e-9)) (min (/ tdv rv) 2.0) 0.0)
            [pos2 anchor2]
            (cond
              ;; disaster stop: a ladder must never ride a trend to zero
              (<= c (* anchor 0.80)) [0.0 c]
              (<= c (* anchor 0.98)) [(min (+ pos scale) 1.0) c]
              (>= c (* anchor 1.02)) [0.0 c]
              :else [pos anchor])]
        (recur (inc i) (double pos2) (double anchor2) (conj! acc (double pos2)))))))

(defn cash-exposure [closes] (vec (repeat (count closes) 0.0)))

(defn expert-vec [kind closes]
  (case kind
    :bull  (trend-exposure closes :long 10 30)
    :bear  (trend-exposure closes :short 10 30)
    :chop  (ladder-exposure closes)
    :crash (cash-exposure closes)))

;; ---------------------------------------------------------------------------
;; Regime classifier: supervised on synthetic windows -> regime token
;; ---------------------------------------------------------------------------

(defn training-data
  "n-per examples per regime: windows of binned returns paired with the
  scenario's regime token. Train seeds are disjoint from eval seeds."
  [n-per]
  (let [rng (java.util.Random. 9)]
    (vec (for [kind regimes
               i (range n-per)
               :let [rets (vec (gen-scenario kind (+ 1000 (.nextInt rng 48000))))
                     bs (bin-seq-of rets)
                     start (+ 40 (.nextInt rng (- 400 ctx 60)))
                     label (+ 3 (.indexOf ^java.util.List regimes kind))]]
           {:rets rets
            :tokens (int-array (concat (subvec bs start (+ start (dec ctx))) [label]))
            :targets (int-array (concat (subvec bs (inc start) (+ start (dec ctx)))
                                        [label label]))}))))

(defn classify
  "Regime index (0-3) from the binned window ending at day-1."
  [cfg params bins ^long day]
  (let [w (int-array (concat (subvec bins (max 0 (- day (dec ctx))) day) [0]))
        {:keys [logits]} (llm-nn/forward-1 cfg params w)
        off (* (dec (count w)) (:vocab cfg))]
    (loop [r 0 best 3 bestv -1e30]
      (if (>= r 4)
        (- best 3)
        (let [id (+ 3 r) v (aget ^floats logits (+ off id))]
          (recur (inc r) (if (> v bestv) id best) (max bestv v)))))))

(defn train-classifier!
  "Returns trained params. 24 epochs with lr decay; per-epoch loss and
  classification accuracy are printed so convergence is VERIFIED, not
  assumed."
  [n-per]
  (let [cfg (assoc trade.model/model-cfg :vocab vocab)
        params (llm.model/init cfg 42)
        adam (llm-train/adam-init params)
        train (training-data n-per)]
    (dotimes [e 24]
      (let [lr (if (< e 14) 1e-3 2.5e-4)
            loss (volatile! 0.0)
            correct (volatile! 0)]
        (doseq [{:keys [^ints tokens ^ints targets]} train]
          (let [[l g] (llm-nn/loss-and-grads-1 cfg params tokens targets 1.0)]
            (vswap! loss + l)
            (llm-train/clip-grads! g 1.0)
            (llm-train/adam-step! params g adam
                                  {:lr lr :beta1 0.9 :beta2 0.999 :eps 1e-8 :wd 0.0})))
        ;; verify: argmax over regime tokens at the prediction position
        (doseq [{:keys [^ints tokens ^ints targets]} train]
          (let [{:keys [^floats logits]} (llm-nn/forward-1 cfg params tokens)
                V (:vocab cfg)
                off (* (- (count tokens) 2) V)]
            (loop [r 0 best 3 bestv -1e30]
              (if (>= r 4)
                (when (= (+ best 3) (aget targets (dec (count tokens))))
                  (vswap! correct inc))
                (let [id (+ 3 r) v (aget logits (+ off id))]
                  (recur (inc r) (if (> v bestv) id best) (max bestv v)))))))
        (println (format "  epoch %2d  loss %.4f  train-acc %.1f%%"
                         (inc e) (/ (double @loss) (count train))
                         (* 100.0 (/ @correct (count train)))))))
    {:cfg cfg :params params :train-count (count train)}))

(defn policy-exposures
  "The composed mechanical policy over a real or synthetic return series."
  [{:keys [cfg params]} rets]
  (let [closes (reduce (fn [acc r] (conj acc (* (peek acc) (+ 1.0 r)))) [100.0] rets)
        bins (bin-seq-of rets)]
    (mapv (fn [d]
            (if (< d 40)
              0.0
              (nth (expert-vec (nth regimes (classify cfg params bins d)) closes) d)))
          (range (count rets)))))

(defn sharpe-of [rets exposures]
  (:sharpe (metrics/summarize
            (assoc (backtest/run
                    {:closes (reduce (fn [acc r] (conj acc (* (peek acc) (+ 1.0 r)))) [100.0] rets)
                     :sigs exposures :cost-rate 0.0015})
                   :ppy 365))))
