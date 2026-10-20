;; BÚSQUEDA PARALELA MÁXIMA del campeón: pmap sobre 16 cores,
;; espacio amplio (k×w×z×lado), corpus 1s real completo de Binance.
;; Puerta honesta: SOLO se promueve N/N pliegues.
(require '[trade.signals :as s]
         '[trade.portfolio :as p]
         '[cheshire.core :as j])

(defn closes-1s []
  (->> (j/parse-string (slurp "data/market/BTCUSDT_1s.json") true)
       (mapv (fn [b] (Double/parseDouble (str (nth b 4)))))
       (take-last 160000)
       vec))

(def configs
  (for [k [10 15 20 30 40 60 90]
        w [90 120 180 240 360 540 720]
        thr [0.8 1.0 1.2 1.5 1.8 2.2 2.8]
        side [:long-dip :both]]
    {:k k :w w :threshold thr :side side}))

(defn score [closes {:keys [k w threshold side]}]
  (try
    (let [sigfn (fn [cl]
                  (s/mr-zscore {:closes cl :k k :w w :threshold threshold
                                :side side :target-vol 0.30 :max-lev 2.0
                                :ppy 31536000}))
          [wins folds] (p/fold-verify closes sigfn 31536000 0.0005 6 20000 false)]
      (when (pos? folds) {:wins wins :folds folds
                          :rate (double (/ wins folds))}))
    (catch Exception _ nil)))

(defn -main []
  (println "MAX SEARCH:" (count configs) "configs × 6 pliegues, 16 cores")
  (flush)
  (let [closes (closes-1s)
        t0 (System/currentTimeMillis)
        results (->> configs
                     (pmap (fn [c] (let [r (score closes c)] (when r (assoc c :wins (:wins r) :folds (:folds r) :rate (:rate r))))))
                     (doall)
                     (remove nil?))
        perfect (filter (fn [r] (and (= (:wins r) (:folds r)) (>= (:wins r) 6))) results)
        top (sort-by :rate > results)]
    (println "evaluadas:" (count results) "en" (quot (- (System/currentTimeMillis) t0) 1000) "s")
    (println "== N/N (6/6) PERFECTAS:" (count perfect))
    (doseq [r (take 12 perfect)]
      (println (format "  PERFECTA k=%d w=%d z=%.1f %s -> %d/%d"
                       (:k r) (:w r) (:threshold r) (name (:side r)) (:wins r) (:folds r))))
    (println "== TOP 10 por tasa:")
    (doseq [r (take 10 top)]
      (println (format "  %2d/%d k=%3d w=%3d z=%.1f %s" (:wins r) (:folds r)
                       (:k r) (:w r) (:threshold r) (name (:side r)))))
    (spit "live/max_search_result.edn"
          (pr-str {:at (System/currentTimeMillis)
                   :evaluated (count results)
                   :perfect (count perfect)
                   :best perfect}))))
(-main)
