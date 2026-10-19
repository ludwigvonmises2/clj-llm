;; Independent double-precision reference for a 1-layer, 1-head GPT.
;; Compares against llm.nn on IDENTICAL weights: splits forward-vs-backward
;; bugs and names the exact parameter that diverges.
;; Run: clojure -M dev/ref_compare.clj
(require '[llm.model :as model]
         '[llm.nn :as nn]
         '[llm.tensor :as t])

(def cfg {:ctx 3 :d 4 :layers 1 :heads 1 :dff 6 :vocab 5})
(def T 3) (def D 4) (def F 6) (def V 5) (def DH 4)
(def tok [1 3 2])
(def tgt [3 0 2])

(def params (model/init cfg 123))
(defn flat [k] (let [a (get params k)] (vec (map #(aget ^floats a %) (range (alength a))))))
(defn rows [k r c] (mapv (fn [i] (subvec (flat k) (* i c) (* (inc i) c))) (range r)))

(def wte (rows "wte" V D)) (def wpe (rows "wpe" T D))
(def g1 (flat "l0-ln1g")) (def bb1p (flat "l0-ln1b"))
(def Wq (rows "l0-qkvw" D (* 3 D))) (def bq (flat "l0-qkvb"))
(def Wo (rows "l0-ow" D D)) (def bo (flat "l0-ob"))
(def g2 (flat "l0-ln2g")) (def bb2p (flat "l0-ln2b"))
(def W1 (rows "l0-w1" D F)) (def bb1 (flat "l0-b1"))
(def W2 (rows "l0-w2" F D)) (def bb2 (flat "l0-b2"))
(def gf (flat "lnfg")) (def bff (flat "lnfb"))

(defn dotv [a b] (reduce + (map * a b)))
;; proper mat-vec: out[j] = Σ_i v[i]·M[i][j]
(defn mv [M v]
  (let [c (count (first M))]
    (vec (for [j (range c)]
           (reduce + (map (fn [row vi] (* vi (nth row j))) M v))))))
(defn softmaxv [vs]
  (let [mx (apply max vs) es (mapv #(Math/exp (- % mx)) vs) z (reduce + es)]
    (mapv #(/ % z) es)))
(defn ln-fwd [v gg bb]
  (let [mu (/ (reduce + v) (count v))
        varr (/ (reduce + (map #(* (- % mu) (- % mu)) v)) (count v))
        rstd (/ 1.0 (Math/sqrt (+ varr 1e-5)))]
    {:y (mapv (fn [xj gj bj] (+ (* (- xj mu) rstd gj) bj)) v gg bb)
     :mu mu :rstd rstd}))
(def c1 (Math/sqrt (/ 2.0 Math/PI)))
(defn gelu [x] (let [tt (Math/tanh (* c1 (+ x (* 0.044715 x x x))))] (* 0.5 x (+ 1.0 tt))))
(defn gelu-d [x]
  (let [tt (Math/tanh (* c1 (+ x (* 0.044715 x x x))))]
    (+ (* 0.5 (+ 1.0 tt))
       (* 0.5 x (- 1.0 (* tt tt)) (+ c1 (* 3.0 c1 0.044715 x x))))))
(defn ln-back [v cache dout gg]
  (let [mu (:mu cache) rstd (:rstd cache)
        dxhat (mapv * dout gg)
        xhat (mapv (fn [x] (* (- x mu) rstd)) v)
        m1 (/ (reduce + dxhat) (count v))
        m2 (/ (reduce + (map * dxhat xhat)) (count v))]
    {:dx (mapv (fn [dxh xh] (* rstd (- dxh m1 (* xh m2)))) dxhat xhat)
     :dg (mapv * dout xhat)
     :db dout}))

;; ---------------- reference forward ----------------
(def xs (mapv (fn [tt] (mapv + (nth wte (nth tok tt)) (nth wpe tt))) (range T)))
(def l1 (mapv #(ln-fwd % g1 bb1p) xs))
(def n1 (mapv :y l1))
(def qkv (mapv (fn [tt] (mapv + (mv Wq (nth n1 tt)) bq)) (range T)))
(def qs (mapv #(subvec % 0 D) qkv))
(def ks (mapv #(subvec % D (* 2 D)) qkv))
(def vv (mapv #(subvec % (* 2 D)) qkv))
(def scl (/ 1.0 (Math/sqrt (double DH))))
(def sc (vec (for [t1 (range T)]
               (vec (for [t2 (range T)]
                      (if (> t2 t1) -1e30 (* scl (dotv (nth qs t1) (nth ks t2)))))))))
(def ps (mapv softmaxv sc))
(def os (vec (for [t1 (range T)]
               (reduce (fn [acc t2]
                         (mapv (fn [a bv] (+ a (* ((nth ps t1) t2) bv))) acc (nth vv t2)))
                       (vec (repeat D 0.0)) (range T)))))
(def proj (mapv (fn [o] (mapv + (mv Wo o) bo)) os))
(def x1s (mapv (fn [x pp] (mapv + x pp)) xs proj))
(def l2 (mapv #(ln-fwd % g2 bb2p) x1s))
(def n2 (mapv :y l2))
(def h1pre (mapv (fn [tt] (mapv + (mv W1 (nth n2 tt)) bb1)) (range T)))
(def h1s (mapv #(mapv gelu %) h1pre))
(def mlps (mapv (fn [h] (mapv + (mv W2 h) bb2)) h1s))
(def x2s (mapv (fn [x1 mm] (mapv + x1 mm)) x1s mlps))
(def lf (mapv #(ln-fwd % gf bff) x2s))
(def nfs (mapv :y lf))
(def logits (mapv (fn [nf] (mapv #(dotv nf %) wte)) nfs))
(def pf (mapv softmaxv logits))
(def ref-loss (/ (reduce + (map (fn [tt] (- (Math/log (nth (nth pf tt) (nth tgt tt))))) (range T))) T))

;; ---------------- reference backward (grads of (1/T)·ΣCE) ----------------
(def s0 (/ 1.0 T))
(def dlogits (vec (for [tt (range T)]
                    (mapv (fn [v] (* s0 (- (nth (nth pf tt) v) (if (= v (nth tgt tt)) 1.0 0.0))))
                          (range V)))))
(def dnf (vec (for [tt (range T)]
                (reduce (fn [acc v]
                          (mapv (fn [a wv] (+ a (* (nth (nth dlogits tt) v) wv))) acc (nth wte v)))
                        (vec (repeat D 0.0)) (range V)))))
(def lf-b (mapv #(ln-back %2 %3 (nth dnf %1) gf) (range T) x2s lf))
(def dgf (reduce (fn [a r] (mapv + a (:dg r))) (vec (repeat D 0.0)) lf-b))
(def dbf (reduce (fn [a r] (mapv + a (:db r))) (vec (repeat D 0.0)) lf-b))
(def dx2s (mapv :dx lf-b))
;; mlp
(def dw2 (vec (for [i (range F)]
                (vec (for [j (range D)]
                       (reduce + (map (fn [tt] (* (nth (nth h1s tt) i) (nth (nth dx2s tt) j))) (range T))))))))
(def db2 (reduce (fn [a dm] (mapv + a dm)) (vec (repeat D 0.0)) dx2s))
(def W2T (vec (for [j (range D)] (vec (for [i (range F)] (nth (nth W2 i) j))))))
(def dh1s (mapv (fn [dm] (mv W2T dm)) dx2s))
(def dpres (mapv (fn [dh hp] (mapv * dh (mapv gelu-d hp))) dh1s h1pre))
(def dw1 (vec (for [i (range D)]
                (vec (for [j (range F)]
                       (reduce + (map (fn [tt] (* (nth (nth n2 tt) i) (nth (nth dpres tt) j))) (range T))))))))
(def db1 (reduce (fn [a dp] (mapv + a dp)) (vec (repeat F 0.0)) dpres))
(def W1T (vec (for [j (range F)] (vec (for [i (range D)] (nth (nth W1 i) j))))))
(def dn2s (mapv (fn [dp] (mv W1T dp)) dpres))
(def l2-b (mapv #(ln-back %2 %3 (nth dn2s %1) g2) (range T) x1s l2))
(def dg2 (reduce (fn [a r] (mapv + a (:dg r))) (vec (repeat D 0.0)) l2-b))
(def db2p (reduce (fn [a r] (mapv + a (:db r))) (vec (repeat D 0.0)) l2-b))
(def dx1s (mapv (fn [d2 r] (mapv + d2 (:dx r))) dx2s l2-b))
;; attention
(def WoT (vec (for [j (range D)] (vec (for [i (range D)] (nth (nth Wo i) j))))))
(def dos (mapv (fn [dop] (mv WoT dop)) dx1s))
(def dwo (vec (for [i (range D)]
                (vec (for [j (range D)]
                       (reduce + (map (fn [tt] (* (nth (nth os tt) i) (nth (nth dx1s tt) j))) (range T))))))))
(def dbo (reduce (fn [a dp] (mapv + a dp)) (vec (repeat D 0.0)) dx1s))
(def dpr (vec (for [t1 (range T)]
                (vec (for [t2 (range T)]
                       (if (> t2 t1) 0.0 (dotv (nth dos t1) (nth vv t2))))))))
(def dps (mapv (fn [p ds]
                 (let [dd (reduce + (map * p ds))]
                   (mapv (fn [pp dsr] (* pp (- dsr dd))) p ds))) ps dpr))
(def dsc (mapv (fn [dp] (mapv #(* % scl) dp)) dps))
(def dqs (vec (for [t1 (range T)]
                (reduce (fn [acc t2]
                          (mapv (fn [a kk] (+ a (* (nth (nth dsc t1) t2) kk))) acc (nth ks t2)))
                        (vec (repeat D 0.0)) (range T)))))
(def dks (vec (for [t2 (range T)]
                (reduce (fn [acc t1]
                          (mapv (fn [a qq] (+ a (* (nth (nth dsc t1) t2) qq))) acc (nth qs t1)))
                        (vec (repeat D 0.0)) (range T)))))
(def dvs (vec (for [t2 (range T)]
                (vec (for [j (range D)]
                       (reduce + (map (fn [t1] (* ((nth ps t1) t2) (nth (nth dos t1) j)))
                                      (range T))))))))
(def dqkv (mapv (fn [tt] (into (nth dqs tt) (into (nth dks tt) (nth dvs tt)))) (range T)))
(def WqT (vec (for [j (range (* 3 D))] (vec (for [i (range D)] (nth (nth Wq i) j))))))
(def dn1s (mapv (fn [dq] (mv WqT dq)) dqkv))
(def dwq (vec (for [i (range D)]
                (vec (for [j (range (* 3 D))]
                       (reduce + (map (fn [tt] (* (nth (nth n1 tt) i) (nth (nth dqkv tt) j))) (range T))))))))
(def dbq (reduce (fn [a dq] (mapv + a dq)) (vec (repeat (* 3 D) 0.0)) dqkv))
(def l1-b (mapv #(ln-back %2 %3 (nth dn1s %1) g1) (range T) xs l1))
(def dg1 (reduce (fn [a r] (mapv + a (:dg r))) (vec (repeat D 0.0)) l1-b))
(def db1p (reduce (fn [a r] (mapv + a (:db r))) (vec (repeat D 0.0)) l1-b))
(def dx0s (mapv (fn [d1 r] (mapv + d1 (:dx r))) dx1s l1-b))
(def dwpe (vec (for [tt (range T)] (nth dx0s tt))))
(def dwte2
  (vec (for [v (range V)]
         (vec (for [j (range D)]
                (+ (reduce + (map (fn [tt] (* (nth (nth dlogits tt) v) (nth (nth nfs tt) j))) (range T)))
                   (reduce + (map (fn [tt] (if (= (nth tok tt) v) (nth (nth dx0s tt) j) 0.0)) (range T)))))))))
(def ref-grads
  {"wte" dwte2 "wpe" dwpe
   "lnfg" dgf "lnfb" dbf
   "l0-ln1g" dg1 "l0-ln1b" db1p
   "l0-qkvw" dwq "l0-qkvb" dbq
   "l0-ow" dwo "l0-ob" dbo
   "l0-ln2g" dg2 "l0-ln2b" db2p
   "l0-w1" dw1 "l0-b1" db1
   "l0-w2" dw2 "l0-b2" db2})

;; ---------------- compare ----------------
(def tokens (int-array tok))
(def targets (int-array tgt))
(def lg-result (nn/loss-and-grads-1 cfg params tokens targets (/ 1.0 T)))
(def l (first lg-result))
(def g (second lg-result))
(println (format "loss  ref=%.8f  llm=%.8f  diff=%.2e" ref-loss l (Math/abs (- ref-loss l))))
(println "grad diffs (ref double vs llm float, same weights, scale 1/T):")
(doseq [k (sort (keys ref-grads))]
  (let [rg (flatten (get ref-grads k))
        ga ^floats (get g k)]
    (if (not= (count rg) (alength ga))
      (println (format "  %-9s SIZE MISMATCH ref=%d llm=%d" k (count rg) (alength ga)))
      (let [rels (for [i (range (count rg))]
                   (let [r (nth rg i)
                         a (aget ga i)]
                     (/ (Math/abs (- r a)) (max 1e-6 (+ (Math/abs r) (Math/abs a))))))
            rs (sort rels)
            n (count rs)]
        (println (format "  %-9s n=%3d  median-rel=%.4f  max-rel=%.4f"
                         k n (nth rs (quot n 2)) (last rs)))))))

(println "\nside-by-side l0-ob (ref vs llm):")
(let [rg (flatten (get ref-grads "l0-ob"))
      ga (get g "l0-ob")]
  (println "  ref:" (vec (take 4 rg)))
  (println "  llm:" (mapv #(aget ^floats ga %) (range 4)))
  (println "  rel:" (mapv (fn [i] (let [r (nth rg i) a (aget ^floats ga i)]
                                    (/ (Math/abs (- r a)) (max 1e-9 (+ (Math/abs r) (Math/abs a))))))
                          (range 4))))
(println "side-by-side wte:")
(let [rg (flatten (get ref-grads "wte"))
      ga (get g "wte")]
  (println "  ref:" (vec (take 4 rg)))
  (println "  llm:" (mapv #(aget ^floats ga %) (range 4))))
