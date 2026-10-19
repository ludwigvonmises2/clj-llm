;; Rejection-sampling demo: generate code candidates from the code model
;; and keep only the ones whose first form parses (syntactically valid
;; Clojure by construction).
;; Run: clojure -M dev/codegen_demo.clj
(require '[llm.train :as train]
         '[llm.sample :as sample])

(def ckpt (train/load-checkpoint "data/code-model.bin"))
(def cfg (:config ckpt))
(def params (:params ckpt))
(def idx->ch (vec (:vocab ckpt)))

(defn first-balanced-form
  "Longest prefix of s that is a single balanced form (strings respected).
  Returns nil when parens go negative or never close."
  [^String s]
  (loop [i 0 depth 0 in-str? false esc? false]
    (if (>= i (count s))
      nil
      (let [c (nth s i)]
        (cond
          esc?          (recur (inc i) depth in-str? false)
          (and in-str? (= c \\)) (recur (inc i) depth in-str? true)
          in-str?       (recur (inc i) depth true false)
          (= c \")      (recur (inc i) depth true false)
          (= c \()      (recur (inc i) (inc depth) false false)
          (= c \))      (let [d (dec depth)]
                          (cond
                            (neg? d)  nil
                            (zero? d) (subs s 0 (inc i))
                            :else     (recur (inc i) d false false)))
          :else         (recur (inc i) depth false false))))))

(defn valid-candidates
  "Draw n-candidates generations and keep the ones whose first balanced
  form reads back. Returns [forms tried]."
  [{:keys [prompt n-candidates n-candidates-limit tokens temperature top-k]}]
  (let [forms (atom [])
        tried (atom 0)]
    (dotimes [i n-candidates-limit]
      (when (and (< (count @forms) n-candidates) (< @tried n-candidates-limit))
        (swap! tried inc)
        (let [raw  (sample/generate cfg params idx->ch prompt tokens
                                    {:temperature temperature :top-k top-k
                                     :seed (+ 100 i)
                                     :stop-text "\n\n\n"})
              form (first-balanced-form raw)]
          (when form
            (try
              (read-string form)
              (swap! forms conj form)
              (catch Exception _ nil))))))
    {:forms @forms :tried @tried}))

(defn -main []
  (println "generating candidates from data/code-model.bin ...")
  (let [{:keys [forms tried]} (valid-candidates
                               {:prompt "(defn"
                                :n-candidates 3
                                :n-candidates-limit 60
                                :tokens 250
                                :temperature 0.85
                                :top-k 30})]
    (doseq [f forms]
      (println "════ valid form (parses) ════")
      (println f))
    (println (format ":valid %d :tried %d" (count forms) tried))))

(-main)
