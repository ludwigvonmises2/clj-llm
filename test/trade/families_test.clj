(ns trade.families-test
  "Every family obeys the signal contract: causal, aligned, bounded."
  (:require [clojure.test :refer [deftest is testing]]
            [trade.families :as fam]))

(def ^:private cs
  (vec (for [i (range 3000)]
         (+ 100.0 (* 5.0 (Math/sin (/ i 50.0))) (* 0.01 i) (* 0.3 (Math/sin (* i 1.7)))))))

(def ^:private cfgs
  {:tsmom {:lookback 60} :donchian {:entry 100 :exit 50}
   :zmom {:k 20 :threshold 1.5} :mr-trend {:k 20 :threshold 1.5 :trend 500}})

(def ^:private base {:w 120 :target-vol 0.3 :max-lev 2.0 :ppy 525600 :side :both})

(deftest families-are-causal
  (doseq [[f o] cfgs
          cut [500 1500 2500]]
    (testing (str f " cut " cut)
      (let [g (fam/families f)
            a (g (merge base o {:closes cs}))
            b (g (merge base o {:closes (into (subvec cs 0 cut) (repeat (- 3000 cut) 1.0))}))]
        (is (= (subvec a 0 cut) (subvec b 0 cut)) "changing the future changes no past target")))))

(deftest families-are-aligned-bounded-and-trade
  (doseq [[f o] cfgs]
    (let [xs ((fam/families f) (merge base o {:closes cs}))]
      (is (= (count cs) (count xs)) (str f " aligned"))
      (is (every? #(<= (Math/abs (double %)) 2.0) xs) (str f " capped at max-lev"))
      (is (some #(not= 0.0 %) xs) (str f " actually trades")))))

(deftest long-only-never-shorts
  (doseq [f [:tsmom :donchian :zmom :mr-trend]]
    (is (every? #(>= (double %) 0.0)
                ((fam/families f) (merge base (cfgs f) {:closes cs :side :long}))))))

(deftest vol-scale-matches-brute-force
  (let [c (double-array cs)
        s (fam/vol-scale c 50 0.3 525600 2.0)
        i 1234
        r (for [j (range (- i 49) (inc i))] (- (/ (aget c j) (aget c (dec j))) 1.0))
        m (/ (reduce + r) 50.0)
        sd (Math/sqrt (/ (reduce + (map #(* (- % m) (- % m)) r)) 50.0))]
    (is (< (Math/abs (- (aget s i) (min (/ (/ 0.3 (Math/sqrt 525600)) sd) 2.0))) 1e-9))))
