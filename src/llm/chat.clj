(ns llm.chat
  "Interactive terminal chat with a trained checkpoint. Streams generated
  characters as they are sampled and reports throughput (tok/s) after every
  reply, plus a /bench command for clean throughput measurement.

  Run: clojure -M:run chat [--model PATH]"
  (:require [clojure.string :as str]
            [llm.model :as model]
            [llm.train :as train]
            [llm.sample :as sample])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

;; ---------------------------------------------------------------------------
;; helpers
;; ---------------------------------------------------------------------------

(defn- parse-opts
  "--key value pairs -> {:key value}. Tolerates stray positional tokens:
  a non-flag token shifts by one instead of swallowing the next token."
  [args]
  (loop [m {} [a b & r] args]
    (if a
      (if (and (.startsWith ^String a "--") b)
        (recur (assoc m (keyword (subs a 2)) b) r)
        (recur m (cons b r)))
      m)))

(defn- arg-num
  "Numeric argument of a command line like \"/temp 0.5\" (nil if absent/bad)."
  [^String line]
  (when-some [tok (second (str/split line #"\s+"))]
    (or (try (parse-long tok) (catch Exception _ nil))
        (try (parse-double tok) (catch Exception _ nil)))))

(defn- crop
  "Tail of s, at most n chars."
  [^String s ^long n]
  (if (> (count s) n) (subs s (- (count s) n)) s))

(defn- run-generation
  "Generate after `prompt` with the incremental KV cache; returns
  {:continuation :tokens :secs :tps}. Streams through on-token (fn [id])."
  [cfg params idx->ch ^String prompt n-tokens opts on-token]
  (let [t0   (System/nanoTime)
        full (sample/generate-cached cfg params idx->ch prompt n-tokens
                                     (assoc opts :on-token
                                            (when on-token
                                              (fn [id]
                                                (print (nth idx->ch id \?))
                                                (flush)))))
        dt   (- (System/nanoTime) t0)]
    {:continuation (subs full (count prompt))
     :tokens       n-tokens
     :secs         (/ dt 1e9)
     :tps          (/ n-tokens (/ dt 1e9))}))

(defn- print-help []
  (println "comandos:
  /temp F     temperatura (0 = greedy/argmax)      ej: /temp 0.7
  /topk N     top-k sampling                       ej: /topk 20
  /len N      tokens por respuesta                 ej: /len 300
  /seed N     semilla (0 = aleatoria por turno)
  /stop TXT   texto de parada temprana (\"off\" desactiva; por defecto salto doble)
  /bench N    mide tok/s limpio sobre el contexto  ej: /bench 100
  /reset      borra el contexto de conversación
  /exit       salir"))

;; ---------------------------------------------------------------------------
;; reply + commands
;; ---------------------------------------------------------------------------

(defn- reply!
  [cfg params idx->ch state ^String line]
  (let [{:keys [history temp topk len seed]} @state
        seed      (if (zero? (long seed)) (mod (System/nanoTime) 1000000) seed)
        base      (str history "\n<usuario> " line "\n<asistente> ")
        prompt    (crop base (:ctx cfg))
        {:keys [continuation tokens secs tps]}
        (run-generation cfg params idx->ch prompt len
                        {:temperature temp :top-k topk :seed seed
                         :stop-text (:stop @state)}
                        (fn [id] (print (nth idx->ch id \?)) (flush)))]
    (swap! state assoc :history (str base continuation))
    (println)
    (println (format "  ── %.1f tok/s · %d tokens · %.1f s · temp %.2f · top-k %d · seed %d"
                     tps tokens secs temp topk seed))))

(defn- bench!
  [cfg params idx->ch state ^String line]
  (let [n      (long (or (arg-num line) 100))
        prompt (crop (:history @state) (:ctx cfg))
        {:keys [tokens secs tps]}
        (run-generation cfg params idx->ch prompt n
                        {:temperature (:temp @state) :top-k (:topk @state)
                         :seed 1 :stop-text (:stop @state)}
                        nil)]
    (println (format "  bench: %.1f tok/s · %d tokens · %.1f s (sin streaming, ctx %d chars)"
                     tps tokens secs (count prompt)))))

(defn- handle-command!
  "Returns false when the loop should exit."
  [cfg params idx->ch state ^String line]
  (cond
    (= line "/help")            (print-help)
    (= line "/reset")           (do (swap! state assoc :history "")
                                    (println "· contexto borrado"))
    (= line "/exit")            false
    (= line "/quit")            false
    (.startsWith line "/temp")  (do (swap! state assoc :temp (double (or (arg-num line) 0.8)))
                                    (println "· temp =" (:temp @state)))
    (.startsWith line "/topk")  (do (swap! state assoc :topk (long (or (arg-num line) 40)))
                                    (println "· topk =" (:topk @state)))
    (.startsWith line "/len")   (do (swap! state assoc :len (long (or (arg-num line) 200)))
                                    (println "· len =" (:len @state)))
    (.startsWith line "/seed")  (do (swap! state assoc :seed (long (or (arg-num line) 0)))
                                    (println "· seed =" (:seed @state) "(0 = aleatoria)"))
    (.startsWith line "/stop")  (do (let [arg (str/trim (subs line 5))]
                                      (swap! state assoc :stop
                                             (cond
                                               (str/blank? arg) nil
                                               (= arg "off") nil
                                               :else (str/replace arg "\\n" "\n")))
                                      (println "· stop-text =" (pr-str (:stop @state))))
                                    true)
    (.startsWith line "/bench") (do (bench! cfg params idx->ch state line) true)
    :else                       (do (println "· comando desconocido, /help") true)))

;; ---------------------------------------------------------------------------
;; main loop
;; ---------------------------------------------------------------------------

(defn -main
  [& args]
  (let [model-path (get (parse-opts args) :model "data/model.bin")
        ckpt       (train/load-checkpoint model-path)
        cfg        (:config ckpt)
        params     (:params ckpt)
        idx->ch    (vec (:vocab ckpt))
        state      (atom {:history "" :temp 0.8 :topk 40 :len 200 :seed 0
                          :stop "\n\n"})]
    (println)
    (println "clj-llm chat — Clojure" (clojure-version) "· JVM"
             (System/getProperty "java.version") "·"
             (.availableProcessors (Runtime/getRuntime)) "cores")
    (println (format "modelo: %s · %d params · ctx %d · vocab %d · %d capas · d %d"
                     model-path (model/n-params cfg) (:ctx cfg)
                     (count idx->ch) (:layers cfg) (:d cfg)))
    (print-help)
    (loop []
      (print "\nusted> ")
      (flush)
      (when-some [line (try (read-line) (catch Exception _ nil))]
        (let [line (str/trim line)]
          (when (and (seq line)
                     (if (str/starts-with? line "/")
                       (handle-command! cfg params idx->ch state line)
                       (do (reply! cfg params idx->ch state line) true))))
          (recur))))
    (println "hasta luego")))
