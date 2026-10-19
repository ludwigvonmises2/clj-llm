(ns trade.backtest
  "Event-driven backtester.

  No look-ahead by construction: the exposure vector sigs has sigs[i]
  computed from data 0..i, and it earns the return of bar i+1 (close to
  close). Costs are charged on turnover |Δexposure| at each rebalance —
  fee + slippage per unit of exposure change. Equity compounds net.")

(require '[trade.data :as data])

(set! *warn-on-reflection* true)

(defn run
  "Returns {:curve [equity per bar] :final-equity :total-turnover
  :profit-factor :hit-rate}."
  [{:keys [closes sigs cost-rate]}]
  (let [n    (count closes)
        rets (trade.data/simple-returns closes)]
    (loop [i            1
           equity       1.0
           turnover     0.0
           curve        (transient [1.0])
           gross-w      0.0
           gross-l      0.0
           wins         0
           losses       0
           prev-pos     0.0]
      (if (>= i n)
        {:final-equity   equity
         :curve          (persistent! curve)
         :total-turnover turnover
         :profit-factor  (if (zero? gross-l)
                           Double/POSITIVE_INFINITY
                           (/ gross-w gross-l))
         :hit-rate       (if (zero? (+ wins losses))
                           0.0
                           (/ wins (double (+ wins losses))))}
        (let [pos     (double (nth sigs (dec i)))   ; decided at close i-1
              r       (* pos (double (nth rets i))) ; bar i, close to close
              tau     (Math/abs (- pos prev-pos))
              cost    (* tau cost-rate)
              rnet    (- r cost)
              eq2     (* equity (+ 1.0 rnet))]
          (recur (inc i)
                 eq2
                 (+ turnover tau)
                 (conj! curve eq2)
                 (+ gross-w (max rnet 0.0))
                 (+ gross-l (max (- rnet) 0.0))
                 (if (pos? rnet) (inc wins) wins)
                 (if (neg? rnet) (inc losses) losses)
                 pos))))))
