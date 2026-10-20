(ns trade.brain-plan-test
  "The brain's decision core (`plan`) and the learner's promotion/write
  contracts, tested without network or disk side effects."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.edn :as edn]
            [trade.live-clojure :as brain]
            [trade.signals :as signals]
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
  (testing "R10: held weight 0.09 on a flat market (want 0): |0-0.09| <= band -> hold
            (the old [pos want] sentinel measured from 0 and sold it)"
    (let [flat (wave 80000.0 240)
          pos-u (brain/weight->units 0.09 10000.0 (peek flat))]
      (is (= :hold (:action (plan-on flat :pos-u pos-u))))))
  (testing "beyond the band it does trade"
    (let [flat (wave 80000.0 240)
          pos-u (brain/weight->units 0.15 10000.0 (peek flat))]
      (is (= :order (:action (plan-on flat :pos-u pos-u)))))))

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

(def ^:private pump
  "gradual rise on the last 15 bars: a clean short entry under :both"
  (vec (map-indexed (fn [i x] (if (>= i 225) (* x (Math/exp (* 0.0005 (- i 224)))) x))
                    (wave 80000.0 240))))

(deftest plan-spot-is-long-only
  (let [r (fn [spec] (brain/plan (merge base {:cs pump :px (peek pump) :pos-u 0.0
                                              :side :both :spec spec})))]
    (is (neg? (:target (r spec))) "precondition: without the clamp it shorts")
    (is (zero? (:target (r (assoc spec :long-only true)))) "spot clamps to flat")
    (is (= :hold (:action (r (assoc spec :long-only true)))))))

(deftest plan-follows-the-production-signal
  (testing "rule 1: the target IS mr-zscore's last exposure (band from flat)"
    (let [want (peek (signals/mr-zscore {:closes dip :k 20 :w 120 :threshold 1.5
                                         :side :long-dip :target-vol 0.30
                                         :max-lev 2.0 :ppy 31536000}))]
      (is (pos? want))
      (is (== want (:target entry))))))

(deftest trim-window-bounds-memory
  (let [v (reduce #(brain/trim-window %1 %2 240) [] (range 10000))]
    (is (= 240 (count v)))
    (is (= (range 9760 10000) v))
    (is (vector? v))))

(deftest valid-px-rejects-poison
  (is (brain/valid-px? 1.5))
  (is (not-any? brain/valid-px? [0 -1 nil "1" Double/NaN Double/POSITIVE_INFINITY])))

;; ---------------- autoaprendizaje ----------------

(deftest promotion-gates-are-nn
  (testing "rule 3, literally: N/N or nothing, on every learner path"
    (is (false? (hot/tapes-promote? 0 8)))
    (is (false? (hot/tapes-promote? 7 8)) "7/8 venues is not N/N")
    (is (true? (hot/tapes-promote? 8 8)))
    (is (false? (hot/tapes-promote? 0 0)) "no venues, no champion")
    (is (false? (hot/nn? 5 6)))
    (is (true? (hot/nn? 6 6)))
    (is (false? (hot/nn? 0 0)))))

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
                    (make-array java.nio.file.OpenOption 0))))
        ;; liquid venue: 50 prints per second
        busy (fn [s0 secs px0] (for [i (range secs) _ (range 50)] [(+ s0 i) (+ px0 i)]))]
    (try
      (write! "20261019" "okx:btc-usdt-swap" (busy 0 600 1000.0))
      (write! "20261020" "okx:btc-usdt-swap" (busy 100000 500 5000.0))
      (write! "20261020" "okx:eth-usdt-swap" (busy 0 900 7.0))
      (let [cs (brain/tape-closes (.getPath dir) "okx:btc-usdt-swap" 960)]
        (is (= 960 (count cs)) "50 prints/s still yield n distinct closes")
        (is (= 499 (count (filter #(>= % 5000.0) cs))) "today's closed seconds, all of them")
        (is (not-any? #(< 6.9 % 1000.0) cs) "own tag only (rule 4)")
        (is (apply < (take-last 499 cs)) "chronological"))
      (is (= [] (brain/tape-closes (.getPath dir) "aster:solusdt" 240)) "no tape -> warm live")
      (testing "a stale tape (recorder dead) is not today's market"
        (doseq [^java.io.File f (.listFiles dir)]
          (.setLastModified f (- (System/currentTimeMillis) 60000)))
        (is (= [] (brain/tape-closes (.getPath dir) "okx:btc-usdt-swap" 240))))
      (finally (doseq [^java.io.File f (.listFiles dir)] (.delete f)) (.delete dir)))))

;; ---------------- ronda 2 ----------------

(defn- st-at [pos last-order]
  {:pos (atom pos) :last-order (atom last-order) :await (atom true)})

(deftest reconcile-venue-clears-await-only-on-truth
  (let [t0 (+ 100000 brain/settle-ms)]
    (testing "venue agrees -> :same, await cleared"
      (let [st (st-at 1.0 0)]
        (is (= :same (brain/reconcile-venue! st t0 1.0 1.0)))
        (is (false? @(:await st)))))
    (testing "venue differs -> adopted BY VALUE even with a re-boxed equal double"
      (let [st (st-at (Double. 1.0) 0)]
        (is (= :adopted (brain/reconcile-venue! st t0 (Double. 1.0) 2.0)))
        (is (== 2.0 @(:pos st)))
        (is (false? @(:await st)))))
    (testing "a bar moved pos during the read -> not adopted, await KEPT"
      (let [st (st-at 1.5 0)]
        (is (#{:skipped :raced} (brain/reconcile-venue! st t0 1.0 2.0)))
        (is (== 1.5 @(:pos st)))
        (is (true? @(:await st)))))
    (testing "read may predate an in-flight order -> :skipped, await KEPT"
      (let [st (st-at 1.0 (- t0 100))]
        (is (= :skipped (brain/reconcile-venue! st t0 1.0 2.0)))
        (is (true? @(:await st)))))))

(deftest settle-covers-the-order-timeout
  (is (> brain/settle-ms 5000) "longer than the brain's 5 s HTTP timeout"))

(deftest repeated-rejects-back-off
  (is (= brain/min-order-gap-ms (brain/reject-gap-ms 0)))
  (is (= brain/min-order-gap-ms (brain/reject-gap-ms 2)))
  (is (< (brain/reject-gap-ms 3) (brain/reject-gap-ms 5)))
  (is (= 300000 (brain/reject-gap-ms 100)) "capped at 5 min")
  (is (= :throttle (:action (plan-on dip :since-last-ms 5000
                                     :gap-ms (brain/reject-gap-ms 5))))))

(deftest dead-quote-feed-gates-risk
  (testing "a venue WITH quotes whose feed died blocks entries, not exits"
    (is (= :gate (:action (plan-on dip :quote-stale? true))))
    (let [up (conj dip (* 1.01 (peek dip)))]
      (is (= :order (:action (plan-on up :pos-u (:newpos-u entry) :quote-stale? true)))))))

(deftest quote-staleness
  (let [now (System/currentTimeMillis)]
    (is (not (brain/quote-stale? {:quote-ts (atom 0)})) "never had quotes: unknown, not stale")
    (is (brain/quote-stale? {:quote-ts (atom (- now 60000))}))
    (is (not (brain/quote-stale? {:quote-ts (atom now)})))))

(deftest window-cap-survives-hot-reload
  (is (= 960 (brain/window-cap 120)))
  (is (>= (brain/window-cap 480) 960))
  (is (= 2000 (brain/window-cap 1000))))
