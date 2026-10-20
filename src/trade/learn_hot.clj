(ns trade.learn-hot
  "El aprendiz en caliente — y NUNCA parte de cero.

  Corpus inmediato: el histórico real ya descargado (data/market, 20
  criptos × 1 año de 1m verificado) + permutaciones Monte Carlo de ese
  histórico (bootstrap por bloques + amplificación de volatilidad:
  escenarios que pudieron pasar y no pasaron). Las cintas en vivo se
  suman a medida que crecen.

  Candidatos: una familia pequeña de perfiles (conservador/medio/
  scalping) evaluada con fold-verify — la MISMA signals/mr-zscore que
  opera en vivo — sobre real Y sintético. El campeón (mejor registro en
  real, tasa sintética >= 50%) se escribe en live/champion.edn: la fuente
  única que el cerebro y los motores leen.

  Run: clojure -M -m trade.learn-hot [cinta] [--sims N]"
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [trade.signals :as signals]
            [trade.portfolio :as portfolio])
  (:import (java.nio.file Files Paths StandardOpenOption)))

(def ^:private ppy-1m 525600)
(def ^:private cost 0.0005)

(defn parse-closes
  "Acepta los dos formatos de data/market: mapa con :c (1m) o kline
  crudo de Binance como vector (close en índice 4) (1s/1h)."
  [bars]
  (mapv (fn [b]
          (Double/parseDouble
           (str (if (map? b) (:c b) (nth b 4)))))
        bars))

(def ^:private profiles
  [{:name "conservador" :k 60 :w 360 :threshold 3.0 :ppy ppy-1m :tv 0.30}
   {:name "medio"       :k 40 :w 240 :threshold 2.2 :ppy ppy-1m :tv 0.30}
   {:name "scalping"    :k 20 :w 120 :threshold 1.5 :ppy ppy-1m :tv 0.30}])

(defn sigfn-for
  [{:keys [k w threshold tv ppy] :or {tv 0.30 ppy ppy-1m}}]
  (fn [closes]
    (signals/mr-zscore {:closes closes :k k :w w :threshold threshold
                        :side :long-dip :target-vol tv
                        :max-lev 2.0 :ppy ppy})))

(defn read-binary-tape
  "Cintas binarias del recorder: 12 bytes por print (sec u32 + px f64)."
  [path]
  (let [raw (Files/readAllBytes (Paths/get path (into-array String [])))
        n (quot (alength raw) 12)]
    (mapv (fn [i]
            (let [bb (java.nio.ByteBuffer/wrap raw)
                  _ (.position bb (* 12 i))
                  sec (.getInt bb)
                  px (.getDouble bb)]
              [sec px]))
          (range n))))

(defn closes-from-history
  "El corpus inmediato: histórico real ya descargado (1m)."
  [symbol bars]
  (let [closes (mapv (fn [b] (:c b)) bars)]
    (subvec closes (max 0 (- (count closes) 30000)))))

(defn block-bootstrap [closes rng]
  (let [n (count closes)]
    (loop [out [] i 0]
      (if (>= i n)
        out
        (let [start (rand-int n)
              blen (+ 30 (rand-int 150))]
          (recur (into out (take blen (drop start (cycle closes))))
                 (+ i blen)))))))

(defn amplify [closes factor]
  (let [prev (volatile! (first closes))]
    (into [(first closes)]
          (map (fn [c]
                 (let [r (* factor (- (/ c @prev) 1.0))
                       nx (* @prev (inc r))]
                   (vreset! prev nx)
                   nx)))
          (rest closes))))

(defn main
  [& args]
  (println "learn-hot: arrancando — corpus histórico + Monte Carlo")
  (let [symbol (or (first args) "BTCUSDT")
        hist-path (str "data/market/" symbol "_1m.json")
        bars (json/parse-string (slurp hist-path) true)
        closes (->> bars parse-closes
                    (drop (- (count bars) 30000))
                    vec)
        _ (println "corpus:" (count closes) "velas 1m reales")
        rng (java.util.Random. 42)
        scenarios
        (concat
         ;; real primero: el juez es el mercado que pasó
         [{:name "real" :closes closes}]
         ;; Monte Carlo: 4 permutaciones + 2 amplificaciones de vol
         (for [i (range 4)]
           {:name (str "bootstrap-" (inc i))
            :closes (vec (take (count closes)
                               (drop (+ 300 (rand-int 20000))
                                     (cycle (block-bootstrap closes rng)))))})
         (for [f [0.5 2.0]]
           {:name (str "vol-x" f)
            :closes (vec (amplify closes f))}))
        resultados
        (for [prof profiles]
          (let [sigfn (sigfn-for prof)
                [w n] (portfolio/fold-verify closes sigfn ppy-1m cost 6 10000 false)
                ;; sintético: tasa de pliegues ganados sobre escenarios
                synth (mapcat (fn [{:keys [closes]}]
                                (portfolio/fold-verify
                                 (vec (take 12000 closes))
                                 sigfn ppy-1m cost 3 5000 false))
                              (take 3 (drop 1 scenarios)))
                sw (first synth) sn (second synth)
                tasa (if (pos? sn) (double (/ sw sn)) 0.0)]
            (assoc prof :real-wins w :real-folds n :synth-rate tasa)))
        _ (doseq [r resultados]
            (println (format "  %-11s real %d/%d | sintético %s"
                             (:name r) (:real-wins r) (:real-folds r)
                             (format "%.2f" (:synth-rate r)))))
        ;; orden natural [wins rate], último = mejor (juxt+> rompe:
        ;; el comparador recibe un vector); PUERTA: sin pliegues
        ;; ganados no hay campeón (regla 3)
        campeon (last (sort-by (juxt :real-wins :synth-rate) resultados))]
    (if (pos? (:real-wins campeon))
      (do (spit "live/champion.edn"
                (str "{:k " (:k campeon) " :w " (:w campeon)
                     " :threshold " (:threshold campeon)
                     " :name \"" (:name campeon) "\""
                     " :real-wins " (:real-wins campeon)
                     " :real-folds " (:real-folds campeon) "}\n"))
          (println "campeón:" (:name campeon) "→ live/champion.edn"))
      (println "PERFILES: ninguno gana pliegues — no se promueve"))))

(defn ^:private grid-1s
  "Entrenamiento del SCALPING AGRESIVO: grid de la familia sobre el
  corpus 1s real (BTC/ETH descargados) + estabilidad de vecinos como
  criterio anti-sobreajuste. Campeón = mediana más alta del vecindario."
  [closes-1s]
  (let [configs (for [k [20 40 60] w [120 240 360] thr [1.5 2.0 2.5]]
                  {:k k :w w :threshold thr :ppy 31536000 :tv 0.30})
        score (fn [cfg]
                (let [sigfn (sigfn-for (assoc cfg :ppy 31536000 :tv 0.30))
                      [w n] (portfolio/fold-verify closes-1s sigfn 31536000
                                                   cost 6 5000 false)]
                  (if (pos? n) (double (/ w n)) 0.0)))
        scored (doall
                (for [cfg configs]
                  (let [t0 (System/currentTimeMillis)
                        s (score cfg)]
                    (println (format "  k=%d w=%d z=%.1f -> tasa pliegues %.2f (%.0fs)"
                                     (:k cfg) (:w cfg) (:threshold cfg)
                                     s (/ (- (System/currentTimeMillis) t0) 1000.0)))
                    (assoc cfg :score s))))
        ;; estabilidad: media del vecindario k±, w±, z±
        vecinos (fn [cfg]
                  (let [cs (filter (fn [c]
                                     (and (not= c cfg)
                                          (<= (Math/abs (- (:k c) (:k cfg))) 20)
                                          (<= (Math/abs (- (:w c) (:w cfg))) 120)
                                          (<= (Math/abs (- (:threshold c) (:threshold cfg))) 0.5)))
                                   scored)]
                    (if (seq cs)
                      (/ (reduce + (map :score cs)) (count cs))
                      0.0)))
        ranked (map (fn [c] (assoc c :vecinos (vecinos c))) scored)
        ;; orden natural por [vecinos score] y ÚLTIMO = mejor (sort-by
        ;; con juxt+> rompe: el comparador recibe un vector, no un nº)
        campeon (last (sort-by (juxt :vecinos :score) ranked))]
    ;; PUERTA DE PROMOCIÓN (regla 3): un campeón con score 0 — que no
    ;; gana NI UN pliegue con costes reales — jamás se promueve; el
    ;; cerebro sigue con sus defaults validados o con lo que haya
    (if (pos? (:score campeon))
      (do
        (println (format "CAMPEÓN SCALP: k=%d w=%d z=%.1f | score %.2f | vecinos %.2f"
                         (:k campeon) (:w campeon) (:threshold campeon)
                         (:score campeon) (:vecinos campeon)))
        ;; escritura ATÓMICA preservando :swing aprendido (fallo M8: el
        ;; placeholder pisaba el swing del ciclo --1m)
        (let [prev (try (edn/read-string (slurp "live/champion.edn"))
                        (catch Exception _ {}))
              tmp "live/champion.edn.tmp"]
          (spit tmp (str (assoc (or prev {})
                                :scalp {:k (:k campeon) :w (:w campeon)
                                        :threshold (:threshold campeon)
                                        :name "scalping-grid"
                                        :score (:score campeon)
                                        :vecinos (:vecinos campeon)}) "\n"))
          (java.nio.file.Files/move
           (java.nio.file.Paths/get (str tmp) (into-array String []))
           (java.nio.file.Paths/get "live/champion.edn" (into-array String []))
           (into-array java.nio.file.StandardCopyOption
                       [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))
      (println "SCALP: ninguna config gana pliegues con costes reales —"
               "NO se promueve campeón (la señal pura no basta en 1s)"))
    campeon))

;; ---------- APRENDIZAJE DESDE CINTAS (por venue y activo) ----------

(defn read-prints-tape
  "Cinta binaria '<Id' intercalado LE (sec u32 + px f64) → closes 1s.
  Lee solo las últimas max-closes velas del fichero."
  [path max-closes]
  (let [raw (Files/readAllBytes (Paths/get path (into-array String [])))
        recs (quot (alength raw) 12)
        n (min max-closes recs)
        bb (doto (java.nio.ByteBuffer/wrap raw)
             (.order java.nio.ByteOrder/LITTLE_ENDIAN)
             (.position (* 12 (- recs n))))]
    (loop [i 0 prev -1 cur nil acc (transient [])]
      (if (>= i n)
        (let [v (persistent! (if (and cur prev (not= prev -1))
                               (conj! acc cur) acc))]
          (vec v))
        (let [sec (.getInt bb)
              px (.getDouble bb)]
          (cond
            (= sec prev) (recur (inc i) sec px acc)
            (neg? prev) (recur (inc i) sec px acc)
            :else (recur (inc i) sec px (conj! acc cur))))))))

(defn tape-series
  "Todas las cintas .prints de los últimos días → series de closes por
  venue:activo, las n-más largas primero."
  [dir n max-closes]
  (->> (file-seq (io/file dir))
       (filter (fn [f] (-> (.getName f) (.endsWith ".prints"))))
       (sort-by #(.lastModified %) >)
       (map (fn [f]
              (let [nm (.getName f)                       ; 20261020_okx:btc-usdt-swap.prints
                    tag (subs nm 9 (- (count nm) 7))]
                {:venue tag
                 :closes (try (read-prints-tape (.getPath f) max-closes)
                              (catch Exception _ []))})))
       (filter (fn [s] (>= (count (:closes s)) 500)))
       (take n)
       (vec)))

(def ^:private tape-configs
  ;; familia scalp condensada (9 configs): las de la escalera del grid
  (for [k [20 40 60] w [120 360] thr [1.5 2.5]]
    {:k k :w w :threshold thr :ppy 31536000 :tv 0.30}))

(defn train-tapes
  "El aprendiz sobre las CINTAS reales de cada venue y activo, y sus
  escenarios sintéticos Monte Carlo (bootstrap por bloques de la propia
  cinta + amplificación de vol 0.5×/2×): la microestructura validada es
  LA DE ESA VENUE, no un sustituto genérico. Promueve :scalp solo si la
  config gana en real Y no pierde en la mayoría de los sintéticos."
  []
  (let [series (tape-series "live/tape" 8 20000)]
    (if (empty? series)
      (println "TAPES: sin cintas suficientes todavía (mín. 500 velas/venue)")
      (do
        (println "TAPES: entrenando sobre" (count series) "series de venues:")
        (doseq [s series] (println "  " (:venue s) (count (:closes s)) "velas"))
        (let [rng (java.util.Random. 42)
              score (fn [closes cfg]
                      (let [sigfn (sigfn-for cfg)
                            [w n] (portfolio/fold-verify closes sigfn 31536000
                                                         cost 3 8000 false)]
                        (if (pos? n) (double (/ w n)) 0.0)))
              ranked
              (doall
               (for [cfg tape-configs]
                 (let [t0 (System/currentTimeMillis)
                       per-venue
                       (for [s series]
                         (let [cl (:closes s)
                               real (score cl cfg)
                               ;; Monte Carlo de LA PROPIA CINTA:
                               ;; bootstrap por bloques + amplificación 2×
                               b1 (block-bootstrap cl rng)
                               s1 (score (vec (take 8000 b1)) cfg)
                               s2 (score (vec (take 8000 (amplify b1 2.0))) cfg)]
                           {:venue (:venue s) :real real
                            :synth (/ (+ s1 s2) 2.0)}))
                       wins (count (filter (fn [pv] (and (pos? (:real pv))
                                                         (>= (:synth pv) 0.5)))
                                           per-venue))]
                   (println (format "  k=%d w=%d z=%.1f -> gana %d/%d venues (%.0fs)"
                                    (:k cfg) (:w cfg) (:threshold cfg)
                                    wins (count series)
                                    (/ (- (System/currentTimeMillis) t0) 1000.0))
                            (flush))
                   (assoc cfg :wins wins :of (count series)))))
              campeon (last (sort-by :wins ranked))]
          (if (and (pos? (:wins campeon))
                   (>= (:wins campeon) (int (Math/ceil (* 0.6 (:of campeon))))))
            (do
              (println (format "CAMPEÓN TAPES: k=%d w=%d z=%.1f -> %d/%d venues"
                               (:k campeon) (:w campeon) (:threshold campeon)
                               (:wins campeon) (:of campeon)))
              (let [prev (try (edn/read-string (slurp "live/champion.edn"))
                              (catch Exception _ {}))
                    tmp "live/champion.edn.tmp"]
                (spit tmp (str (assoc (or prev {})
                                      :scalp {:k (:k campeon) :w (:w campeon)
                                              :threshold (:threshold campeon)
                                              :name "scalp-tapes"
                                              :wins (:wins campeon)
                                              :of (:of campeon)}) "\n"))
                (java.nio.file.Files/move
                 (java.nio.file.Paths/get (str tmp) (into-array String []))
                 (java.nio.file.Paths/get "live/champion.edn" (into-array String []))
                 (into-array java.nio.file.StandardCopyOption
                             [java.nio.file.StandardCopyOption/REPLACE_EXISTING]))))
            (println "TAPES: ninguna config gana en ≥60% de venues —"
                     "no se promueve campeón")))))))

(defn ^:private grid-1m
  "Entrenamiento SWING: familia sobre velas 1m reales (corpus histórico
  de 1 año). Escribe la sección :swing del champion."
  []
  (let [bars (json/parse-string (slurp "data/market/BTCUSDT_1m.json") true)
        closes (->> bars (take-last 50000) parse-closes)
        configs (for [k [40 60 100] w [120 240 480] thr [2.2 3.0]]
                  {:k k :w w :threshold thr :ppy 525600 :tv 0.30})
        scored (doall
                (for [cfg configs]
                  (let [sigfn (sigfn-for (assoc cfg :ppy 525600 :tv 0.30))
                        [w n] (portfolio/fold-verify closes sigfn 525600
                                                     cost 6 15000 false)]
                    (println (format "  swing k=%d w=%d z=%.1f -> %d/%d"
                                     (:k cfg) (:w cfg) (:threshold cfg) w n))
                    (assoc cfg :wins w :folds n))))
        campeon (first (sort-by :wins > scored))]
    (println (format "CAMPEÓN SWING: k=%d w=%d z=%.1f | %d/%d pliegues"
                     (:k campeon) (:w campeon) (:threshold campeon)
                     (:wins campeon) (:folds campeon)))
    (let [prev (try (edn/read-string (slurp "live/champion.edn"))
                    (catch Exception _ {}))
          tmp "live/champion.edn.tmp"]
      ;; escritura ATÓMICA: un cerebro leyendo a mitad de spit lee EDN
      ;; roto y cae a defaults 30 s (fallo F9 del review)
      (spit tmp (str (assoc-in (or prev {}) [:swing]
                               {:k (:k campeon) :w (:w campeon)
                                :threshold (:threshold campeon)
                                :name "swing-grid" :wins (:wins campeon)
                                :folds (:folds campeon)}) "\n"))
      (java.nio.file.Files/move
       (java.nio.file.Paths/get (str tmp) (into-array String []))
       (java.nio.file.Paths/get "live/champion.edn" (into-array String []))
       (into-array java.nio.file.StandardCopyOption
                   [java.nio.file.StandardCopyOption/REPLACE_EXISTING])))
    campeon))

(defn -main [& args]
  (cond
    (= "--tapes" (first args)) (train-tapes)
    (= "--1m" (first args)) (grid-1m)
    (= "--1s" (first args))
    (let [path (or (second args) "data/market/BTCUSDT_1s.json")
          bars (json/parse-string (slurp path) true)
          closes (->> bars parse-closes
                      (drop (max 0 (- (count bars) 200000)))
                      vec)]
      (println "grid scalping 1s sobre" (count closes) "velas reales")
      (grid-1s closes))
    :else (apply main args)))
