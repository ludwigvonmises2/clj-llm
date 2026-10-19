(ns trade.data
  "OHLCV loading and bar-level indicators for the backtester.

  Bars are maps {:ts :o :h :l :c :v} ordered by time. Every function here
  is pure; the backtester enforces no-look-ahead by construction (signals
  at bar i only ever see bars 0..i).")

(set! *warn-on-reflection* true)

(defn load-binance-json
  "Parse a Binance klines JSON file (an array of arrays; the numeric
  fields arrive as strings, which is valid EDN) into a vector of bars.
  The last kline of a live request may be still open — drop it."
  [path]
  (let [raw   (slurp path)
        rows  (read-string raw)
        bars  (into []
                    (map (fn [r]
                           {:ts (nth r 0)
                            :o  (Double/parseDouble (nth r 1))
                            :h  (Double/parseDouble (nth r 2))
                            :l  (Double/parseDouble (nth r 3))
                            :c  (Double/parseDouble (nth r 4))
                            :v  (Double/parseDouble (nth r 5))}))
                    rows)]
    (subvec (vec (sort-by :ts bars)) 0 (dec (count bars)))))

(defn closes
  "Close prices as a primitive-friendly float vector."
  [bars]
  (mapv (fn [b] (double (:c b))) bars))

(defn sma
  "Simple moving average of width n; nil until the first full window."
  [closes n]
  (let [prefix (reduce (fn [acc c] (conj acc (+ (peek acc) c))) [0.0] closes)]
    (mapv (fn [i]
            (when (>= i (dec n))
              (/ (- (nth prefix (inc i)) (nth prefix (inc (- i n)))) (double n))))
          (range (count closes)))))

(defn simple-returns
  "r[i] = c[i]/c[i-1] − 1; index 0 is 0.0 so rets aligns with closes."
  [closes]
  (into [0.0]
        (for [i (range 1 (count closes))]
          (- (/ (double (nth closes i)) (nth closes (dec i))) 1.0))))

(defn realized-vol
  "Population std of the last n simple returns up to and including i;
  nil before the first full window."
  [rets ^long i ^long n]
  (when (>= i n)
    (let [w  (subvec rets (inc (- i n)) (inc i))
          m  (/ (reduce + w) (double (count w)))
          vv (/ (reduce + (map #(let [d (- % m)] (* d d)) w))
                (dec (count w)))]
      (Math/sqrt vv))))

(defn load-alpaca-json
  "Parse Alpaca v2 bars JSON into bars. Field order inside each bar
  object may vary, so fields are extracted individually."
  [path]
  (let [json (slurp path)]
    (->> (re-seq #"\{[^{}]*\}" json)
         (keep (fn [blk]
                 (let [f  (fn [kk] (second (re-find (re-pattern (str "\"" kk "\":([-\\d.eE]+)")) blk)))
                       ts (second (re-find #"\"t\":\"([^\"]+)\"" blk))]
                   (when (and ts (f "o") (f "h") (f "l") (f "c"))
                     {:ts ts
                      :o  (Double/parseDouble (f "o"))
                      :h  (Double/parseDouble (f "h"))
                      :l  (Double/parseDouble (f "l"))
                      :c  (Double/parseDouble (f "c"))}))))
         vec)))
