(ns llm.model
  "GPT model assembly: configuration, GPT-2-style parameter initialization,
  and parameter accounting.")

(defn default-config
  "A configuration that trains a coherent char-level model on CPU in a few
  minutes (≈818k parameters)."
  []
  {:ctx 128 :d 128 :layers 4 :heads 4 :dff 512 :vocab 65})

(defn n-params
  [{:keys [ctx d layers dff vocab]}]
  (+ (* vocab d)                              ; wte
     (* ctx d)                                ; wpe
     (* layers (+ (* 2 d)                     ; ln1
                  (* d (* 3 d)) (* 3 d)       ; qkvw qkvb
                  (* d d) d                   ; ow ob
                  (* 2 d)                     ; ln2
                  (* d dff) dff               ; w1 b1
                  (* dff d) d))               ; w2 b2
     (* 2 d)))                                ; lnf

(defn init
  "GPT-2-style init: N(0, 0.02) everywhere, residual projections scaled by
  1/√(2·layers), biases 0, layernorm gains 1. Returns {name float[]}."
  [{:keys [ctx d layers heads dff vocab]} ^long seed]
  (let [_ (assert (zero? (mod d heads))
                  (str "d must be divisible by heads, got d=" d " heads=" heads))
        rng (java.util.Random. seed)
        std-res (double (/ 0.02 (Math/sqrt (* 2.0 (double layers)))))
        gauss (fn [^long n ^double s]
                (let [a (float-array n)]
                  (dotimes [i n]
                    (aset a i (float (* s (.nextGaussian rng)))))
                  a))
        zeros (fn [^long n] (float-array n))
        ones  (fn [^long n]
                (let [a (float-array n)]
                  (dotimes [i n] (aset a i 1.0))
                  a))]
    (into {}
          (concat
           [["wte" (gauss (* vocab d) 0.02)]
            ["wpe" (gauss (* ctx d) 0.02)]
            ["lnfg" (ones d)]
            ["lnfb" (zeros d)]]
           (mapcat (fn [l]
                     (let [p #(str "l" l "-" %)]
                       [[(p "ln1g") (ones d)]
                        [(p "ln1b") (zeros d)]
                        [(p "qkvw") (gauss (* d (* 3 d)) 0.02)]
                        [(p "qkvb") (zeros (* 3 d))]
                        [(p "ow") (gauss (* d d) std-res)]
                        [(p "ob") (zeros d)]
                        [(p "ln2g") (ones d)]
                        [(p "ln2b") (zeros d)]
                        [(p "w1") (gauss (* d dff) 0.02)]
                        [(p "b1") (zeros dff)]
                        [(p "w2") (gauss (* dff d) std-res)]
                        [(p "b2") (zeros d)]]))
                   (range layers))))))

(defn continue-params
  "Seed parameters for a new config/vocabulary from a previous checkpoint —
  the incremental-improvement primitive.

  Every tensor whose size matches is copied wholesale. The token embedding
  (and its tied output head) is remapped BY CHARACTER: rows for characters
  the old model knew are preserved, characters new to the vocabulary get
  fresh GPT-2 init. Requires the same d between old and new."
  [{:keys [old-config old-params old-idx->ch]}
   {:keys [d vocab] :as new-cfg}
   new-idx->ch
   ^long seed]
  (let [_     (assert (= d (:d old-config))
                      (str "cannot continue across widths: old d=" (:d old-config)
                           " new d=" d))
        fresh (init new-cfg seed)
        old-vocab->i (zipmap old-idx->ch (range))
        ^floats old-wte (old-params "wte")
        ^floats new-wte (fresh "wte")]
    (doseq [[name ^floats p] fresh]
      (when-some [^floats old (get old-params name)]
        (when (= (alength old) (alength p))
          (System/arraycopy old 0 p 0 (alength old)))))
    ;; embedding rows, remapped by character (wte is [V,d]; the head is tied)
    (dotimes [v (long vocab)]
      (when-some [oi (get old-vocab->i (nth new-idx->ch v nil))]
        (System/arraycopy old-wte (long (* oi d)) new-wte (long (* v d)) (long d))))
    fresh))
