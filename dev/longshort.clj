;; Long/short gating: does :both beat :long-dip in fold-verify on real
;; data? Both timeframes, the three profile families. Zero look-ahead:
;; same portfolio/fold-verify the production signal must pass.
(require '[trade.signals :as s]
         '[trade.portfolio :as p]
         '[cheshire.core :as j]
         '[clojure.java.io :as io])

(defn closes-any
  "1m files are maps with :c; 1s/1h files are Binance kline arrays (close idx 4)."
  [path n]
  (let [bars (j/parse-string (slurp path) true)]
    (->> bars
         (mapv (fn [b] (Double/parseDouble (str (if (map? b) (:c b) (nth b 4))))))
         (take-last n)
         vec)))

(def cost 0.0005)
(doseq [[tf path ppy n-folds cap]
        [["1m" "data/market/BTCUSDT_1m.json" 525600 6 15000]
         ["1s" "data/market/BTCUSDT_1s.json" 31536000 4 60000]]
        [nm k w thr]
        [["medio" 40 240 2.2] ["scalping" 20 120 1.5] ["conservador" 60 360 3.0]]]
  (let [closes (closes-any path cap)
        sigfn (fn [side]
                (fn [cl]
                  (s/mr-zscore {:closes cl :k k :w w :threshold thr :side side
                                :target-vol 0.30 :max-lev 2.0 :ppy ppy})))]
    (doseq [side [:long-dip :both]]
      (let [[w n] (p/fold-verify closes (sigfn side) ppy cost n-folds 10000 false)]
        (println (format "%s %-11s %-9s %d/%d pliegues" tf nm (name side) w n))
        (flush)))))
