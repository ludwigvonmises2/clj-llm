(ns trade.live-clojure
  "The live brain — the ONLY decision-maker in the system, for ALL venues.

  Rule 1 of the house: everything that DECIDES is Clojure. The Python
  desk signs and paints; this process computes every target from the
  kaocha-tested trade.signals/mr-zscore and routes orders through the
  gateway (/order/{venue}), which signs with each venue's official SDK
  or its ultra-realistic virtual executor.

  Feed discipline: the desk owns every venue WebSocket; this brain gets
  one relay stream (ws://localhost:7777/ws) and builds each venue's own
  bars from the venue's OWN prints — never crossed. The venue's position
  is the source of truth: seeded at boot, reconciled every 10 s.

  Champion params: live/champion.edn {:scalp ... :swing ...} — retrained
  every 30 min by trade.learn-hot, hot-reloaded here every cycle.

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

(def ws-url "ws://localhost:7777/ws")   ; relay interno del desk
(def gateway-url "http://localhost:7777")
(def max-spread-bps 8.0)
(def min-order-gap-ms 2000)
(def reconcile-ms 10000)
(def error-budget 12)

;; Cada venue: su tag de prints en el relé, su instrumento, su
;; timeframe y su sección del campeón. tf :s1 = scalping 1s,
;; :m1 = swing 1m.
(def venue-specs
  [{:id "grvt"      :tag "grvt"              :instrument "BTC_USDT_Perp"  :tf :s1 :pp 31536000 :ignition true}
   {:id "okx-v"     :tag "okx:btc-usdt-swap" :instrument "BTC-USDT-SWAP"  :tf :s1 :pp 31536000}
   {:id "binance-v" :tag "binance:btcusdt"   :instrument "BTCUSDT"        :tf :s1 :pp 31536000}
   {:id "alpaca"    :tag "alpaca"            :instrument "BTC/USD"        :tf :m1 :pp 525600}])

(defn now-ms [] (System/currentTimeMillis))

(defn git-sha []
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
               ^bytes (.getBytes (str s "\n"))
               ^"[Ljava.nio.file.OpenOption;"
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

(def champion-cache (atom {:at 0 :params {}}))

(defn champion-params
  "Sección :scalp o :swing del campeón; :side solo cuando el learner lo
  haya validado N/N (hoy :long-dip, validado en 1m). CACHEADO: el
  camino de decisión jamás toca el disco (recarga cada 30 s; el
  aprendizaje en caliente escribe, el cerebro lo adopta solo)."
  [mode]
  (let [now (now-ms)
        {:keys [at params]} @champion-cache
        fresh (if (> (- now at) 30000)
                (let [p (try
                          (edn/read-string (slurp "live/champion.edn"))
                          (catch Exception _ {}))]
                  (reset! champion-cache {:at now :params p})
                  p)
                params)]
    (let [sec (get fresh mode)]
      (if (and (:k sec) (:w sec) (:threshold sec))
        (select-keys sec [:k :w :threshold :side])
        (if (= :scalp mode)
          {:k 20 :w 120 :threshold 1.5 :side :long-dip}
          {:k 60 :w 360 :threshold 3.0 :side :long-dip})))))

(defn venue-order! [id qty ctx]
  (let [now (now-ms)]
    (try
      (json/parse-string
       (post! (str gateway-url "/order/" id)
              (json/generate-string {:qty (double qty)
                                     :z (double (or (:z ctx) 0.0))
                                     :target (double (or (:target ctx) 0.0))
                                     :spread (double (or (:spread ctx) 0.0))
                                     :why (str (or (:why ctx) "signal"))}))
       true)
      (catch Exception e
        {:ok false :msg (.getMessage e)}))))

(defn venue-position!
  "Posición según el VENUE, o nil si no está disponible — jamás 0.0:
  confundir venue-caído con posición-plana resets a cero y dispara
  sobre-órdenes (fallo C4 del review)."
  [id]
  (try
    (let [b (json/parse-string
             (post! (str gateway-url "/api/position/" id) "{}") true)]
      (:position b))
    (catch Exception _ nil)))

(defn mtm-init [] {:prev nil :entry 0.0 :realized 0.0 :wins 0 :trips 0
                   :peak 1.0 :eq 1.0 :rets [] :tick 0})

(defn z-of
  "El z del instante, solo para AUDITORÍA del evento — la decisión sale
  SIEMPRE de signals/mr-zscore. Mismo math, mismos params."  [closes k w]
  (let [n (count closes)]
    (if (< n w) 0.0
        (let [lr (fn [i] (- (Math/log (nth closes i))
                            (Math/log (nth closes (dec i)))))
              sk (reduce + (map lr (range (inc (- n k)) n)))
              rw (map lr (range (inc (- n w)) n))
              m (double (/ (reduce + rw) (double w)))
              sd (Math/sqrt (/ (reduce + (map (fn [v] (let [d (- v m)] (* d d))) rw))
                               (double (dec w))))]
          (if (pos? sd) (/ sk (* sd (Math/sqrt (double k)))) 0.0)))))

(defn -main [& args]
  (let [dry (some #{"--dry"} args)
        ;; runtime por venue: barras, posición, libro, throttle, mtm
        rt (atom (into {}
                       (map (fn [s]
                              [(:id s)
                               {:spec s :closes (atom []) :pos (atom 0.0)
                                :bid (atom nil) :ask (atom nil)
                                :closer (atom nil) :last-order (atom 0)
                                :mtm (atom (mtm-init)) :ignited (atom false)
                                :last-bar-ts (atom 0) :errors (atom 0)}])
                            venue-specs)))
        last-msg-ms (atom (now-ms))
        pending (atom (StringBuilder.))
        emit (fn [ev]
               (append! "live/clj_events.jsonl" (json/generate-string ev))
               (println (str (java.time.Instant/ofEpochMilli (now-ms)) " "
                             (json/generate-string ev))))
        ;; decide sobre la vela cerrada de UNA venue; devuelve pos final
        ;; decide sobre la vela cerrada de UNA venue; devuelve pos final
        decide! (fn [{:keys [id spec px pos bid ask]}]
                  (let [st (get @rt id)
                        {:keys [k w threshold side]} (champion-params
                                                      (if (= :s1 (:tf spec))
                                                        :scalp :swing))
                        closes (swap! (:closes st) conj px)
                        _ (when (> (count closes) (* 2 w))
                            (swap! (:closes st) subvec (- (count closes) (* 2 w))))
                        cs (vec @(:closes st))
                        n (count cs)]
                    (if (< n w)
                      pos
                      (let [z-now (z-of cs k w)
                            sigs (signals/mr-zscore
                                  {:closes cs :k k :w w :threshold threshold
                                   :side (or side :long-dip)
                                   :target-vol 0.30 :max-lev 2.0 :ppy (:pp spec)})
                            want (double (peek sigs))
                            executed (live/band-executor [want pos] 0.0)
                            newpos (second executed)
                            delta (- newpos pos)
                            spread-bps (if (and bid ask (pos? bid))
                                         (* 10000.0 (/ (- ask bid) bid))
                                         9999.0)]
                        (emit {:type "bar" :venue id :ts (* (now-ms) 1000)
                               :close px :target newpos :position pos
                               :z z-now
                               :spread (when (< spread-bps 9000.0)
                                         (format "%.1f" spread-bps))})
                        ;; mark-to-market de la sesión
                        (let [mtm (:mtm st)
                              {:keys [prev entry peak eq rets tick]} @mtm
                              ret (if (and prev (not (zero? prev))
                                           (not (zero? pos)))
                                    (* pos (/ (- px prev) prev)) 0.0)
                              eq2 (* eq (+ 1.0 ret))
                              new-peak (max peak eq2)
                              closed-trip (and (zero? newpos)
                                               (not (zero? pos)))
                              rets2 (if (not (zero? pos)) (conj rets ret) rets)
                              nr (count rets2)
                              m (if (pos? nr) (/ (reduce + rets2) nr) 0.0)
                              sd (if (> nr 2)
                                   (Math/sqrt (/ (reduce + (map #(let [d (- % m)]
                                                                   (* d d)) rets2))
                                                 (dec nr)))
                                   0.0)]
                          (swap! mtm assoc
                                 :prev px :eq eq2 :peak new-peak :rets rets2
                                 :entry (if (and (not (zero? newpos)) (zero? pos))
                                          px entry)
                                 :trips (if closed-trip (inc (:trips @mtm)) (:trips @mtm))
                                 :wins (if (and closed-trip (pos? ret))
                                         (inc (:wins @mtm)) (:wins @mtm))
                                 :realized (if closed-trip
                                             (+ (:realized @mtm) (* pos (- px entry)))
                                             (:realized @mtm))
                                 :tick (inc tick))
                          (when (zero? (mod (inc tick) 50))
                            (let [sharpe (if (pos? sd)
                                           (* (/ m sd) (Math/sqrt (double (:pp spec))))
                                           0.0)
                                  pnl (+ (:realized @mtm)
                                         (if (not (zero? newpos))
                                           (* newpos
                                              (- px (if (not (zero? (:entry @mtm)))
                                                      (:entry @mtm) px)))
                                           0.0))]
                              (emit {:type "metrics" :venue id
                                     :pnl (double pnl) :sharpe (double sharpe)
                                     :maxdd (double (max 0.0 (- 1.0 (/ eq2 new-peak))))
                                     :wins (:wins @mtm) :trips (:trips @mtm)}))))
                        ;; ejecución: ignition solo en venues reales
                        (let [known-spread (< spread-bps 9000.0)
                              gate-blocked (and known-spread
                                                (pos? delta)
                                                (> spread-bps max-spread-bps))
                              throttle (< (- (now-ms) @(:last-order st))
                                          min-order-gap-ms)
                              ignition? (and (:ignition spec) (zero? pos)
                                             (not @(:ignited st)))]
                          (cond
                            throttle pos
                            ignition?
                            (do (reset! (:ignited st) true)
                                (let [r (venue-order! id 0.002
                                                      {:z z-now :target newpos
                                                       :spread spread-bps
                                                       :why "ignition"})]
                                  (emit {:type "order" :venue id :side "buy"
                                         :qty 0.002 :px px :why "ignition"
                                         :z z-now :resp (str r)})
                                  (if (:ok r) 0.002 pos)))
                            (pos? (Math/abs ^double delta))
                            (if gate-blocked
                              (do (emit {:type "notice" :venue id
                                         :msg (format "entrada retenida: spread %.1f bps"
                                                      spread-bps)})
                                  pos)
                              (let [side-o (if (pos? delta) "buy" "sell")
                                    r (venue-order! id (Math/abs ^double delta)
                                                    {:z z-now :target newpos
                                                     :spread spread-bps
                                                     :why "scalp"})]
                                (reset! (:last-order st) (now-ms))
                                (emit {:type "order" :venue id :side side-o
                                       :qty (Math/abs ^double delta) :px px
                                       :z z-now :resp (str r)})
                                (if (:ok r) newpos pos)))
                            :else pos))))))
        ;; cierra la vela de un venue (prints 1s) y decide
        on-close! (fn [id px sec]
                    (let [st (get @rt id)]
                      (when st
                        (let [newpos (decide! {:id id :spec (:spec st) :px px
                                               :pos @(:pos st)
                                               :bid @(:bid st) :ask @(:ask st)})]
                          (reset! (:pos st) newpos)))))
        on-print (fn [m]
                   (let [[venue-id st] (first (filter (fn [[_ s]]
                                                        (= (:tag (:spec s))
                                                           (:venue m)))
                                                      @rt))]
                     (when st
                       (let [px (double (:px m)) sec (long (:sec m))
                             closer (:closer st)]
                         (if (or (nil? @closer) (= sec (:sec @closer)))
                           (reset! closer {:sec sec :px px})
                           (do (on-close! venue-id (:px @closer) (:sec @closer))
                               (reset! closer {:sec sec :px px})))))))
        on-bar-1m (fn [m]
                    ;; swing: velas 1m ya cerradas que publica el venue
                    (let [[_ st] (first (filter (fn [[_ s]]
                                                  (= (:tag (:spec s))
                                                     (:venue m)))
                                                @rt))]
                      (when (and st (number? (:close m)))
                        (let [ts (long (:ts m))]
                          (when (> ts @(:last-bar-ts st))
                            (reset! (:last-bar-ts st) ts)
                            (decide! {:id (:id (:spec st)) :spec (:spec st)
                                      :px (double (:close m)) :pos @(:pos st)
                                      :bid @(:bid st) :ask @(:ask st)}))))))
        listener (reify WebSocket$Listener
                   ;; CONTRATO java: cada método devuelve CompletionStage
                   ;; o null. Devolver lo último evaluado (p.ej. la
                   ;; posición Double de decide!) mata el socket con
                   ;; ClassCastException en la PRIMERA decisión — fallo
                   ;; medido; todo método termina en nil explícito.
                   ;; FLOW CONTROL: demanda alta y re-arme por lotes —
                   ;; pedir 1 a 1 ahoga al cliente con el burst history.
                   (onOpen [this ws]
                     (.request ws 4096) nil)
                   (onText [this ws data last]
                     (try
                       (.request ws 4096)
                       (reset! last-msg-ms (now-ms))
                       (doto ^StringBuilder @pending (.append ^CharSequence data))
                       (when last
                         ;; LEER primero, limpiar después: vaciar antes de
                         ;; convertir dejaba s="" SIEMPRE — cerebro sordo
                         ;; con el watchdog engañado (fallo C3, medido)
                         (let [s (str @pending)]
                           (.setLength ^StringBuilder @pending 0)
                           (try
                             (let [m (json/parse-string s true)
                                   t (:type m)]
                               (cond
                                 (= t "print") (on-print m)
                                 (= t "bar") (on-bar-1m m)
                                 (= t "quote")
                                 (let [[_ st] (first (filter
                                                      (fn [[_ s]]
                                                        (= (:tag (:spec s)) (:venue m)))
                                                      @rt))]
                                   (when (and st (:bid m) (:ask m)
                                              (pos? (double (:bid m))))
                                     (reset! (:bid st) (double (:bid m)))
                                     (reset! (:ask st) (double (:ask m)))))))
                             (catch Exception e
                               (println "EXC dispatch:" (.getMessage e)
                                        (first (.getStackTrace e)))))))
                       nil
                       (catch Exception e
                         (println "EXC listener:" (.getMessage e))
                         nil)))
                   (onError [this ws err]
                     (emit {:type "error" :venue "brain" :msg (str err)})
                     nil)
                   (onClose [this ws code reason]
                     (emit {:type "error" :venue "brain"
                            :msg (str "ws closed " code)})
                     nil))]
    (emit {:type "start" :venue "brain" :dry dry :git (git-sha)
           :venues (mapv :id venue-specs)
           :data "relé del desk; el venue es la fuente de verdad"})
    ;; seed de posiciones: el venue manda (nil = no disponible: no tocar)
    (doseq [{:keys [id]} venue-specs]
      (when-let [p (venue-position! id)]
        (let [st (get @rt id)]
          (reset! (:pos st) p)
          (emit {:type "reconcile" :venue id :interno 0.0 :venue-pos p}))))
    ;; handshake con límite duro: un deref bloqueante sin timeout se
    ;; cuelga en silencio (fallo medido hoy) — si falla, lo grita y el
    ;; watchdog del loop fuerza el reinicio limpio
    (let [fut (.buildAsync ^WebSocket$Builder
               (.newWebSocketBuilder (HttpClient/newHttpClient))
                           ^URI (URI/create ws-url)
                           ^WebSocket$Listener listener)]
      (try
        @(.orTimeout fut 15 java.util.concurrent.TimeUnit/SECONDS)
        (catch Exception e
          (emit {:type "error" :venue "brain"
                 :msg (str "ws handshake falló: " (.getMessage e))}))))
    ;; reconciliación cada 10 s, presupuesto de errores: el venue gana
    ;; watchdog + reconciliación: sin mensajes del relé en 90 s el
    ;; cerebro está sordo — se suicida y el supervisor lo levanta
    ;; (lo que no se puede podrir en silencio, se reinicia)
    (loop [errs (long 0)]
      (Thread/sleep ^long reconcile-ms)
      (when (> (- (now-ms) @last-msg-ms) 90000)
        (emit {:type "error" :venue "brain"
               :msg "watchdog: relé mudo >90s, reinicio"})
        (System/exit 1))
      (let [err2
            (reduce (fn [acc {:keys [id]}]
                      (let [vp (venue-position! id)
                            st (get @rt id)]
                        (cond
                          ;; venue inalcanzable: CONSERVAR la posición
                          ;; interna, contar el error, no inventar plano
                          (nil? vp)
                          (do (when (zero? (mod (inc acc) 6))
                                (emit {:type "error" :venue id
                                       :msg "posición del venue no disponible"}))
                              (inc acc))
                          :else
                          (let [diff (- vp @(:pos st))]
                            (if (> (Math/abs ^double diff) 1e-6)
                              (do (emit {:type "reconcile" :venue id
                                         :interno @(:pos st) :venue-pos vp})
                                  (reset! (:pos st) vp)
                                  acc)
                              acc)))))
                    errs venue-specs)]
        (recur (long err2))))))
