(ns llm.sample
  "Autoregressive generation: temperature + top-k sampling over a sliding
  context window (full re-forward per token), with a per-token streaming
  hook and optional early stop on a literal string.")

(set! *warn-on-reflection* true)

(require '[llm.nn :as nn]
         '[llm.tokenizer :as tok])

(defn generate
  "Generate n-tokens after `prompt`. idx->ch is the checkpoint's char
  vector. Options:
    :temperature  (0 means greedy argmax decoding)
    :top-k        candidate cutoff (0 disables)
    :seed         RNG seed
    :on-token     fn of the sampled id, called after each step (streaming)
    :stop-text    when the output ends with this literal, stop early"
  [cfg params idx->ch prompt n-tokens
   {:keys [temperature top-k seed on-token stop-text]
    :or {temperature 0.8 top-k 40 seed 7}}]
  (let [vocab {:idx->ch idx->ch
               :ch->idx (zipmap idx->ch (range (count idx->ch)))}
        V    (count idx->ch)
        rng  (java.util.Random. (long seed))
        ids  (java.util.ArrayList. ^java.util.Collection (vec (tok/encode vocab (or prompt ""))))
        sb   (StringBuilder. ^String (str (or prompt "")))
        _    (when (.isEmpty ids) (.add ids (int 0)))
        temp (double temperature)]
    (loop [i 0]
      (when (< i (long n-tokens))
        (let [lo     (max 0 (- (.size ids) (:ctx cfg)))
              window (.subList ids lo (.size ids))
              in     (int-array window)
              {:keys [^floats logits]} (nn/forward-1 cfg params in)
              off    (* (dec (.size window)) V)
              cand   (->> (range V)
                          (map (fn [j] [j (aget logits (+ off j))]))
                          (sort-by second >))
              top    (if (pos? (long top-k)) (take top-k cand) cand)
              sel    (if (< temp 0.001)
                       ;; greedy decoding: argmax
                       (ffirst top)
                       (let [t    (max 0.05 temp)
                             mx   (second (first top))
                             ws   (mapv (fn [[j z]] [j (Math/exp (/ (double (- z mx)) t))])
                                        top)
                             zsum (reduce + 0.0 (map second ws))
                             r    (* zsum (.nextDouble rng))]
                         (loop [acc 0.0, items (seq ws), fallback (ffirst ws)]
                           (if (nil? items)
                             fallback
                             (let [[j w] (first items)]
                               (if (>= (+ acc w) r)
                                 j
                                 (recur (+ acc w) (next items) j)))))))]
          (when on-token (on-token sel))
          (.add ids (int sel))
          (.append sb ^char (nth idx->ch sel \?))
          (when (or (not stop-text)
                    (not (.endsWith (str sb) ^String stop-text)))
            (recur (inc i))))))
    (str sb)))

(defn generate-cached
  "Same contract as generate, but with an incremental KV cache: the prompt
  is forwarded once, then every new token attends to the cached K/V and
  only its own position is computed. O(ctx) work per token instead of
  O(ctx²) total — much faster for long generations."
  [cfg params idx->ch prompt n-tokens
   {:keys [temperature top-k seed on-token stop-text]
    :or {temperature 0.8 top-k 40 seed 7}}]
  (let [vocab {:idx->ch idx->ch
               :ch->idx (zipmap idx->ch (range (count idx->ch)))}
        V    (count idx->ch)
        rng  (java.util.Random. (long seed))
        ids  (java.util.ArrayList. ^java.util.Collection (vec (tok/encode vocab (or prompt ""))))
        sb   (StringBuilder. ^String (str (or prompt "")))
        _    (when (.isEmpty ids) (.add ids (int 0)))
        temp (double temperature)
        kv   (nn/init-kv-cache cfg)
        {:keys [logits len]} (nn/prime-cache cfg params kv (int-array ids))]
    (loop [i 0
           len (long len)
           ^floats logits logits]
      (when (< i (long n-tokens))
        (let [;; cache nearly full? re-prime over the last ctx-1 tokens so
              ;; forward-step still has room for the new position
              win      (when (>= len (dec (:ctx cfg)))
                         (int-array (.subList ids
                                              (max 0 (- (.size ids) (dec (:ctx cfg))))
                                              (.size ids))))
              reprimed (when win (nn/prime-cache cfg params kv win))
              len2     (if win (long (:len reprimed)) len)
              ^floats logits2 (if win (:logits reprimed) logits)
              len      len2
              logits   logits2
              cand (->> (range V)
                        (map (fn [j] [j (aget logits j)]))
                        (sort-by second >))
              top  (if (pos? (long top-k)) (take top-k cand) cand)
              sel  (if (< temp 0.001)
                     (ffirst top)
                     (let [t    (max 0.05 temp)
                           mx   (second (first top))
                           ws   (mapv (fn [[j z]] [j (Math/exp (/ (double (- z mx)) t))])
                                      top)
                           zsum (reduce + 0.0 (map second ws))
                           r    (* zsum (.nextDouble rng))]
                       (loop [acc 0.0, items (seq ws), fallback (ffirst ws)]
                         (if (nil? items)
                           fallback
                           (let [[j w] (first items)]
                             (if (>= (+ acc w) r)
                               j
                               (recur (+ acc w) (next items) j)))))))]
          (when on-token (on-token sel))
          (.add ids (int sel))
          (.append sb ^char (nth idx->ch sel \?))
          (if (and stop-text (.endsWith (str sb) ^String stop-text))
            nil
            (recur (inc i) (inc len)
                   (nn/forward-step cfg params sel kv len))))))
    (str sb)))
