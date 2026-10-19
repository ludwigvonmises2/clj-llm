;; Walk-forward fold verification: the strategy must beat B&H in EVERY
;; fold (Sharpe AND maxDD) using only past data at each decision point.
;; This is the rigorous meaning of "gana siempre" over Binance data.
;; Run: clojure -M dev/verify_folds.clj [asset]
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(def asset (or (first *command-line-args*) "BTCUSDT"))
(def cost 0.0015)
(def fold-days 100)

(let [closes (data/closes (data/load-binance-json (str "data/market/" asset "_1d.json")))
      n (count closes)
      folds (min 9 (quot n fold-days))
      rets (data/simple-returns closes)
      tdv (/ 0.20 (Math/sqrt 365.0))
      ;; fold i covers days [i*folds*? ...]: split the post-warmup series
      warm 60
      usable (- n warm)
      step (quot usable folds)
      verdicts
      (for [f (range folds)
            :let [t0 (+ warm (* f step))
                  t1 (min n (+ t0 step))
                  ;; the strategy at day d uses closes[0..d] only — for fold
                  ;; verification we run the full-series exposure but the
                  ;; exposure at day d was decided with data <= d-1 (the
                  ;; engine's own lag), so no fold sees the future.
                  c-sub (subvec closes 0 t1)
                  sigs (signals/sma-crossover {:closes c-sub :fast 20 :slow 60
                                               :target-vol 0.20 :vol-window 30
                                               :max-lev 2.0 :ppy 365})
                  st (metrics/summarize-pro
                      (assoc (backtest/run {:closes c-sub :sigs sigs :cost-rate cost})
                             :ppy 365))
                  ;; B&H over the SAME fold window (from t0)
                  bh-rets (subvec rets t0 t1)
                  bh-curve (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] bh-rets)
                  bh (metrics/summarize-pro {:curve bh-curve :final-equity (peek bh-curve)
                                             :total-turnover 0.0 :profit-factor 0.0
                                             :hit-rate 0.0 :ppy 365})
                  st-f (let [bt (backtest/run {:closes c-sub :sigs sigs :cost-rate cost})
                             fold-rets (subvec (metrics/net-returns (:curve bt)) (dec t0) (dec t1))
                             fc (reduce (fn [c r] (conj c (* (peek c) (+ 1.0 r)))) [1.0] fold-rets)]
                         (metrics/summarize-pro {:curve fc :final-equity (peek fc)
                                                 :total-turnover 0.0 :profit-factor 0.0
                                                 :hit-rate 0.0 :ppy 365}))]]
        {:fold (inc f) :s-sharpe (:sharpe st-f) :b-sharpe (:sharpe bh)
         :s-dd (:max-drawdown st-f) :b-dd (:max-drawdown bh)
         :win? (and (> (:sharpe st-f) (:sharpe bh)) (< (:max-drawdown st-f) (:max-drawdown bh)))})]
  (println (str "\n" asset " — verificación walk-forward (" folds " pliegues de " step
                " días, señal 20/60 vol-target, costes 15bps)"))
  (doseq [v verdicts]
    (println (format "  pliegue %d | Sharpe est. %+.2f vs B&H %+.2f %s | maxDD %.1f%% vs %.1f%% %s → %s"
                     (:fold v) (:s-sharpe v) (:b-sharpe v) (if (> (:s-sharpe v) (:b-sharpe v)) "✓" "✗")
                     (* 100 (:s-dd v)) (* 100 (:b-dd v)) (if (< (:s-dd v) (:b-dd v)) "✓" "✗")
                     (if (:win? v) "GANA" "pierde"))))
  (let [wins (count (filter :win? verdicts))]
    (println (format "\nRESULTADO: %d/%d pliegues GANAN a buy&hold (Sharpe+maxDD)" wins (count verdicts)))
    (if (= wins (count verdicts))
      (println "✓ GANA SIEMPRE en esta historia de Binance")
      (println (format "✗ gana en %d de %d — no es 'siempre' todavía" wins (count verdicts))))))