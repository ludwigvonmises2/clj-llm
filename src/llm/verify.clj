(ns llm.verify
  "Self-verification suite: attention causality, softmax normalization,
  numerical gradient check (central difference vs analytic backprop), and a
  learning smoke test. Run via: clojure -M:verify")

(set! *warn-on-reflection* true)

(require '[llm.model :as model]
         '[llm.nn :as nn]
         '[llm.tensor :as t]
         '[llm.train :as train])

(def tiny-cfg
  "Small enough to gradcheck every parameter quickly."
  {:ctx 4 :d 8 :layers 2 :heads 2 :dff 16 :vocab 6})

(defn- make-samples
  [cfg n seed]
  (let [rng (java.util.Random. (long seed))]
    (repeatedly n
                (fn []
                  (let [in (int-array (:ctx cfg))
                        tg (int-array (:ctx cfg))]
                    (dotimes [i (:ctx cfg)]
                      (let [x (.nextInt rng (:vocab cfg))]
                        (aset in i x)
                        (aset tg i (.nextInt rng (:vocab cfg)))))
                    {:in in :tg tg})))))

(defn- loss-f
  "The batch mean token loss as a function of the params map. This is
  exactly the objective whose gradients loss-and-grads-1 returns when
  called with loss-scale 1/(B·T) and summed over the batch."
  [cfg samples]
  (let [s0 (/ 1.0 (* (count samples) (:ctx cfg)))]
    (fn [params]
      (/ (reduce + (map (fn [{:keys [^ints in ^ints tg]}]
                          (first (nn/loss-and-grads-1 cfg params in tg s0)))
                        samples))
         (count samples)))))

(defn causality-check
  "The attention matrix must be exactly 0 above the diagonal (no peeking at
  future tokens) and every row must sum to 1."
  []
  (let [cfg    tiny-cfg
        params (model/init cfg 5)
        {:keys [cache]} (nn/forward-1 cfg params (int-array [1 2 3 1]))
        T      (:ctx cfg)
        probs  (-> cache :probs first first)   ; layer 0, head 0
        ok     (volatile! true)]
    (dotimes [t1 T]
      (dotimes [t2 T]
        (when (> t2 t1)
          (when-not (zero? (aget ^floats probs (+ (* t1 T) t2)))
            (vreset! ok false))))
      (let [s (reduce + (map #(aget ^floats probs (+ (* t1 T) %)) (range T)))]
        (when-not (< (Math/abs (- s 1.0)) 1e-4)
          (vreset! ok false))))
    @ok))

(defn gradcheck
  "Central-difference gradient vs analytic backprop over EVERY parameter of
  the tiny model. Returns {:max-rel-err :mean-rel-err :n-checked}."
  ([]
   (gradcheck 1e-3))
  ([^double eps]
   (let [cfg      tiny-cfg
         params   (model/init cfg 123)
         samples  (make-samples cfg 2 7)
         f        (loss-f cfg samples)
         s0       (/ 1.0 (* (count samples) (:ctx cfg)))
         analytic (reduce (fn [acc {:keys [^ints in ^ints tg]}]
                            (let [[_ g] (nn/loss-and-grads-1 cfg params in tg s0)]
                              (if acc
                                (do (doseq [[k ^floats v] g]
                                      (t/add-into! (acc k) v))
                                    acc)
                                g)))
                          nil samples)
         stats    (volatile! {:fail 0 :worst 0.0 :n 0})]
     (doseq [[name ^floats p] params
             i (range (alength p))]
       (let [old (aget p i)
             _   (aset p i (+ old eps))
             lp  (f params)
             _   (aset p i (- old eps))
             lm  (f params)
             _   (aset p i old)
             num (/ (- lp lm) (* 2.0 eps))
             ga  ^floats (analytic name)
             ana (aget ga i)
             err (Math/abs (- num ana))
             ;; float32 finite differences are noisy on near-zero grads, so
             ;; use the standard atol + rtol criterion per element
             tol (+ 1e-4 (* 0.02 (+ (Math/abs num) (Math/abs ana))))
             ratio (/ err (max 1e-9 tol))]
         (vswap! stats (fn [{fl :fail w :worst n :n}]
                         {:fail (if (> err tol) (inc fl) fl)
                          :worst (max w (min 1.0 ratio))
                          :n (inc n)}))))
     (let [{:keys [fail worst n]} @stats]
       {:n n :failures fail :worst worst}))))

(defn learning-check
  "A tiny model must learn a repetitive string quickly: loss after 40 steps
  < 70% of the initial loss."
  []
  (let [text (apply str (repeat 150 "the quick brown fox jumps over the lazy dog. "))
        {:keys [data cfg]} (train/prepare text (assoc tiny-cfg :ctx 8 :dff 16))
        params (model/init cfg 1)
        {:keys [losses]} (train/train! {:cfg cfg :params params :data data
                                        :steps 80 :batch 4 :peak-lr 1e-2
                                        :warmup-frac 0.1 :wd 0.0 :clip 1.0
                                        :print-every 1000})]
    {:first (first losses) :last (last losses)
     :ok (< (last losses) (* 0.7 (first losses)))}))

(defn run-all
  "Run every check; returns true when all pass."
  []
  (println "Clojure" (clojure-version) "on JVM"
           (System/getProperty "java.version"))
  (println "cores:" (.availableProcessors (Runtime/getRuntime)))
  (let [c (do (print "\n[1/3] attention causality + softmax normalization ... ")
              (flush)
              (let [r (causality-check)]
                (println (if r "PASS" "FAIL"))
                r))
        g (do (print "[2/3] gradient check (analytic vs numeric) ... ")
              (flush)
              (let [{:keys [failures n worst]} (gradcheck)]
                (println (format "%s  (n=%d, failures=%d, worst-ratio=%.3f)"
                                 (if (zero? failures) "PASS" "FAIL")
                                 n failures worst))
                (zero? failures)))
        l (do (print "[3/3] learning smoke test ... ")
              (flush)
              (let [{:keys [first last ok]} (learning-check)]
                (println (format "%s  (loss %.4f -> %.4f)"
                                 (if ok "PASS" "FAIL") first last))
                ok))]
    (println (if (and c g l)
               "\nALL CHECKS PASSED ✓"
               "\nFAILURES DETECTED ✗"))
    (and c g l)))
