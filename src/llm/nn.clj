(ns llm.nn
  "GPT-style decoder-only transformer: forward and manual backward for a
  single sample.

  Shapes: T = ctx, d = model width, H = heads, dh = d/H, F = dff, V = vocab.
  All matrices are row-major float[] from llm.tensor; every intermediate the
  backward pass needs is cached by forward-1."
  (:require [llm.tensor :as t]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

;; ---------------------------------------------------------------------------
;; Forward
;; ---------------------------------------------------------------------------

(defn forward-1
  "Forward pass for one sample: `tokens` is an int[T] of vocab ids.
  Returns {:logits float[T*V], :cache cache}."
  [{:keys [ctx d layers heads dff vocab]} params ^ints tokens]
  (let [T (alength tokens)                 ; any length <= ctx is valid
        dh (quot d heads)
        scale (/ 1.0 (Math/sqrt (double dh)))
        ^floats wte (params "wte")
        ^floats wpe (params "wpe")
        ^floats x (t/fzeros (* T d))]
    ;; token + position embeddings
    (dotimes [i T]
      (let [e (* (aget tokens i) d)
            o (* i d)]
        (dotimes [j d]
          (aset x (+ o j) (+ (aget wte (+ e j)) (aget wpe (+ o j)))))))
    (loop [l 0
           x x
           c {:x [] :x1 [] :normed1 [] :normed2 [] :mean1 [] :rstd1 []
              :mean2 [] :rstd2 [] :q [] :k [] :v [] :probs [] :attn []
              :h1pre [] :h1 []}]
      (if (= l layers)
        (let [xf    (t/fzeros (* T d))
              meanf (t/fzeros T)
              rstdf (t/fzeros T)
              logits (t/fzeros (* T vocab))]
          (t/layernorm-rows! xf x (params "lnfg") (params "lnfb") T d meanf rstdf)
          (t/matmul-a-bt! logits xf wte T d vocab false)   ; tied head
          {:logits logits
           :cache (assoc c :xf-in x :xf xf :meanf meanf :rstdf rstdf)})
        (let [pre (str "l" l "-")
              normed1 (t/fzeros (* T d))
              mean1 (t/fzeros T)
              rstd1 (t/fzeros T)]
          (t/layernorm-rows! normed1 x (params (str pre "ln1g"))
                             (params (str pre "ln1b")) T d mean1 rstd1)
          (let [qkv (t/fzeros (* T (* 3 d)))]
            (t/matmul! qkv normed1 (params (str pre "qkvw")) T d (* 3 d) false)
            (t/add-bias-row! qkv (params (str pre "qkvb")) T (* 3 d))
            (let [qh   (mapv (fn [_] (t/fzeros (* T dh))) (range heads))
                  kh   (mapv (fn [_] (t/fzeros (* T dh))) (range heads))
                  vh   (mapv (fn [_] (t/fzeros (* T dh))) (range heads))
                  probs (mapv (fn [_] (t/fzeros (* T T))) (range heads))
                  ohs  (mapv (fn [_] (t/fzeros (* T dh))) (range heads))
                  attn (t/fzeros (* T d))]
              (dotimes [h heads]
                (t/split-head! (nth qh h) qkv T d (* 3 d) 0 h heads)
                (t/split-head! (nth kh h) qkv T d (* 3 d) d h heads)
                (t/split-head! (nth vh h) qkv T d (* 3 d) (* 2 d) h heads)
                (let [sc (nth probs h)]
                  (t/matmul-a-bt! sc (nth qh h) (nth kh h) T dh T false)
                  (t/scale! sc (float scale))
                  (t/causal-mask! sc T -1.0e30)
                  (t/softmax-rows! sc T T)
                  (t/matmul! (nth ohs h) sc (nth vh h) T T dh false)))
              (dotimes [h heads]
                (t/merge-head! attn (nth ohs h) T d d 0 h heads))
              (let [proj (t/fzeros (* T d))]
                (t/matmul! proj attn (params (str pre "ow")) T d d false)
                (t/add-bias-row! proj (params (str pre "ob")) T d)
                (let [x1 (t/add-into! (t/copy-into! (t/fzeros (* T d)) x) proj)
                      normed2 (t/fzeros (* T d))
                      mean2 (t/fzeros T)
                      rstd2 (t/fzeros T)]
                  (t/layernorm-rows! normed2 x1 (params (str pre "ln2g"))
                                     (params (str pre "ln2b")) T d mean2 rstd2)
                  (let [h1pre (t/fzeros (* T dff))]
                    (t/matmul! h1pre normed2 (params (str pre "w1")) T d dff false)
                    (t/add-bias-row! h1pre (params (str pre "b1")) T dff)
                    (let [h1 (t/gelu! (t/fzeros (* T dff)) h1pre)
                          mlp (t/fzeros (* T d))]
                      (t/matmul! mlp h1 (params (str pre "w2")) T dff d false)
                      (t/add-bias-row! mlp (params (str pre "b2")) T d)
                      (recur (inc l)
                             (t/add-into! (t/copy-into! (t/fzeros (* T d)) x1) mlp)
                             (-> c
                                 (update :x conj x)
                                 (update :x1 conj x1)
                                 (update :normed1 conj normed1)
                                 (update :normed2 conj normed2)
                                 (update :mean1 conj mean1)
                                 (update :rstd1 conj rstd1)
                                 (update :mean2 conj mean2)
                                 (update :rstd2 conj rstd2)
                                 (update :q conj qh)
                                 (update :k conj kh)
                                 (update :v conj vh)
                                 (update :probs conj probs)
                                 (update :attn conj attn)
                                 (update :h1pre conj h1pre)
                                 (update :h1 conj h1))))))))))))))

;; ---------------------------------------------------------------------------
;; Backward
;; ---------------------------------------------------------------------------

(defn loss-and-grads-1
  "Forward + softmax cross-entropy + manual backward for one sample.
  `targets` is int[T]; `loss-scale` multiplies the gradient (use
  1/(batch*T)). Returns [mean-token-loss grads]; grads has an entry for
  every param."
  [{:keys [ctx d layers heads dff vocab] :as cfg}
   params tokens targets loss-scale]
  (let [^ints tokens tokens
        ^ints targets targets
        ^double loss-scale loss-scale
        {:keys [logits cache]} (forward-1 cfg params tokens)
        T (alength tokens)                 ; matches tokens/targets length
        dh (quot d heads)
        scale (/ 1.0 (Math/sqrt (double dh)))
        grads (zipmap (keys params)
                      (map (fn [^floats v] (t/fzeros (alength v))) (vals params)))
        wte (params "wte")
        dlogits (t/fzeros (* T vocab))
        prow (t/fzeros vocab)
        loss (volatile! 0.0)]
    ;; softmax + cross-entropy: dlogits = (p - onehot) * loss-scale
    (let [^floats logits logits
          ^floats prow prow
          ^floats dlogits dlogits]
      (dotimes [i T]
        (let [off (* i vocab)
              vmax (loop [j 1, mv (aget logits off)]
                     (if (< j vocab)
                       (recur (inc j) (max mv (aget logits (+ off j))))
                       mv))]
          (dotimes [j vocab]
            (aset prow j (float (Math/exp (double (- (aget logits (+ off j)) vmax))))))
          (let [z (loop [j 0, s 0.0]
                    (if (< j vocab)
                      (recur (inc j) (+ s (double (aget prow j))))
                      s))]
            (dotimes [j vocab]
              (aset prow j (float (/ (aget prow j) z)))))
          (let [tgt (aget targets i)]
            (vswap! loss + (- (Math/log (double (aget prow tgt)))))
            (dotimes [j vocab]
              (aset dlogits (+ off j)
                    (float (* (- (aget prow j) (if (= j tgt) 1.0 0.0)) loss-scale))))))))
    ;; tied head: dWte += dlogitsᵀ @ xf ; dxf = dlogits @ wteᵀ
    (t/matmul-at-b! (grads "wte") dlogits (:xf cache) T vocab d true)
    (let [^floats dxf (t/fzeros (* T d))]
      (t/matmul! dxf dlogits wte T vocab d false)
      (let [^floats dx (t/fzeros (* T d))]
        (t/layernorm-back! dx (grads "lnfg") (grads "lnfb") dxf
                           (:xf-in cache) (params "lnfg")
                           T d (:meanf cache) (:rstdf cache))
        (loop [l (dec layers)
               dx dx]
          (if (neg? l)
            (let [^floats gwte (grads "wte")]
              ;; dx now backs the embedding sum: positional (each position
              ;; used exactly once) + token (scatter-add, tokens repeat)
              (t/add-into! (grads "wpe") dx)
              (dotimes [i T]
                (let [e (* (aget tokens i) d)
                      o (* i d)]
                  (dotimes [j d]
                    (aset gwte (+ e j)
                          (+ (aget gwte (+ e j)) (aget dx (+ o j)))))))
              [(double (/ (double @loss) T)) grads])
            (let [pre    (str "l" l "-")
                  x0     (nth (:x cache) l)
                  x1     (nth (:x1 cache) l)
                  normed1 (nth (:normed1 cache) l)
                  normed2 (nth (:normed2 cache) l)
                  h1     (nth (:h1 cache) l)
                  h1pre  (nth (:h1pre cache) l)
                  attn   (nth (:attn cache) l)
                  qh     (nth (:q cache) l)
                  kh     (nth (:k cache) l)
                  vh     (nth (:v cache) l)
                  probs  (nth (:probs cache) l)
                  G  #(grads (str pre %))
                  Pm #(params (str pre %))
                  ;; MLP branch: residual passes dx straight through
                  dh1 (t/fzeros (* T dff))]
              (t/matmul-at-b! (G "w2") h1 dx T dff d true)
              (t/col-sum! (G "b2") dx T d)
              (t/matmul-a-bt! dh1 dx (Pm "w2") T d dff false)
              (let [dpre (t/gelu-back! (t/fzeros (* T dff)) dh1 h1pre)]
                (t/matmul-at-b! (G "w1") normed2 dpre T d dff true)
                (t/col-sum! (G "b1") dpre T dff)
                (let [dnormed2 (t/fzeros (* T d))
                      dtmp (t/fzeros (* T d))]
                  (t/matmul-a-bt! dnormed2 dpre (Pm "w1") T dff d false)
                  ;; LN2 backward: din = dx + d(normed2 path)
                  (t/layernorm-back! dtmp (G "ln2g") (G "ln2b") dnormed2
                                     x1 (Pm "ln2g") T d
                                     (nth (:mean2 cache) l) (nth (:rstd2 cache) l))
                  (let [dx-mid (t/add-into! (t/copy-into! (t/fzeros (* T d)) dx) dtmp)
                        dattn (t/fzeros (* T d))]
                    ;; attention projection branch
                    (t/matmul-a-bt! dattn dx-mid (Pm "ow") T d d false)
                    (t/matmul-at-b! (G "ow") attn dx-mid T d d true)
                    (t/col-sum! (G "ob") dx-mid T d)
                    (let [dq (mapv (fn [_] (t/fzeros (* T dh))) (range heads))
                          dk (mapv (fn [_] (t/fzeros (* T dh))) (range heads))
                          dv (mapv (fn [_] (t/fzeros (* T dh))) (range heads))]
                      (dotimes [h heads]
                        (let [douth (t/fzeros (* T dh))
                              datt  (t/fzeros (* T T))]
                          (t/split-head! douth dattn T d d 0 h heads)
                          (t/matmul-a-bt! datt douth (nth vh h) T dh T false) ; dprobs
                          (t/matmul-at-b! (nth dv h) (nth probs h) douth T T dh false)
                          (t/softmax-back-rows! datt (nth probs h) T T)
                          (t/scale! datt (float scale)) ; fold 1/√dh back in
                          (t/matmul! (nth dq h) datt (nth kh h) T T dh false)
                          (t/matmul-at-b! (nth dk h) datt (nth qh h) T T dh false)))
                      (let [dqkv (t/fzeros (* T (* 3 d)))]
                        (dotimes [h heads]
                          (t/merge-head! dqkv (nth dq h) T d (* 3 d) 0 h heads)
                          (t/merge-head! dqkv (nth dk h) T d (* 3 d) d h heads)
                          (t/merge-head! dqkv (nth dv h) T d (* 3 d) (* 2 d) h heads))
                        (t/matmul-at-b! (G "qkvw") normed1 dqkv T d (* 3 d) true)
                        (t/col-sum! (G "qkvb") dqkv T (* 3 d))
                        (let [dnormed1 (t/fzeros (* T d))
                              dtmp2 (t/fzeros (* T d))]
                          (t/matmul-a-bt! dnormed1 dqkv (Pm "qkvw") T (* 3 d) d false)
                          (t/layernorm-back! dtmp2 (G "ln1g") (G "ln1b") dnormed1
                                             x0 (Pm "ln1g") T d
                                             (nth (:mean1 cache) l)
                                             (nth (:rstd1 cache) l))
                          (recur (dec l)
                                 (t/add-into! dx-mid dtmp2)))))))))))))))

;; ---------------------------------------------------------------------------
;; Incremental inference with a KV cache
;; ---------------------------------------------------------------------------

(defn init-kv-cache
  "Empty KV cache sized for `ctx` tokens: per layer, per head, one k- and
  one v-array of [ctx*dh]."
  [{:keys [ctx d layers heads]}]
  (let [dh (quot d heads)]
    (vec (for [_ (range layers)]
           {:k (vec (for [_ (range heads)] (t/fzeros (* ctx dh))))
            :v (vec (for [_ (range heads)] (t/fzeros (* ctx dh))))}))))

(defn prime-cache
  "Full forward over `tokens` (int[]), copying the K/V of every layer into
  the KV cache. Returns {:logits float[V] :len T} — ONLY the logits of the
  last prompt position."
  [cfg params kv-cache ^ints tokens]
  (let [{:keys [logits cache]} (forward-1 cfg params tokens)
        {:keys [d layers heads vocab]} cfg
        dh (quot d heads)
        len (alength tokens)
        ^floats last-row (t/fzeros vocab)]
    (dotimes [l layers]
      (dotimes [h heads]
        (System/arraycopy (nth (nth (:k cache) l) h) 0
                          (nth (get-in kv-cache [l :k]) h) 0 (* len dh))
        (System/arraycopy (nth (nth (:v cache) l) h) 0
                          (nth (get-in kv-cache [l :v]) h) 0 (* len dh))))
    (System/arraycopy logits (* (dec len) vocab) last-row 0 vocab)
    {:logits last-row :len len}))

(defn forward-step
  "One incremental pass: `token` (int) attends to the cached K/V of
  positions 0..len-1 and appends its own K/V at position `len` (in-place).
  Returns float[V] logits for the new position. Cost is O(d²+ctx·d) per
  token, not the O(T·d²) of a full re-forward."
  [{:keys [d layers heads dff vocab]} params token kv-cache len]
  (let [^long len len
        dh (quot d heads)
        scale (/ 1.0 (Math/sqrt (double dh)))
        ^floats wte (params "wte")
        ^floats wpe (params "wpe")
        token (long token)
        ^floats x (t/fzeros d)]
    (dotimes [j d]
      (aset x j (+ (aget wte (+ (* token d) j))
                   (aget wpe (+ (* len d) j)))))
    (loop [l 0
           ^floats x x]
      (if (= l layers)
        (let [^floats xf (t/fzeros d)
              ^floats logits (t/fzeros vocab)
              meanf (t/fzeros 1)
              rstdf (t/fzeros 1)]
          (t/layernorm-rows! xf x (params "lnfg") (params "lnfb") 1 d meanf rstdf)
          (t/matmul-a-bt! logits xf wte 1 d vocab false)
          logits)
        (let [pre (str "l" l "-")
              layer (nth kv-cache l)
              ks (:k layer)
              vs (:v layer)
              ^floats normed1 (t/fzeros d)
              mean1 (t/fzeros 1)
              rstd1 (t/fzeros 1)
              ^floats qkv (t/fzeros (* 3 d))
              ^floats attn (t/fzeros d)
              ^floats proj (t/fzeros d)
              ^floats x1 (t/fzeros d)
              ^floats normed2 (t/fzeros d)
              mean2 (t/fzeros 1)
              rstd2 (t/fzeros 1)
              ^floats h1pre (t/fzeros dff)
              ^floats h1 (t/fzeros dff)
              ^floats mlp (t/fzeros d)
              ^floats x2 (t/fzeros d)]
          (t/layernorm-rows! normed1 x (params (str pre "ln1g"))
                             (params (str pre "ln1b")) 1 d mean1 rstd1)
          (t/matmul! qkv normed1 (params (str pre "qkvw")) 1 d (* 3 d) false)
          (t/add-bias-row! qkv (params (str pre "qkvb")) 1 (* 3 d))
          (dotimes [h heads]
            (let [^floats kc (nth ks h)
                  ^floats vc (nth vs h)
                  ^floats qh (t/fzeros dh)
                  coff (* h dh)
                  base (* len dh)]
              ;; append this token's k/v at cache position len
              (dotimes [j dh]
                (aset kc (+ base j) (aget qkv (+ d coff j)))
                (aset vc (+ base j) (aget qkv (+ (* 2 d) coff j))))
              ;; q of the new position
              (dotimes [j dh]
                (aset qh j (aget qkv (+ coff j))))
              ;; scores against all cached positions (no mask needed: the
              ;; cache only holds positions <= len)
              (let [^floats sc (t/fzeros (inc len))]
                (dotimes [j (inc len)]
                  (let [base2 (* j dh)
                        s (loop [q2 0 acc 0.0]
                            (if (< q2 dh)
                              (recur (inc q2)
                                     (+ acc (* (double (aget qh q2))
                                               (double (aget kc (+ base2 q2))))))
                              acc))]
                    (aset sc j (float (* s scale)))))
                (t/softmax-rows! sc 1 (inc len))
                (dotimes [j dh] (aset attn (+ coff j) 0.0))
                (dotimes [j2 (inc len)]
                  (let [p (aget sc j2)
                        base3 (* j2 dh)]
                    (dotimes [j dh]
                      (aset attn (+ coff j)
                            (+ (aget attn (+ coff j))
                               (* p (aget vc (+ base3 j)))))))))))
          (t/matmul! proj attn (params (str pre "ow")) 1 d d false)
          (t/add-bias-row! proj (params (str pre "ob")) 1 d)
          (t/layernorm-rows! normed2 (t/add-into! (t/copy-into! x1 x) proj)
                             (params (str pre "ln2g")) (params (str pre "ln2b"))
                             1 d mean2 rstd2)
          (t/matmul! h1pre normed2 (params (str pre "w1")) 1 d dff false)
          (t/add-bias-row! h1pre (params (str pre "b1")) 1 dff)
          (t/gelu! h1 h1pre)
          (t/matmul! mlp h1 (params (str pre "w2")) 1 dff d false)
          (t/add-bias-row! mlp (params (str pre "b2")) 1 d)
          (recur (inc l) (t/add-into! (t/copy-into! x2 x1) mlp)))))))
