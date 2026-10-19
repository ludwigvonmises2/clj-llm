(ns llm.core
  "CLI entry point.
    clojure -M:run verify
    clojure -M:run train  [--data PATH] [--steps N] [--batch N] [--ctx N]
                          [--d N] [--layers N] [--heads N] [--dff N]
                          [--lr F] [--out PATH] [--seed N]
    clojure -M:run generate [--model PATH] [--prompt STR] [--tokens N]
                            [--temperature F] [--topk N] [--seed N]")

(require '[llm.model :as model]
         '[llm.train :as train]
         '[llm.sample :as sample]
         '[llm.chat :as chat]
         '[llm.data :as data]
         '[llm.verify :as verify])

(defn- parse-opts
  "--key value pairs -> {:key value}"
  [args]
  (loop [m {} [a b & r] args]
    (if a
      (recur (if (and (.startsWith ^String a "--") b)
               (assoc m (keyword (subs a 2)) b)
               m)
             r)
      m)))

(defn- num-opt
  "Parse a numeric CLI value without read-string (which honors #= read-eval).
  Integers stay longs so config dims remain longs."
  [opts k default]
  (if-some [v (get opts k)]
    (or (try (parse-long v) (catch Exception _ nil))
        (try (parse-double v) (catch Exception _ nil))
        default)
    default))

(defn- cmd-train
  "Fresh training, or incremental continuation with --init PATH (dims come
  from the checkpoint; the vocabulary may grow — old embeddings are
  preserved and remapped by character)."
  [opts]
  (let [data-path (get opts :data "data/tinyshakespeare.txt")
        out       (get opts :out "data/model.bin")
        init-path (get opts :init)
        text      (slurp data-path)
        base      (model/default-config)
        cfg0      (merge base {:ctx    (num-opt opts :ctx (:ctx base))
                               :d      (num-opt opts :d (:d base))
                               :layers (num-opt opts :layers (:layers base))
                               :heads  (num-opt opts :heads (:heads base))
                               :dff    (num-opt opts :dff (:dff base))})
        {:keys [vocab data cfg]} (train/prepare text cfg0)
        seed      (long (num-opt opts :seed 42))
        ;; with --init the checkpoint's dims win (config flags ignored)
        [cfg params]
        (if init-path
          (let [ckpt (train/load-checkpoint init-path)
                new-cfg (:config ckpt)
                seeded (model/continue-params
                        {:old-config (:config ckpt)
                         :old-params (:params ckpt)
                         :old-idx->ch (vec (:vocab ckpt))}
                        new-cfg
                        (:idx->ch vocab)
                        seed)]
            (println "continuing from" init-path
                     (format "(old vocab %d -> new vocab %d)"
                             (count (:vocab ckpt)) (count (:idx->ch vocab))))
            [new-cfg seeded])
          [cfg (model/init cfg seed)])]
    (println "config:" (pr-str cfg))
    (println "params:" (model/n-params cfg) " corpus-chars:" (count text))
    (train/train! {:cfg cfg :params params :data data
                   :steps (long (num-opt opts :steps 400))
                   :batch (long (num-opt opts :batch 16))
                   :peak-lr (num-opt opts :lr 2e-3)
                   :warmup-frac 0.05
                   :wd (num-opt opts :wd 0.01)
                   :clip 1.0
                   :vocab (:idx->ch vocab)
                   :out out
                   :print-every (long (num-opt opts :print-every 10))
                   :seed seed})
    (println "checkpoint saved to" out)))

(defn- cmd-instructgen
  [opts]
  (let [out  (get opts :out "data/instruct.txt")
        text (data/instruction-corpus
              {:n-arith (long (num-opt opts :n-arith 15000))
               :n-code  (long (num-opt opts :n-code 13000))
               :n-greet 400
               :seed 7})]
    (spit out text)
    (println (format "wrote %s: %d chars of SFT instruction pairs" out (count text)))))

(defn- cmd-mixdata
  "Build the multi-task mixture: tinyshakespeare + clojure source +
  arithmetic, with arithmetic oversampled by --arith-weight."
  [opts]
  (let [out  (get opts :out "data/mixed.txt")
        aw   (long (num-opt opts :arith-weight 4))
        text (data/mixed-corpus
              [["data/tinyshakespeare.txt" 1]
               ["data/clojure.txt" 1]
               ["data/arith.txt" aw]])]
    (spit out text)
    (println (format "wrote %s: %d chars (arith weight %d)"
                     out (count text) aw))))

(defn- cmd-generate
  [opts]
  (let [ckpt    (train/load-checkpoint (get opts :model "data/model.bin"))
        text    (sample/generate (:config ckpt) (:params ckpt)
                                 (vec (:vocab ckpt))
                                 (get opts :prompt "JULIET:")
                                 (long (num-opt opts :tokens 300))
                                 {:temperature (num-opt opts :temperature 0.8)
                                  :top-k (long (num-opt opts :topk 40))
                                  :seed (long (num-opt opts :seed 7))
                                  :stop-text (get opts :stop)})]
    (println text)))

(defn- cmd-datagen
  [opts]
  (let [out  (get opts :out "data/arith.txt")
        n    (long (num-opt opts :n 40000))
        ;; curriculum: small operands dominate so single-digit queries are
        ;; crisp; larger ranges teach multi-digit structure
        parts [[0.35 9] [0.35 99] [0.30 999]]
        text (apply str
                    (map-indexed (fn [i [f m]]
                                   (data/arith-corpus {:n (long (* n f))
                                                       :max-operand m
                                                       :seed (+ 42 i)}))
                                 parts))
        total (count (re-seq #"\n\n" text))]
    (spit out text)
    (println (format "wrote %s: %d chars, %d examples (curriculum 0..9 / 0..99 / 0..999, ops +/-)"
                     out (count text) total))))

(defn- usage
  []
  (println "llm — a GPT-style transformer in pure Clojure ("
           (clojure-version) ")")
  (println "usage: clojure -M:run <command> [options]
  verify                             run correctness checks
  train    [--data PATH] [--steps N] [--batch N] [--ctx N] [--d N]
           [--layers N] [--heads N] [--dff N] [--lr F] [--wd F]
           [--out PATH] [--seed N] [--print-every N]
           [--init PATH]  continue training from a checkpoint (incremental)
  generate [--model PATH] [--prompt STR] [--tokens N] [--temperature F]
           [--topk N] [--seed N]   (temperature 0 = greedy)
  chat     [--model PATH]  interactive chat with tok/s stats and /bench
  datagen  [--out PATH] [--n N] [--max N]  arithmetic Q/A corpus"))

(defn -main
  [& args]
  (let [cmd (first args)
        opts (parse-opts (rest args))]
    (case cmd
      "verify"   (System/exit (if (verify/run-all) 0 1))
      "train"    (cmd-train opts)
      "generate" (cmd-generate opts)
      "datagen"  (cmd-datagen opts)
      "instructgen" (cmd-instructgen opts)
      "mixdata"  (cmd-mixdata opts)
      "chat"     (apply chat/-main (rest args))
      (usage))))
