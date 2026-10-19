(ns trade.core-test
  "Core invariants: SMA correctness, causal signals (a signal at bar i
  must not change when FUTURE bars change), backtester cost/turnover
  accounting, fold-verify contract, and the mr-zscore log-return
  prefix (the bug class this suite exists to catch)."
  (:require [clojure.test :refer [deftest is testing]]
            [trade.data :as data]
            [trade.signals :as signals]
            [trade.backtest :as backtest]
            [trade.portfolio :as portfolio]
            [trade.live :as trade.live]
            [trade.learn :as trade.learn]))

(defn- sine-series
  "Deterministic test series: geometric walk with a sine trend."
  [n]
  (->> (range n)
       (map (fn [i]
              (let [trend (* 0.0005 i)
                    wave  (* 0.03 (Math/sin (/ i 20.0)))]
                (* 100.0 (Math/exp (+ trend wave))))))
       vec))

(deftest sma-matches-brute-force
  (let [c    (sine-series 200)
        fast (data/sma c 20)
        brute (fn [i] (/ (reduce + (subvec c (inc (- i 20)) (inc i))) 20.0))]
    (doseq [i (range 19 200)]
      (is (< (Math/abs (- (nth fast i) (brute i))) 1e-9))
      (is (nil? (nth fast (min i 18)))))))

(defn- prefix-causal?
  "True when changing bars AFTER index k never changes the signal at k."
  [sigfn k]
  (let [c    (sine-series 400)
        c2   (assoc (vec c) 390 (* 3.0 (nth c 390)))
        s1   (sigfn c)
        s2   (sigfn c2)]
    (= (subvec s1 0 (inc k)) (subvec s2 0 (inc k)))))

(deftest signals-are-causal
  (doseq [[nm sigfn] [["sma-crossover" #(signals/sma-crossover
                                             {:closes % :fast 10 :slow 30
                                              :target-vol 0.2 :vol-window 30
                                              :max-lev 2.0 :ppy 365})]
                       ["mr-zscore" #(signals/mr-zscore
                                      {:closes % :k 20 :w 60 :threshold 2.0
                                       :side :long-dip :target-vol 0.2
                                       :max-lev 2.0 :ppy 365})]
                       ["triple-trend" #(signals/triple-trend
                                          {:closes % :f1 10 :s1 30 :mid 50
                                           :target-vol 0.2 :vol-window 30
                                           :max-lev 2.0 :ppy 365})]]]
    (testing (str nm " is causal")
      (is (prefix-causal? sigfn 200) (str nm " changed its past when the future changed")))))

(deftest mr-zscore-enters-only-on-dips
  (let [c    (sine-series 500)
        sigs (signals/mr-zscore {:closes c :k 30 :w 90 :threshold 2.0
                                 :side :long-dip :target-vol 0.2
                                 :max-lev 2.0 :ppy 365})]
    (is (every? #(>= % 0.0) sigs) "long-dip must never go short")))

(deftest backtester-applies-signal-to-next-bar
  (let [c    [100.0 110.0]
        ;; signal decided at bar 0 (1.0) must earn bar 1's return (+10%)
        bt   (backtest/run {:closes c :sigs [1.0 1.0] :cost-rate 0.0})]
    (is (< (Math/abs (- (:final-equity bt) 1.10)) 1e-9))))

(deftest backtester-charges-costs-on-turnover
  (let [c  [100.0 100.0]
        bt (backtest/run {:closes c :sigs [1.0 1.0] :cost-rate 0.01})]
    (is (< (- (:final-equity bt) 0.99) 1e-9))
    (is (< (Math/abs (- (:total-turnover bt) 1.0)) 1e-9)
        "entering costs |pos - 0| = 1 unit of turnover")
    (is (= 1.0 (first (:curve bt))))))

(deftest fold-verify-contract
  (let [c (sine-series 600)
        [w n] (portfolio/fold-verify c
                                     (fn [x] (signals/buy-and-hold x))
                                     365 0.0 4 100 false)]
    (is (= n 4) "folds count")
    (is (>= w 0) "wins never negative")))

(deftest fold-verify-bh-scores-zero-vs-itself
  (let [c (sine-series 600)
        [w _n] (portfolio/fold-verify c
                                      (fn [x] (vec (concat [0.0] (repeat (dec (count x)) 1.0))))
                                      365 0.0 4 100 false)]
    (is (zero? w) "B&H vs B&H must tie, never win")))

(deftest bar-builder-folds-ticks
  (let [ticks [{:p 100.0 :t 1000 :q 1.0} {:p 105.0 :t 1999 :q 2.0}
               {:p  98.0 :t 1400 :q 0.5} {:p 101.0 :t 2100 :q 1.0}]
        bars  (trade.live/ticks->bars ticks 1000)]
    (is (= 2 (count bars)) "two completed 1s windows")
    (let [b (first bars)]
      (is (= 1000 (:ts b)) "window start")
      (is (= 100.0 (:o b)) (= 105.0 (:c b)))
      (is (= 98.0 (:l b)) (= 105.0 (:h b)))
      (is (< (Math/abs (- (:v b) 3.5)) 1e-9)))))

(deftest live-parity-with-backtest
  "THE honesty contract: the live rolling engine must decide exactly
  what the causal backtest signal decides, once the window is full."
  (let [c  (sine-series 1200)
        sigfn (fn [x] (signals/mr-zscore {:closes x :k 60 :w 360 :threshold 3.0
                                          :side :long-dip :target-vol 0.30
                                          :max-lev 2.0 :ppy 525600}))
        live (trade.live/live-sigs c 720 sigfn)
        ref  (sigfn c)]
    (is (= (subvec live 719) (subvec ref 719))
        "live decisions diverge from the backtest signal")))

(deftest band-executor-holds-through-noise
  (let [targets [0.0 0.4 0.0 -0.3 1.0 1.2]
        pos     (trade.live/band-executor targets 0.5)]
    (is (= [0.0 0.0 0.0 0.0 1.0 1.0] pos)
        "sub-band changes never reach the exchange")))

(deftest learn-journal-and-promotion
  (let [j (trade.learn/new-journal)]
    (trade.learn/record-decision! j 1 0.5 0.001)
    (trade.learn/record-decision! j 2 -0.3 -0.002)
    (trade.learn/record-decision! j 3 0.2 0.003)
    (let [s (trade.learn/decision-stats j 8760)]
      (is (= 3 (:bars s)))
      (is (< (Math/abs (- (:hit-rate s) (/ 2.0 3))) 1e-9))
      (is (pos? (:sharpe s))))
    (is (nil? (trade.learn/realized-slippage j)) "no fills yet -> nil")
    (trade.learn/record-fill! j 1 :buy 100.0 100.05 1.0)
    (trade.learn/record-fill! j 2 :sell 100.0 99.90 1.0)
    (let [sl (trade.learn/realized-slippage j)]
      (is (some? sl))
      (is (< (Math/abs (- sl 7.5)) 0.01) "buy +5bps, sell +10bps -> mean 7.5bps"))))

(deftest promote-requires-dominant-evidence
  (is (true? (trade.learn/promote? 4 5)) "5>4 promotes")
  (is (false? (trade.learn/promote? 5 5)) "tie keeps champion")
  (is (false? (trade.learn/promote? 5 4))))
