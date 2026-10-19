(ns trade.core
  "Backtest CLI: strategy vs buy-and-hold over real market data, with
  costs, vol-targeted sizing and professional metrics.

  Run: clojure -M:backtest [--fast N] [--slow N] [--target-vol F]
                           [--cost-bps N] [--assets A,B,C]"

  (:require [clojure.string :as str]
            [trade.data :as data]
            [trade.signals :as signals]
            [trade.backtest :as backtest]
            [trade.metrics :as metrics]))

(set! *warn-on-reflection* true)

(def ^:private default-assets ["BTCUSDT" "ETHUSDT" "SOLUSDT"])

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

(defn- num-opt [opts k default]
  (if-some [v (get opts k)]
    (or (try (parse-long v) (catch Exception _ nil))
        (try (parse-double v) (catch Exception _ nil))
        default)
    default))

(defn- print-line
  [{:keys [final-equity cagr sharpe sortino ann-vol max-drawdown hit-rate
           profit-factor turnover-yr days]}]
  (println (format "  equity %.3f | CAGR %+.1f%% | Sharpe %.2f | Sortino %.2f | vol %.1f%% | maxDD %.1f%% | hit %.0f%% | PF %.2f | turn %.1fx/yr | %d días"
                   final-equity (* 100 cagr) sharpe sortino (* 100 ann-vol)
                   (* 100 max-drawdown) (* 100 hit-rate) profit-factor turnover-yr days)))

(defn -main
  [& args]
  (let [opts      (parse-opts args)
        assets    (str/split (or (get opts :assets) (str/join "," default-assets)) #",")
        ppy       (long (num-opt opts :ppy
                                 (case (get opts :interval "1d")
                                   "1d" 365 "1h" 8760 "1m" 525600 31536000)))
        cfg       {:fast       (long (num-opt opts :fast 20))
                   :slow       (long (num-opt opts :slow 60))
                   :target-vol (double (num-opt opts :target-vol 0.20))
                   :vol-window 30
                   :max-lev    2.0
                   :ppy        ppy
                   :cost-rate  (/ (double (num-opt opts :cost-bps 15)) 10000.0)}
        port-rets (atom [])]
    (println)
    (println "trade.backtest — SMA crossover + vol targeting | fast" (:fast cfg)
             "slow" (:slow cfg) "| target-vol" (:target-vol cfg)
             "| interval" (get opts :interval "1d")
             "| costs" (format "%.0f bps" (* 10000 (:cost-rate cfg))))
    (println (str "asset        | strategy (net)"))
    (doseq [a assets]
      (let [bars  (trade.data/load-binance-json
                   (str "data/market/" a "_" (get opts :interval "1d") ".json"))
            closes (trade.data/closes bars)
            sigs  (trade.signals/sma-crossover
                   (assoc cfg :closes closes))
            bt    (trade.backtest/run {:closes closes :sigs sigs
                                       :cost-rate (:cost-rate cfg)})
            s     (trade.metrics/summarize (assoc bt :ppy ppy))
            bh    (trade.backtest/run {:closes closes
                                       :sigs   (trade.signals/buy-and-hold closes)
                                       :cost-rate 0.0})
            sbh   (trade.metrics/summarize (assoc bh :ppy ppy))
            rets  (trade.metrics/net-returns (:curve bt))]
        (swap! port-rets (fn [acc] (if (empty? acc)
                                     rets
                                     (mapv + acc rets))))
        (println (format "%-12s | strategy:" a))
        (print-line s)
        (println "             | buy&hold (sin costes):")
        (print-line sbh)))
    (let [n (count assets)
          p-rets (mapv #(/ % n) @port-rets)
          curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] p-rets)
          s (trade.metrics/summarize {:curve curve :final-equity (peek curve)
                                      :total-turnover 0.0 :profit-factor 0.0 :hit-rate 0.0})]
      (println "──────────")
      (println "PORTFOLIO (equiponderado diario, sin costes de rebalanceo):")
      (print-line s))))
