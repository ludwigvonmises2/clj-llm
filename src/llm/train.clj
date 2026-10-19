(ns llm.train
  "Training loop: batch sampling, parallel gradient computation (one shard
  per CPU core), gradient clipping, AdamW with warmup + cosine decay, and
  Java-serialization checkpoints.")

(set! *warn-on-reflection* true)

(require '[llm.tensor :as t]
         '[llm.nn :as nn]
         '[llm.tokenizer :as tok]
         '[clojure.java.io :as io])

;; ---------------------------------------------------------------------------
;; Data
;; ---------------------------------------------------------------------------

(defn prepare
  "Tokenize `text` and return {:vocab :data int[] :cfg (with :vocab set)}."
  [text cfg]
  (let [v (tok/build-vocab text)]
    {:vocab v
     :data (tok/encode v text)
     :cfg (assoc cfg :vocab (:vocab-size v))}))

(defn make-batch
  "batch-size random (input, target) samples of length ctx. Targets are the
  inputs shifted by one token."
  [^ints data ctx batch-size ^java.util.Random rng]
  (let [max-start (max 1 (- (alength data) ctx))]
    (repeatedly batch-size
                (fn []
                  (let [s  (.nextInt rng max-start)
                        in (int-array ctx)
                        tg (int-array ctx)]
                    (System/arraycopy data s in 0 ctx)
                    (System/arraycopy data (inc s) tg 0 ctx)
                    {:in in :tg tg})))))

;; ---------------------------------------------------------------------------
;; Parallel gradients
;; ---------------------------------------------------------------------------

(defn- shard-grads!
  "Forward+backward over one shard of samples, accumulating loss and grads
  into shard-local arrays. Returns [total-loss grads]."
  [cfg params samples loss-scale]
  (let [loss (volatile! 0.0)
        acc  (volatile! nil)]
    (doseq [{:keys [^ints in ^ints tg]} samples]
      (let [[l g] (nn/loss-and-grads-1 cfg params in tg loss-scale)]
        (vswap! loss + l)
        (vreset! acc
                 (if-let [a @acc]
                   (reduce-kv (fn [m k ^floats v] (t/add-into! (m k) v) m) a g)
                   g))))
    [@loss @acc]))

(defn grads-for-batch!
  "Gradients of the batch mean loss, computed on ~one shard per core.
  Returns [mean-loss grads]."
  [cfg params batch loss-scale]
  (let [nproc    (.availableProcessors (Runtime/getRuntime))
        nshards  (min nproc (count batch))
        per      (int (Math/ceil (/ (count batch) (double nshards))))
        results  (pmap #(shard-grads! cfg params % loss-scale)
                       (partition-all per batch))
        grads    (reduce (fn [a r]
                           (reduce-kv (fn [m k ^floats v] (t/add-into! (m k) v) m)
                                      a (second r)))
                         (second (first results))
                         (rest results))]
    [(/ (reduce + (map first results)) (count batch)) grads]))

;; ---------------------------------------------------------------------------
;; Optimizer
;; ---------------------------------------------------------------------------

(defn adam-init
  "Zeroed first/second moment buffers for every param."
  [params]
  {:m    (into {} (map (fn [[k ^floats v]] [k (t/fzeros (alength v))]) params))
   :v    (into {} (map (fn [[k ^floats v]] [k (t/fzeros (alength v))]) params))
   :step (atom 0)})

(defn clip-grads!
  "Global-norm gradient clipping. Returns the pre-clip global norm."
  ^double [grads ^double max-norm]
  (let [total (Math/sqrt (reduce-kv (fn [^double acc _ v]
                                      (+ acc (let [n (t/l2-norm v)] (* n n))))
                                    0.0 grads))]
    (when (> total max-norm)
      (let [s (float (/ max-norm total))]
        (doseq [[_ v] grads] (t/scale! v s))))
    total))

(defn- matrix-param?
  "Weight matrices get decoupled weight decay; layernorm gains and biases
  are exempt. Matches wte/wpe, l<n>-qkvw, l<n>-ow, l<n>-w1, l<n>-w2."
  [^String name]
  (boolean (re-matches #"(?:l\d+-)?(?:w\d+|qkvw|ow|wte|wpe)" name)))

(defn adam-step!
  "In-place AdamW update. Decoupled weight decay applies to matrix params
  only ([[matrix-param?]]) — layernorm gains and biases are exempt."
  [params grads {:keys [m v step]}
   {:keys [^double lr ^double beta1 ^double beta2 ^double eps ^double wd]}]
  (let [tt  (swap! step inc)
        bc1 (- 1.0 (Math/pow beta1 tt))
        bc2 (- 1.0 (Math/pow beta2 tt))]
    (doseq [[name ^floats p] params
            :let [^floats g  (grads name)
                  ^floats mv (m name)
                  ^floats vv (v name)
                  decay  (if (matrix-param? name) wd 0.0)]]
      (dotimes [i (alength p)]
        (let [gv   (aget g i)
              mi   (+ (* beta1 (aget mv i)) (* (- 1.0 beta1) gv))
              vi   (+ (* beta2 (aget vv i)) (* (- 1.0 beta2) gv gv))
              pi   (aget p i)
              upd  (+ (/ (/ mi bc1) (+ (Math/sqrt (/ vi bc2)) eps)) (* decay pi))]
          (aset mv i (float mi))
          (aset vv i (float vi))
          (aset p i (float (- pi (* lr upd)))))))))

;; ---------------------------------------------------------------------------
;; Schedule + loop
;; ---------------------------------------------------------------------------

(defn lr-at
  "Linear warmup to peak-lr, then cosine decay to 10% of peak."
  ^double [^long step ^long warmup ^long total ^double peak-lr]
  (if (< step warmup)
    (* peak-lr (/ step warmup))
    (let [p (/ (- step warmup) (double (max 1 (- total warmup))))]
      (* peak-lr (+ 0.1 (* 0.9 0.5 (+ 1.0 (Math/cos (* Math/PI p)))))))))

(defn save-checkpoint
  "Java-serialized {:config :params :vocab} map. NOTE: Java serialization
  must never be used to load files from untrusted sources; this format is
  for local checkpointing only."
  [^String path {:keys [config params vocab]}]
  (let [f (io/file path)]
    (io/make-parents f)
    (with-open [^java.io.ObjectOutputStream o
                (java.io.ObjectOutputStream. (io/output-stream f))]
      (.writeObject o {:config config :params params :vocab vocab}))))

(defn load-checkpoint
  "Returns {:config :params :vocab}."
  [^String path]
  (with-open [^java.io.ObjectInputStream i
              (java.io.ObjectInputStream. (io/input-stream path))]
    (.readObject i)))

(defn train!
  "Run the full training loop. Options:
    :cfg :params :data (int[]) :steps :batch :peak-lr :warmup-frac :wd
    :clip :vocab (idx->ch, saved into the checkpoint) :out (checkpoint path)
    :print-every :seed
  Returns {:params :losses :final-loss}."
  [{:keys [cfg params data steps batch peak-lr warmup-frac wd clip vocab out
           print-every seed]
    :or {steps 400 batch 16 peak-lr 2e-3 warmup-frac 0.05 wd 0.01 clip 1.0
         print-every 10 seed 42}}]
  (let [^ints data data
        T        (:ctx cfg)
        _        (when (< (alength data) (inc (long T)))
                   (throw (ex-info "corpus shorter than ctx+1 tokens"
                                   {:len (alength data) :ctx T})))
        _        (when-not (pos? (long batch))
                   (throw (ex-info "batch must be >= 1" {:batch batch})))
        rng      (java.util.Random. (long seed))
        adam     (adam-init params)
        warmup   (max 10 (int (* steps warmup-frac)))
        opt-base {:beta1 0.9 :beta2 0.999 :eps 1e-8 :wd (double wd)}
        t0       (System/nanoTime)
        ema      (volatile! nil)
        losses   (volatile! [])]
    (dotimes [s steps]
      (let [samples      (make-batch data T batch rng)
            lr           (lr-at s warmup steps (double peak-lr))
            [loss grads] (grads-for-batch! cfg params samples (/ 1.0 (* batch T)))]
        (clip-grads! grads clip)
        (adam-step! params grads adam (assoc opt-base :lr lr))
        (vswap! ema (fn [e] (if e (+ (* 0.95 e) (* 0.05 loss)) loss)))
        (vswap! losses conj loss)
        (when (or (zero? (mod (inc s) (max 1 (long print-every)))) (= (inc s) steps))
          (let [dt   (/ (- (System/nanoTime) t0) 1e9)
                done (inc s)]
            (println (format
                      "step %4d/%d  loss %.4f  ema %.4f  lr %.2e  %.0f ms/step  %.1f ktok/s"
                      done steps loss (or @ema loss) lr
                      (/ (* 1000.0 dt) done)
                      (/ (* done batch T) dt 1000.0)))
            (flush)))))
    (when out
      (save-checkpoint out {:config cfg :params params :vocab vocab}))
    {:params params :losses @losses :final-loss (last @losses)}))
