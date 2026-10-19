;; Final regime policy evaluation against the acceptance criteria:
;;   PASS per scenario type iff  Sharpe(policy) > Sharpe(B&H)
;;                          AND  maxDD(policy)   < maxDD(B&H)
;; Run: clojure -M dev/regime_eval.clj
(require '[trade.regime :as reg]
         '[trade.backtest :as backtest]
         '[trade.metrics :as metrics])

(let [{:keys [cfg params]} (reg/train-classifier! 250)]
  (println "\nacceptance: Sharpe(policy) > Sharpe(B&H) AND maxDD(policy) < maxDD(B&H)")
  (println (format "  %-8s | %-22s | %-22s | veredicto"
                   "tipo" "Sharpe policy (b&h)" "maxDD policy (b&h)"))
  (let [verdicts (volatile! [])]
    (doseq [kind reg/regimes]
      (let [rows (for [i (range 30)]
                   (let [rets (vec (reg/gen-scenario kind (+ 200000 i)))
                         closes (reduce (fn [acc r] (conj acc (* (peek acc) (+ 1.0 r)))) [100.0] rets)
                         bins (reg/bin-seq-of rets)
                         pol (reg/policy-exposures {:cfg cfg :params params} rets)
                         sm (fn [e]
                              (metrics/summarize
                               (assoc (backtest/run {:closes closes :sigs e :cost-rate 0.0015})
                                      :ppy 365)))]
                     {:ps (:sharpe (sm pol))
                      :bs (:sharpe (sm (vec (repeat (count rets) 1.0))))
                      :pd (:max-drawdown (sm pol))
                      :bd (:max-drawdown (sm (vec (repeat (count rets) 1.0))))}))
            med (fn [k] (let [xs (sort (map k rows))] (nth xs (quot (count xs) 2))))
            mps (med :ps) mbs (med :bs) mpd (med :pd) mbd (med :bd)
            pass? (and (> mps mbs) (< mpd mbd))]
        (vswap! verdicts conj pass?)
        (println (format "  %-8s | %+.2f (%+.2f) %s | %.1f%% (%.1f%%) %s | %s"
                         (name kind) mps mbs (if (> mps mbs) "✓" "✗")
                         (* 100 mpd) (* 100 mbd) (if (< mpd mbd) "✓" "✗")
                         (if pass? "GANA" "PIERDE")))))
    (println (format "\nRESULTADO: %d/%4 escenarios GANAN" (count (filter true? @verdicts))))
    (if (every? true? @verdicts)
      (println "✓ OBJETIVO CUMPLIDO: Sharpe > B&H y maxDD < B&H en todos los escenarios")
      (println "✗ objetivo no alcanzado todavía"))))