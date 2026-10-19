;; Arithmetic accuracy scorer: greedy answers to fresh problems, exact
;; match. Used to measure incremental improvement between checkpoints
;; (v1 vs v2) on identical problems.
;; Run: clojure -M dev/eval_arith.clj data/unified-v1.bin data/unified-v2.bin
(require '[llm.train :as train]
         '[llm.sample :as sample]
         '[clojure.string :as str])

(defn gen-problems
  "n deterministic (a, b) addition problems across the curriculum ranges."
  [n]
  (let [rng (java.util.Random. 2026)]
    (vec (for [i (range (long n))
               :let [rng2 (java.util.Random. (+ 1000 i))]
               :let [maxop (cond (< i (* 0.5 n)) 9
                                 (< i (* 0.8 n)) 99
                                 :else 999)
                     a (.nextInt rng2 (inc maxop))
                     b (.nextInt rng2 (inc maxop))]]
           [a b]))))

(defn answer
  "Greedy answer of the model to 'a+b', first line only."
  [cfg params idx->ch [a b]]
  (let [expr (str a "+" b)
        out  (sample/generate cfg params idx->ch (str "\n" expr "\n") 10
                              {:temperature 0.0 :stop-text "\n\n"})
        ans  (first (str/split-lines (subs out (count (str "\n" expr "\n")))))]
    (str/trim (or ans ""))))

(defn score
  [model-path problems]
  (let [ckpt    (train/load-checkpoint model-path)
        cfg     (:config ckpt)
        params  (:params ckpt)
        idx->ch (vec (:vocab ckpt))
        results (for [p problems]
                  (let [got (answer cfg params idx->ch p)
                        want (str (+ (p 0) (p 1)))]
                    {:p p :got got :ok? (= got want)}))]
    {:model model-path
     :correct (count (filter :ok? results))
     :total (count results)
     :results results}))

(defn -main
  [args]
  (let [problems (gen-problems 30)]
    (doseq [m args]
      (let [{:keys [correct total results]} (score m problems)]
        (println (format "%s: %d/%d (%.0f%%)" m correct total (* 100.0 (/ correct total))))
        (doseq [{:keys [p got ok?]} results
                :when (not ok?)]
          (println "   ✗" (str (p 0) "+" (p 1)) "=" (+ (p 0) (p 1)) "· el modelo dijo:" (pr-str got)))))))

(-main *command-line-args*)
