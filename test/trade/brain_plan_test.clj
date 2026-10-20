(ns trade.brain-plan-test
  "The brain's decision core (`plan`) and the learner's promotion/write
  contracts, tested without network or disk side effects."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [trade.live-clojure :as brain]
            [trade.learn-hot :as hot]))

(def ^:private spec {:pp 31536000 :capital-usd 10000.0})

(defn- wave [base n]
  (vec (for [i (range n)] (* base (+ 1.0 (* 0.0004 (Math/sin (* i 0.7))))))))

(defn- slide
  "gradual dip on the last 15 bars: a clean z < -threshold entry"
  [cs]
  (let [n (count cs)]
    (vec (map-indexed (fn [i x] (if (>= i (- n 15))
                                  (* x (Math/exp (* -0.0005 (- i (- n 16)))))
                                  x))
                      cs))))

(def ^:private base {:k 20 :w 120 :threshold 1.5 :side :long-dip :spec spec
                     :since-last-ms 1e9 :ignited? true})

(defn- plan-on [cs & {:as over}]
  (brain/plan (merge base {:cs cs :px (peek cs) :pos-u 0.0} over)))

(def ^:private dip (slide (wave 80000.0 240)))
(def ^:private entry (plan-on dip))

(deftest plan-warms-up-before-w-closes
  (is (= :warming (:action (plan-on (subvec dip 0 119)))))
  (is (not= :warming (:action (plan-on (subvec dip 0 120))))))

(deftest plan-flat-market-holds
  (let [r (plan-on (wave 80000.0 240))]
    (is (= :hold (:action r)))
    (is (zero? (:target r)))))

(deftest plan-enters-on-dip-sized-by-capital
  (is (= :order (:action entry)))
  (is (= "buy" (:side entry)))
  (is (< (:z entry) -1.5))
  (testing "notional = weight × capital (the weight is NOT a coin count)"
    (is (< (Math/abs (- (* (:qty entry) (peek dip)) (* (:target entry) 10000.0))) 1e-6))))

(deftest plan-is-price-scale-invariant
  (testing "the same chart on a $1.3 coin buys the same notional as on BTC"
    (let [cheap (mapv #(/ % 61538.0) dip)
          r (plan-on cheap)]
      (is (< (Math/abs (- (:target r) (:target entry))) 1e-9))
      (is (< (Math/abs (- (* (:qty r) (peek cheap)) (* (:qty entry) (peek dip)))) 1e-6)))))

(deftest plan-never-sends-dust
  (testing "already at target: the units<->weight round trip residue must not trade"
    (is (= :hold (:action (plan-on dip :pos-u (:newpos-u entry))))))
  (testing "inside the no-trade band, measured from the HELD weight"
    (let [held (* 1.05 (:newpos-u entry))]
      (is (= :hold (:action (plan-on dip :pos-u held)))))))

(deftest plan-band-does-not-dump-small-positions
  (testing "R10: a held weight <= 0.10 whose target moved < band is kept"
    (let [w-held (+ (:target entry) 0.05)
          pos-u (brain/weight->units w-held 10000.0 (peek dip))]
      (is (= :hold (:action (plan-on dip :pos-u pos-u)))))))

(deftest plan-exits-when-dip-reverts
  (let [up (conj dip (* 1.01 (peek dip)))
        r (plan-on up :pos-u (:newpos-u entry))]
    (is (= :order (:action r)))
    (is (= "sell" (:side r)))
    (is (zero? (:target r)))
    (testing "exits are never spread-gated (only buys that add risk)"
      (is (= :order (:action (plan-on up :pos-u (:newpos-u entry)
                                      :bid 100.0 :ask 100.5)))))))

(deftest plan-guards
  (testing "throttle"
    (is (= :throttle (:action (plan-on dip :since-last-ms 500)))))
  (testing "spread gate blocks entries above 8 bps, passes tight books"
    (is (= :gate (:action (plan-on dip :bid 100.0 :ask 100.2))))
    (is (= :order (:action (plan-on dip :bid 100.0 :ask 100.01)))))
  (testing "unknown spread does not gate (documented behaviour)"
    (is (= :order (:action (plan-on dip :bid nil :ask nil)))))
  (testing "ignition only for :ignition venues, flat and not yet ignited"
    (is (= :ignite (:action (plan-on dip :ignited? false
                                     :spec (assoc spec :ignition true)))))
    (is (= :order (:action (plan-on dip :ignited? false)))))
  (testing "poisoned price never trades"
    (doseq [bad [0.0 -1.0 Double/NaN Double/POSITIVE_INFINITY]]
      (is (= :hold (:action (plan-on dip :px bad :pos-u 1.0)))))))

(deftest plan-spot-is-long-only
  (let [pump (conj (wave 80000.0 240) (* 1.02 80000.0))
        both (assoc base :side :both)]
    (is (>= (:target (brain/plan (merge both {:cs pump :px (peek pump) :pos-u 0.0
                                              :spec (assoc spec :long-only true)})))
            0.0))))

(deftest plan-is-causal
  (testing "appending a future bar never changes the decision already taken"
    (let [fut (conj dip 1.0e9)]
      (is (= (:target entry) (:target (plan-on (pop fut)))))
      (is (= (:qty entry) (:qty (plan-on (pop fut))))))))

(deftest trim-window-bounds-memory
  (let [v (reduce #(brain/trim-window %1 %2 240) [] (range 10000))]
    (is (= 240 (count v)))
    (is (= (range 9760 10000) v))
    (is (vector? v))))

(deftest valid-px-rejects-poison
  (is (brain/valid-px? 1.5))
  (is (not-any? brain/valid-px? [0 -1 nil "1" Double/NaN Double/POSITIVE_INFINITY])))

;; ---------------- autoaprendizaje ----------------

(deftest tapes-gate-is-honest
  (is (false? (hot/tapes-promote? 0 8)) "zero wins never promotes")
  (is (false? (hot/tapes-promote? 4 8)) "50 % < 60 %")
  (is (true? (hot/tapes-promote? 5 8)) "ceil(0.6*8)=5")
  (is (true? (hot/tapes-promote? 3 5)))
  (is (false? (hot/tapes-promote? 0 0)) "no venues, no champion"))

(deftest champion-write-preserves-other-sections
  (let [f (java.io.File/createTempFile "champion" ".edn")
        path (.getPath f)]
    (try
      (spit path (pr-str {:swing {:k 100 :w 120 :threshold 2.2}}))
      (hot/write-champion-section! path :scalp {:k 20 :w 240 :threshold 2.0})
      (hot/write-champion-section! path :profile {:k 1 :w 2 :threshold 3.0})
      (let [m (edn/read-string (slurp path))]
        (is (= {:k 100 :w 120 :threshold 2.2} (:swing m)) "swing survives")
        (is (= {:k 20 :w 240 :threshold 2.0} (:scalp m)) "scalp written")
        (is (contains? m :profile)))
      (testing "a corrupt file is replaced, never crashes the learner"
        (spit path "{:swing {:k ")
        (hot/write-champion-section! path :scalp {:k 1 :w 2 :threshold 3.0})
        (is (= {:scalp {:k 1 :w 2 :threshold 3.0}} (edn/read-string (slurp path)))))
      (is (not (.exists (java.io.File. (str path ".tmp")))) "no tmp left behind")
      (finally (.delete f)))))

;; ---------------- reconcile / idempotencia / arranque ----------------

(deftest reconcile-never-overwrites-in-flight-orders
  (testing "R4: read started >= settle-ms after the last order -> apply"
    (is (true? (brain/reconcile-apply? 10000 (- 10000 brain/settle-ms 1) 1.0 1.0))))
  (testing "order sent within settle-ms before the read -> skip"
    (is (false? (brain/reconcile-apply? 10000 9000 1.0 1.0))))
  (testing "order sent DURING the read -> skip"
    (is (false? (brain/reconcile-apply? 10000 10500 1.0 1.0))))
  (testing "position moved while the read was in flight -> skip"
    (is (false? (brain/reconcile-apply? 10000 0 1.0 2.0)))))

(deftest unknown-outcome-stops-deciding
  (is (= :await (:action (plan-on dip :awaiting? true))))
  (is (= :order (:action (plan-on dip :awaiting? false)))))

(deftest client-order-ids-are-unique
  (let [ids (repeatedly 1000 #(brain/new-coid "okx-v"))]
    (is (= 1000 (count (set ids))))))

(deftest spread-gate-is-symmetric
  (let [up (vec (map-indexed (fn [i x] (if (>= i 225)
                                         (* x (Math/exp (* 0.0005 (- i 224))))
                                         x))
                             (wave 80000.0 240)))
        on (fn [& {:as o}] (brain/plan (merge base {:cs up :px (peek up) :side :both} o)))
        short-entry (on :pos-u 0.0)]
    (is (= "sell" (:side short-entry)))
    (is (neg? (:target short-entry)))
    (testing "R14: a SHORT entry adds risk and is gated on a wide book"
      (is (= :gate (:action (on :pos-u 0.0 :bid 100.0 :ask 100.5)))))
    (testing "covering that short is an exit: never gated"
      (let [down (conj up (* 0.99 (peek up)))]
        (is (= :order (:action (brain/plan (merge base {:cs down :px (peek down) :side :both
                                                        :pos-u (:newpos-u short-entry)
                                                        :bid 100.0 :ask 100.5})))))))))

(deftest stale-quotes-are-ignored
  (let [st {:bid (atom 100.0) :ask (atom 100.5)
            :quote-ts (atom (- (System/currentTimeMillis) 60000))}]
    (is (nil? (brain/fresh-quote st :bid)) "60 s old quote = unknown")
    (reset! (:quote-ts st) (System/currentTimeMillis))
    (is (= 100.0 (brain/fresh-quote st :bid)))))

(deftest boot-window-comes-from-own-tape
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "tape" (make-array java.nio.file.attribute.FileAttribute 0)))
        write! (fn [day tag recs]
                 (let [bb (doto (java.nio.ByteBuffer/allocate (* 12 (count recs)))
                            (.order java.nio.ByteOrder/LITTLE_ENDIAN))]
                   (doseq [[sec px] recs] (.putInt bb (int sec)) (.putDouble bb (double px)))
                   (java.nio.file.Files/write
                    (.toPath (java.io.File. dir (str day "_" tag ".prints")))
                    (.array bb)
                    (make-array java.nio.file.OpenOption 0))))]
    (try
      (write! "20261019" "okx:btc-usdt-swap" (for [i (range 500)] [i 1.0]))
      (write! "20261020" "okx:btc-usdt-swap" (for [i (range 500)] [(+ 1000 i) (+ 100.0 i)]))
      (write! "20261020" "okx:eth-usdt-swap" (for [i (range 500)] [i 7.0]))
      (let [cs (brain/tape-closes (.getPath dir) "okx:btc-usdt-swap" 240)]
        (is (= 240 (count cs)))
        (is (every? #(>= % 100.0) cs) "newest day, own tag only (rule 4)")
        (is (apply < cs) "chronological"))
      (is (= [] (brain/tape-closes (.getPath dir) "aster:solusdt" 240)) "no tape -> warm live")
      (finally (doseq [^java.io.File f (.listFiles dir)] (.delete f)) (.delete dir)))))
