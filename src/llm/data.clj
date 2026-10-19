(ns llm.data
  "Synthetic training corpora. Arithmetic pairs teach a small char-level
  model to answer questions like '1+1' with '2' through pure pattern
  completion: the chat context ends with the user's line after a newline,
  so examples use the bare format '<expr>\\n<result>\\n\\n' — exactly the
  prefix shape the model sees at inference time."
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)

(defn- swap-if-needed
  "For subtraction keep a >= b so results are non-negative."
  [[a b]] (if (< a b) [b a] [a b]))

(defn arith-corpus
  "n examples of 'a<bop>b\\nresult\\n\\n' with operands in [0, max-operand]
  drawn from a seeded RNG (deterministic)."
  [{:keys [n max-operand ops seed]
    :or {n 30000 max-operand 99 ops [:+ :-] seed 42}}]
  (let [rng (java.util.Random. (long seed))
        op->sym {:+ "+" :- "-"}]
    (str/join
     (for [_ (range (long n))
           :let [op  (nth ops (.nextInt rng (count ops)))
                 [a b] (swap-if-needed
                        [(.nextInt rng (inc (long max-operand)))
                         (.nextInt rng (inc (long max-operand)))])
                 r (case op :+ (+ a b) :- (- a b))]]
       (str a (op->sym op) b "\n" r "\n\n")))))

(defn mixed-corpus
  "Reads [path weight] sections and concatenates each file `weight` times,
  separated by blank lines — the multi-task mixture for a unified model
  (conversation + code + arithmetic), with arithmetic oversampled to keep
  the algorithmic task from being drowned out."
  [sections]
  (str/join "\n\n\n"
            (for [[path w] sections
                  _ (range (long w))]
              (slurp path))))

;; ---------------------------------------------------------------------------
;; Instruction tuning (SFT): templated user/assistant pairs. Narrow is the
;; point — a model this size generalizes over phrasings of a few intents,
;; not over arbitrary human language.
;; ---------------------------------------------------------------------------

(def ^:private add-asks
  ["¿cuánto es {a}+{b}?" "cuánto es {a}+{b}" "cuanto es {a}+{b}"
   "suma {a} y {b}" "calcula {a}+{b}" "resuelve {a}+{b}" "what is {a}+{b}?"])

(def ^:private sub-asks
  ["¿cuánto es {a}-{b}?" "cuánto es {a}-{b}" "resta {b} a {a}"
   "calcula {a}-{b}" "what is {a}-{b}?"])

(def ^:private code-sum-asks
  ["escríbeme una función que sume {a} y {b}"
   "escribeme una funcion en clojure que sume {a} y {b}"
   "hazme una función que sume {a} y {b}"
   "quiero una función que sume {a} y {b}"
   "crea una función que sume {a} y {b}"
   "escríbeme una función en clojure que sume {a} y {b}"])

(def ^:private code-sub-asks
  ["escríbeme una función que reste {b} a {a}"
   "escribeme una funcion en clojure que reste {b} a {a}"
   "hazme una función que reste {b} a {a}"
   "quiero una función que reste {b} a {a}"])

(def ^:private greetings
  ["hola" "¡hola!" "buenas" "hey" "hello" "hola, ¿quién eres?"])

(def ^:private greeting-replies
  ["¡hola! pídeme una suma, una resta o una función en clojure."
   "saludos. puedo calcular sumas y restas o escribirte funciones clojure."
   "hola. prueba: '¿cuánto es 2+2?' o 'escríbeme una función que sume 3 y 4'."])

(defn- fill
  [^String tpl a b]
  (.. tpl (replace "{a}" (str a)) (replace "{b}" (str b))))

(defn- pick
  [^java.util.Random rng coll]
  (nth coll (.nextInt rng (count coll))))

(defn- rand-pair
  "Two operands for op; subtraction keeps a >= b."
  [^java.util.Random rng op max-operand]
  (let [x (.nextInt rng (inc (long max-operand)))
        y (.nextInt rng (inc (long max-operand)))]
    (if (and (= op :-) (< x y)) [y x] [x y])))

(defn instruction-corpus
  "SFT pairs '<usuario> <instruction>\\n<asistente> <response>\\n\\n'.
  Intents: arithmetic questions (result), 'write me a function' code
  requests (Clojure fn whose body embeds the operands), greetings."
  [{:keys [n-arith n-code n-greet max-operand seed]
    :or {n-arith 15000 n-code 13000 n-greet 400 max-operand 99 seed 7}}]
  (let [rng (java.util.Random. (long seed))
        p (fn [s] (str "<usuario> " s "\n<asistente> "))
        arith (for [_ (range (long n-arith))
                    :let [op (pick rng [:+ :-])
                          [a b] (rand-pair rng op max-operand)
                          r (case op :+ (+ a b) :- (- a b))
                          tpl (pick rng (case op :+ add-asks sub-asks))]]
                (str (p (fill tpl a b)) r "\n\n"))
        code (for [_ (range (long n-code))
                   :let [op (pick rng [:+ :-])
                         [a b] (rand-pair rng op max-operand)
                         tpl (pick rng (case op :+ code-sum-asks code-sub-asks))
                         fname (case op :+ "sumar" "restar")
                         opfn (case op :+ "+" "-")
                         variant (pick rng [:general :literal])]]
               (str (p (fill tpl a b))
                    (if (= variant :general)
                      (str "(defn " fname "\n  [a b]\n  (" opfn " a b))\n\n")
                      (str "(defn calculo\n  []\n  (" opfn " " a " " b "))\n\n"))))
        greets (for [_ (range (long n-greet))
                     :let [g (pick rng greetings)
                           r (pick rng greeting-replies)]]
                 (str (p g) r "\n\n"))]
    (str/join (concat arith code greets))))
