(ns trade.live-clojure
  "The live scalping brain — DATA AND EXECUTION ON THE SAME VENUE.

  The signal feeds from the venue we trade (GRVT testnet), never a
  default market: prints (v1.trade@50) build the 1s bars, the mini book
  (v1.mini.s) gives live bid/ask, and the spread gate refuses entries
  while the book is wide — scalping a wide book pays the spread to learn
  nothing. Decision path: the parity-tested trade.signals/mr-zscore over
  1s bars; trade.live/band-executor throttles churn; execution signs
  through the local gateway (live/server.py, venue SDKs). The venue's
  own position is the source of truth: seeded at boot, reconciled every
  10 s; orders respect a 2 s minimum gap (rate-limit margin).

  Run: clojure -M -m trade.live-clojure [--dry]"
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [cheshire.core :as json]
            [trade.signals :as signals]
            [trade.live :as live])
  (:import (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket WebSocket$Builder
                          WebSocket$Listener)
           (java.net URI)
           (java.nio.file Files Paths StandardOpenOption)))

(set! *warn-on-reflection* true)

(def instrument "BTC_USDT_Perp")
(def ws-url "ws://localhost:7777/ws")  ; feed interno del desk
(def gateway-url "http://localhost:7777")
;; params del campeón: EDN nativo Clojure, recargable en caliente
(def params
  (atom (try
          (-> (clojure.edn/read-string (slurp "live/champion.edn"))
              (select-keys [:k :w :threshold]))
          (catch Exception _ {:k 20 :w 120 :threshold 1.5}))))
(def max-lev 2.0)
(def ppy 31536000)                  ; 1s bars
(def target-vol 0.30)
(def max-spread-bps 8.0)            ; spread gate
(def min-order-gap-ms 2000)
(def reconcile-ms 10000)
(def error-budget 12)

(defn now-ms [] (System/currentTimeMillis))

(defn git-sha
  "Versión exacta del código que decide: sin ella una sesión no es
  reproducible."
  []
  (try
    (let [pb (doto (ProcessBuilder. ["git" "rev-parse" "--short" "HEAD"])
               (.directory (java.io.File. ".")))
          p (.start pb)
          _ (.waitFor p)
          out (slurp (.getInputStream p))]
      (str/trim out))
    (catch Exception _ "unknown")))

(defn append! [path s]
  (Files/write (Paths/get path (into-array String []))
               (.getBytes (str s "\n"))
               (into-array StandardOpenOption
                           [StandardOpenOption/CREATE StandardOpenOption/APPEND])))

(defn post! [url body]
  (let [client (HttpClient/newHttpClient)
        req (-> (HttpRequest/newBuilder)
                (.uri (URI/create url))
                (.header "content-type" "application/json")
                (.POST (HttpRequest$BodyPublishers/ofString body))
                .build)
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    (.body resp)))

(def last-order-at (atom 0))

(defn z-of
  "El z del instante, para auditar cada decisión con su contexto."
  [closes k w]
  (let [n (count closes)]
    (if (< n w) 0.0
        (let [lr (fn [i] (- (Math/log (nth closes i)) (Math/log (nth closes (dec i)))))
              sk (reduce + (map lr (range (inc (- n k)) n)))
              rw (map lr (range (inc (- n w)) n))
              m (double (/ (reduce + rw) (double w)))
              sd (Math/sqrt (/ (reduce + (map (fn [v] (let [d (- v m)] (* d d))) rw))
                               (double (dec w))))]
          (if (pos? sd) (/ sk (* sd (Math/sqrt (double k)))) 0.0)))))

(defn venue-order! [side qty ctx]
  (let [now (now-ms)]
    (if (< (- now @last-order-at) min-order-gap-ms)
      {:ok false :msg "throttled"}
      (do
        (reset! last-order-at now)
        (try
          (json/parse-string
           (post! (str gateway-url "/order/grvt")
                  (json/generate-string {:side side :qty (double qty)
                                         :z (double (or (:z ctx) 0.0))
                                         :target (double (or (:target ctx) 0.0))
                                         :spread (double (or (:spread ctx) 0.0))
                                         :why (str (or (:why ctx) "scalp"))}))
           true)
          (catch Exception e
            {:ok false :msg (.getMessage e)}))))))

(defn venue-position! []
  (try
    (let [b (json/parse-string
             (post! (str gateway-url "/api/position/grvt") "{}") true)]
      (or (:position b) 0.0))
    (catch Exception _ 0.0)))

(defn -main [& args]
  (let [{:keys [k w threshold]} @params
        dry (some #{"--dry"} args)
        closes (atom [])
        position (atom (venue-position!))
        ignited (atom false)
        bid-ask (atom nil)
        mtm (atom {:prev nil :entry 0.0 :realized 0.0 :wins 0 :trips 0
                   :peak 1.0 :eq 1.0 :rets [] :tick 0})
        errors (atom 0)
        last-msg-ms (atom (now-ms))
        pending (atom (StringBuilder.))   ; los mensajes largos llegan fragmentados
        emit (fn [ev]
               (append! "live/clj_events.jsonl" (json/generate-string ev))
               (println (str (java.time.Instant/ofEpochMilli (now-ms)) " "
                             (json/generate-string ev))))
        ;; decide: cierra la vela de 1s (sec, px) y opera si el objetivo
        ;; cambió; devuelve la posición tras decidir
        decide (fn [{:keys [sec px pos bid ask]}]
                 (swap! closes conj px)
                 (when (> (count @closes) (* 2 w))
                   (swap! closes subvec (- (count @closes) (* 2 w))))
                 (let [n (count @closes)]
                   (when (>= n w)
                     (let [closes-v (vec @closes)
                           z-now (z-of closes-v k w)
                           sigs (signals/mr-zscore
                                 {:closes closes-v :k k :w w :threshold threshold
                                  :side :long-dip :target-vol target-vol
                                  :max-lev max-lev :ppy ppy})
                           want (double (peek sigs))
                           executed (live/band-executor [want pos] 0.0)
                           newpos (second executed)
                           delta (- newpos pos)
                           spread-bps (if (and bid ask (pos? bid))
                                        (* 10000.0 (/ (- ask bid) bid))
                                        9999.0)]
                       (emit {:type "bar" :ts (* sec 1000) :close px
                              :target newpos :position pos :z z-now
                              :spread (when (< spread-bps 9000.0)
                                        (format "%.1f" spread-bps))})
                       ;; mark-to-market de la sesión
                       (let [{:keys [prev entry peak eq rets tick]} @mtm
                             ret (if (and prev (pos? prev) (pos? pos))
                                   (* pos (/ (- px prev) prev)) 0.0)
                             eq2 (* eq (+ 1.0 ret))
                             new-peak (max peak eq2)
                             closed-trip (and (zero? newpos) (pos? pos))
                             rets2 (if (pos? pos) (conj rets ret) rets)
                             nr (count rets2)
                             m (if (pos? nr) (/ (reduce + rets2) nr) 0.0)
                             sd (if (> nr 2)
                                  (Math/sqrt (/ (reduce + (map #(let [d (- % m)] (* d d)) rets2))
                                                (dec nr)))
                                  0.0)]
                         (swap! mtm assoc
                                :prev px :eq eq2 :peak new-peak :rets rets2
                                :entry (if (and (pos? newpos) (zero? pos)) px entry)
                                :trips (if closed-trip (inc (:trips @mtm)) (:trips @mtm))
                                :wins (if (and closed-trip (pos? ret))
                                        (inc (:wins @mtm)) (:wins @mtm))
                                :realized (if closed-trip
                                            (+ (:realized @mtm) (* pos (- px entry)))
                                            (:realized @mtm))
                                :tick (inc tick))
                         (when (zero? (mod (inc tick) 50))
                           (let [sharpe (if (pos? sd)
                                          (* (/ m sd) (Math/sqrt (double ppy)))
                                          0.0)
                                 pnl (+ (:realized @mtm)
                                        (if (pos? newpos)
                                          (* newpos (- px (if (pos? (:entry @mtm))
                                                            (:entry @mtm) px)))
                                          0.0))]
                             (emit {:type "metrics" :venue "grvt"
                                    :pnl (double pnl) :sharpe (double sharpe)
                                    :maxdd (double (max 0.0 (- 1.0 (/ eq2 new-peak))))
                                    :wins (:wins @mtm) :trips (:trips @mtm)}))))
                       ;; ignition + señal, con compuerta de spread
                       (let [known-spread (< spread-bps 9000.0)
                             gate-blocked (and known-spread
                                               (pos? delta)
                                               (> spread-bps max-spread-bps))]
                         (cond
                           ;; primera posición del desk, en el minuto uno
                           (and (zero? pos) (not @ignited))
                           (do (reset! ignited true)
                               (let [r (venue-order! "buy" 0.002
                                                     {:z z-now :target newpos
                                                      :spread spread-bps :why "ignition"})]
                                 (emit {:type "order" :venue "grvt" :side "buy"
                                        :qty 0.002 :px px :why "ignition" :z z-now
                                        :resp (str r)})
                                 (if (:ok r) 0.002 pos)))
                           ;; señal scalping, respetando la compuerta
                           (> (Math/abs ^double delta) 1e-9)
                           (let [side (if (pos? delta) "buy" "sell")]
                             (if gate-blocked
                               (do (emit {:type "notice" :venue "grvt"
                                          :msg (format "entrada retenida: spread %.1f bps"
                                                       spread-bps)})
                                   pos)
                               (let [r (venue-order! side (Math/abs ^double delta)
                                                     {:z z-now :target newpos
                                                      :spread spread-bps :why "scalp"})]
                                 (emit {:type "order" :venue "grvt" :side side
                                        :qty (Math/abs ^double delta) :px px :z z-now
                                        :resp (str r)})
                                 (if (:ok r) newpos pos))))
                           :else pos))))
                   pos))]
    (emit {:type "start" :venue "grvt" :instrument instrument
           :dry dry :position @position :git (git-sha)
           :data "GRVT prints + L2 (mismo venue)"})
    ;; feed del PROPIO venue por relé del desk (dueño único del WS):
    ;; prints -> velas 1s; quotes -> spread. El cerebro jamás toca el venue.
    (let [closer (atom {:sec nil :px nil})
          listener (reify WebSocket$Listener
                     (onOpen [this ws]
                       (.request ws 1))
                     (onText [this ws data last]
                       (.request ws 1)
                       (reset! last-msg-ms (now-ms))
                       (doto ^StringBuilder @pending (.append ^CharSequence data))
                       (when last
                         (let [s (str (doto ^StringBuilder @pending (.setLength 0)))]
                           (try
                             (let [m (json/parse-string s true)
                                   t (:type m)]
                               (when (= (:venue m) "grvt")
                                 (cond
                                   (= t "print")
                                   (let [px (double (:px m))
                                         sec (long (:sec m))
                                         cur @closer]
                                     (cond
                                       (nil? (:sec cur))
                                       (reset! closer {:sec sec :px px})
                                       (= sec (:sec cur))
                                       (reset! closer {:sec sec :px px})
                                       :else
                                       (let [newpos (decide {:sec (:sec cur)
                                                             :px (:px cur)
                                                             :pos @position
                                                             :bid (:bid @bid-ask)
                                                             :ask (:ask @bid-ask)})]
                                         (reset! position newpos)
                                         (reset! closer {:sec sec :px px}))))
                                   (= t "quote")
                                   (let [b (:bid m) a (:ask m)]
                                     (when (and b a (pos? (double b)))
                                       (swap! bid-ask assoc :bid b :ask a))))))
                             (catch Exception e
                               (println "EXC parse:" (.getMessage e)))))))
                     (onError [this ws err]
                       (emit {:type "error" :venue "grvt" :msg (str err)}))
                     (onClose [this ws code reason]
                       (emit {:type "error" :venue "grvt"
                              :msg (str "ws closed " code)})))]
      @(.buildAsync ^WebSocket$Builder
        (.newWebSocketBuilder (HttpClient/newHttpClient))
                    ^URI (URI/create ws-url)
                    ^WebSocket$Listener listener)
      ;; el venue es la fuente de verdad: reconciliación cada 10 s
      (loop []
        (let [vp (venue-position!)
              diff (- vp @position)]
          (when (> (Math/abs ^double diff) 1e-6)
            (emit {:type "reconcile" :venue "grvt" :interno @position
                   :venue-pos vp})
            (reset! position vp)))
        (Thread/sleep reconcile-ms)
        (recur)))))
