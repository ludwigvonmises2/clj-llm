(ns llm.tokenizer
  "Character-level tokenizer: the vocabulary is the sorted set of distinct
   characters found in the training corpus.")

(defn build-vocab
  "Returns {:idx->ch [char] :ch->idx {char id} :vocab-size n}."
  [^String text]
  (let [chars (vec (sort (distinct text)))
        ch->idx (into {} (map-indexed (fn [i c] [c i]) chars))]
    {:idx->ch    chars
     :ch->idx    ch->idx
     :vocab-size (count chars)}))

(defn encode
  "Text -> int[] of vocab ids. Unknown characters map to id 0."
  [vocab ^String text]
  (let [ch->idx (:ch->idx vocab)]
    (int-array (map #(get ch->idx % 0) text))))

(defn decode
  "int[]/seq of ids -> text."
  [vocab ids]
  (let [idx->ch (:idx->ch vocab)]
    (apply str (map #(nth idx->ch % \?) ids))))
