(ns llm.tensor
  "Dense float32 primitives on Java arrays.

  Every matrix is a row-major float[] with explicit dimensions. The kernels
  below are deliberately written as tight primitive loops so that HotSpot's
  JIT can keep the working set in registers and auto-vectorize the inner
  loop. No boxing, no seqs, no reflection.

  NOTE: areduce always spans the WHOLE array, so every row-wise reduction
  here is an explicit `loop` bounded by the row width n.")

(set! *warn-on-reflection* true)
(set! *unchecked-math* true)

(defn fzeros
  "Fresh zeroed float array of length n."
  ^floats [^long n]
  (float-array n))

(defn copy-into!
  "dst := src (elementwise, same length). Returns dst."
  [^floats dst ^floats src]
  (System/arraycopy src 0 dst 0 (alength src))
  dst)

(defn add-into!
  "dst += src (elementwise, same length). Returns dst."
  [^floats dst ^floats src]
  (dotimes [i (alength dst)]
    (aset dst i (+ (aget dst i) (aget src i))))
  dst)

(defn scale!
  "a *= s (elementwise). Returns a."
  [^floats a ^double s]
  (dotimes [i (alength a)]
    (aset a i (* (aget a i) s)))
  a)

(defn l2-norm
  "Euclidean norm of the array (double accumulator)."
  ^double [^floats a]
  (Math/sqrt (areduce a i acc 0.0
                      (+ acc (double (* (aget a i) (aget a i)))))))

;; ---------------------------------------------------------------------------
;; Matrix multiply kernels
;;
;; The compiler only allows primitive-hinted args on fns of <= 4 args, so
;; kernels with more take Object dims and rebind them as primitive locals
;; ((let [^long k k] ...)) — the rebind costs one unbox per call, and the
;; hot inner loops then run on pure primitive math.
;; ---------------------------------------------------------------------------

(defn matmul!
  "out[m,n] (+)= a[m,k] · b[k,n].  When acc is false out is overwritten.
  Inner loop is the classic c[i,j] += aik * b[k,j] shape HotSpot vectorizes."
  [^floats out ^floats a ^floats b m k n acc]
  (let [^long m m
        ^long k k
        ^long n n]
    (when-not acc (java.util.Arrays/fill out 0.0))
    (dotimes [i m]
      (let [aoff (* i k)
            coff (* i n)]
        (dotimes [p k]
          (let [aip  (aget a (+ aoff p))
                boff (* p n)]
            (dotimes [j n]
              (aset out (+ coff j)
                    (+ (aget out (+ coff j)) (* aip (aget b (+ boff j))))))))))
    out))

(defn matmul-a-bt!
  "out[m,n] (+)= a[m,k] · bᵀ[n,k]  (b is stored row-major as [n,k])."
  [^floats out ^floats a ^floats b m k n acc]
  (let [^long m m
        ^long k k
        ^long n n]
    (when-not acc (java.util.Arrays/fill out 0.0))
    (dotimes [i m]
      (let [aoff (* i k)]
        (dotimes [j n]
          (let [boff (* j k)
                s (loop [q 0, acc 0.0]
                    (if (< q k)
                      (recur (inc q)
                             (+ acc (* (double (aget a (+ aoff q)))
                                       (double (aget b (+ boff q))))))
                      acc))]
            (aset out (+ (* i n) j) (float s))))))
    out))

(defn matmul-at-b!
  "out[m,n] (+)= aᵀ[k,m] · b[k,n]  (a is stored row-major as [k,m])."
  [^floats out ^floats a ^floats b k m n acc]
  (let [^long k k
        ^long m m
        ^long n n]
    (when-not acc (java.util.Arrays/fill out 0.0))
    (dotimes [q k]
      (let [aoff (* q m)
            boff (* q n)]
        (dotimes [i m]
          (let [aiv (aget a (+ aoff i))
                ioff (* i n)]
            (dotimes [j n]
              (aset out (+ ioff j)
                    (+ (aget out (+ ioff j)) (* aiv (aget b (+ boff j))))))))))
    out))

;; ---------------------------------------------------------------------------
;; Elementwise / rowwise kernels
;; ---------------------------------------------------------------------------

(defn add-bias-row!
  "a[i,j] += bias[j] for every row i. Returns a."
  [^floats a ^floats bias ^long m ^long n]
  (dotimes [i m]
    (let [off (* i n)]
      (dotimes [j n]
        (aset a (+ off j) (+ (aget a (+ off j)) (aget bias j))))))
  a)

(defn col-sum!
  "out[j] += Σ_i a[i,j]. Returns out."
  [^floats out ^floats a ^long m ^long n]
  (dotimes [i m]
    (let [off (* i n)]
      (dotimes [j n]
        (aset out j (+ (aget out j) (aget a (+ off j)))))))
  out)

(defn softmax-rows!
  "Numerically stable in-place row softmax of a[m,n]. Returns a."
  [^floats a ^long m ^long n]
  (dotimes [i m]
    (let [off (* i n)
          vmax (loop [j 1, mv (aget a off)]
                 (if (< j n)
                   (recur (inc j) (max mv (aget a (+ off j))))
                   mv))]
      (dotimes [j n]
        (aset a (+ off j) (float (Math/exp (double (- (aget a (+ off j)) vmax))))))
      (let [zsum (loop [j 0, s 0.0]
                   (if (< j n)
                     (recur (inc j) (+ s (double (aget a (+ off j)))))
                     s))]
        (dotimes [j n]
          (aset a (+ off j) (float (/ (aget a (+ off j)) zsum)))))))
  a)

(defn softmax-back-rows!
  "Given probs p (the row softmax outputs, m×n) and dout holding ∂L/∂y,
  overwrites dout with ∂L/∂z where y = softmax(z):  dout[i,j] = p[i,j] *
  (dout[i,j] − Σ_j' p[i,j']·dout[i,j']).  Rows where p is 0 stay 0, so a
  causally-masked matrix needs no separate masking. Returns dout."
  [^floats dout ^floats probs ^long m ^long n]
  (dotimes [i m]
    (let [off (* i n)
          dot (loop [j 0, s 0.0]
                (if (< j n)
                  (recur (inc j) (+ s (* (double (aget probs (+ off j)))
                                         (double (aget dout (+ off j))))))
                  s))]
      (dotimes [j n]
        (aset dout (+ off j)
              (* (aget probs (+ off j)) (- (aget dout (+ off j)) dot))))))
  dout)

(def ^:private gelu-c1 (float (Math/sqrt (/ 2.0 Math/PI))))
(def ^:private gelu-c2 (float 0.044715))

(defn gelu!
  "out[i] = gelu(in[i]) using the tanh approximation. Returns out."
  [^floats out ^floats in]
  (dotimes [i (alength in)]
    (let [x (aget in i)
          u (* gelu-c1 (+ x (* gelu-c2 x x x)))
          t (Math/tanh (double u))]
      (aset out i (float (* 0.5 x (+ 1.0 t))))))
  out)

(defn gelu-back!
  "dx[i] = dy[i] * gelu'(x[i]);  x is the gelu input cached from forward.
  Returns dx."
  [^floats dx ^floats dy ^floats x]
  (dotimes [i (alength x)]
    (let [xf (aget x i)
          u  (* gelu-c1 (+ xf (* gelu-c2 xf xf xf)))
          t  (Math/tanh (double u))]
      ;; d/dx [0.5x(1+tanh u)] = 0.5(1+t) + 0.5x(1-t^2) * c1(1+3c2x^2)
      (aset dx i (float (* (aget dy i)
                           (+ (* 0.5 (+ 1.0 t))
                              (* 0.5 xf (- 1.0 (* t t))
                                 (+ gelu-c1 (* 3.0 gelu-c1 gelu-c2 xf xf)))))))))
  dx)

(defn layernorm-rows!
  "Row-wise layer norm: out = (in-μ)/σ ⊙ g + b, caching mean and 1/σ per row
  for the backward pass. Returns out."
  [^floats out ^floats in ^floats g ^floats b m n
   ^floats mean-cache ^floats rstd-cache]
  (let [^long m m
        ^long n n]
    (dotimes [i m]
      (let [off (* i n)
            mu  (/ (loop [j 0, s 0.0]
                     (if (< j n)
                       (recur (inc j) (+ s (double (aget in (+ off j)))))
                       s))
                   n)
            varq (/ (loop [j 0, s 0.0]
                      (if (< j n)
                        (let [d (double (- (aget in (+ off j)) mu))]
                          (recur (inc j) (+ s (* d d))))
                        s))
                    n)
            rstd (float (/ 1.0 (Math/sqrt (+ varq 1e-5))))]
        (aset mean-cache i (float mu))
        (aset rstd-cache i rstd)
        (dotimes [j n]
          (aset out (+ off j)
                (+ (* (- (aget in (+ off j)) (float mu)) rstd (aget g j))
                   (aget b j))))))
    out))

(defn layernorm-back!
  "Backward of layernorm-rows! for a stack of m rows of width n.
  dg/db are accumulated into;  din is overwritten. Returns din."
  [^floats din ^floats dg ^floats db
   ^floats dout ^floats in ^floats g m n
   ^floats mean-cache ^floats rstd-cache]
  (let [^long m m
        ^long n n]
    (dotimes [i m]
      (let [off  (* i n)
            mu   (aget mean-cache i)
            rstd (aget rstd-cache i)
            m1   (/ (loop [j 0, s 0.0]
                      (if (< j n)
                        (recur (inc j)
                               (+ s (* (double (aget dout (+ off j)))
                                       (double (aget g j)))))
                        s))
                    n)
            m2   (/ (loop [j 0, s 0.0]
                      (if (< j n)
                        (recur (inc j)
                               (+ s (* (double (aget dout (+ off j)))
                                       (double (aget g j))
                                       (double (- (aget in (+ off j)) mu))
                                       rstd)))
                        s))
                    n)]
        (dotimes [j n]
          (let [xhat  (* (- (aget in (+ off j)) mu) rstd)
                dxhat (* (aget dout (+ off j)) (aget g j))
                di    (* rstd (- dxhat (+ m1 (* xhat m2))))]
            (aset din (+ off j) (float di))
            (aset dg j (+ (aget dg j) (* (aget dout (+ off j)) xhat)))
            (aset db j (+ (aget db j) (aget dout (+ off j))))))))
    din))

;; ---------------------------------------------------------------------------
;; Multi-head reshape helpers
;; ---------------------------------------------------------------------------

(defn split-head!
  "Copy one attention head out of a fused projection matrix:
  src[i*src-stride + col-off + h*dh + j] -> dst[i*dh + j],
  where dh = d/heads. Returns dst."
  [^floats dst ^floats src t d src-stride col-off h heads]
  (let [^long t t
        ^long d d
        ^long src-stride src-stride
        ^long col-off col-off
        ^long h h
        ^long heads heads
        dh (quot d heads)]
    (dotimes [i t]
      (let [soff (+ (* i src-stride) col-off (* h dh))
            doff (* i dh)]
        (dotimes [j dh]
          (aset dst (+ doff j) (aget src (+ soff j))))))
    dst))

(defn merge-head!
  "Accumulate one attention head into a fused matrix (inverse of split-head!):
  dst[i*dst-stride + col-off + h*dh + j] += src[i*dh + j]. Returns dst."
  [^floats dst ^floats src t d dst-stride col-off h heads]
  (let [^long t t
        ^long d d
        ^long dst-stride dst-stride
        ^long col-off col-off
        ^long h h
        ^long heads heads
        dh (quot d heads)]
    (dotimes [i t]
      (let [soff (* i dh)
            doff (+ (* i dst-stride) col-off (* h dh))]
        (dotimes [j dh]
          (aset dst (+ doff j)
                (+ (aget dst (+ doff j)) (aget src (+ soff j)))))))
    dst))

(defn causal-mask!
  "Set a[i,j] = v for j > i in an m×m matrix (v typically -1e30). Returns a."
  [^floats a ^long m ^double v]
  (dotimes [i m]
    (dotimes [j m]
      (when (> j i)
        (aset a (+ (* i m) j) v))))
  a)
