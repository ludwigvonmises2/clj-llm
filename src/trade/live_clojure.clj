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

;; Universo venue×activo: UN spec por mercado ejecutable. Perps L/S
;; (:long-only ausente); spot solo-long (el venue real no permite
;; short). Añadir un mercado aquí + 1 línea en VENUES del desk = nuevo
;; mercado operando, con la MISMA señal kaocha-tested.
(def ^:private perp-specs
  [["binance-v"   "binance:btcusdt"       "BTCUSDT"]
   ["binance-eth-v" "binance:ethusdt"     "ETHUSDT"]
   ["binance-sol-v" "binance:solusdt"     "SOLUSDT"]
   ["okx-v"        "okx:btc-usdt-swap"    "BTC-USDT-SWAP"]
   ["okx-eth-v"    "okx:eth-usdt-swap"    "ETH-USDT-SWAP"]
   ["okx-sol-v"    "okx:sol-usdt-swap"    "SOL-USDT-SWAP"]
   ["bybit-v"      "bybit:btcusdt"        "BTCUSDT"]
   ["bybit-eth-v"  "bybit:ethusdt"        "ETHUSDT"]
   ["bybit-sol-v"  "bybit:solusdt"        "SOLUSDT"]
   ["bybit-xrp-v"  "bybit:xrpusdt"        "XRPUSDT"]
   ["bybit-doge-v" "bybit:dogeusdt"       "DOGEUSDT"]
   ["hl-v"         "hl:btc"               "BTC"]
   ["hl-eth-v"     "hl:eth"               "ETH"]
   ["hl-sol-v"     "hl:sol"               "SOL"]
   ["aster-v"      "aster:btcusdt"        "BTCUSDT"]
   ["aster-eth-v"  "aster:ethusdt"        "ETHUSDT"]
   ["aster-sol-v"  "aster:solusdt"        "SOLUSDT"]])

(def ^:private spot-specs
  [["cbse-v"      "coinbase:btc-usd"     "BTC-USD"]
   ["cbse-eth-v"  "coinbase:eth-usd"     "ETH-USD"]
   ["cbse-sol-v"  "coinbase:sol-usd"     "SOL-USD"]
   ["cbse-xrp-v"  "coinbase:xrp-usd"     "XRP-USD"]
   ["krkn-v"      "kraken:btc-usd"       "BTC-USD"]
   ["krkn-eth-v"  "kraken:eth-usd"       "ETH-USD"]
   ["krkn-sol-v"  "kraken:sol-usd"       "SOL-USD"]
   ["krkn-xrp-v"  "kraken:xrp-usd"       "XRP-USD"]])

(defn ^:private mk-spec
  [[id tag instrument long-only]]
  {:id id :tag tag :instrument instrument :tf :s1 :pp 31536000
   :long-only long-only})

(def venue-specs
  (vec (concat
        [{:id "grvt" :tag "grvt" :instrument "BTC_USDT_Perp"
          :tf :s1 :pp 31536000 :ignition true}
         {:id "alpaca" :tag "alpaca" :instrument "BTC/USD"
          :tf :m1 :pp 525600}]
        (map #(mk-spec (conj % false)) perp-specs)
        (map #(mk-spec (conj % true)) spot-specs))))

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

(def ^HttpClient http-client (HttpClient/newHttpClient))

(defn post!
  "HTTP con límite duro de 5 s y cliente singleton (un HttpClient por
  llamada = churn de selectores, fallo I5)."
  [url body]
  (let [req (-> (HttpRequest/newBuilder)
                (.uri (URI/create url))
                (.header "content-type" "application/json")
                (.timeout (java.time.Duration/ofSeconds 5))
                (.POST (HttpRequest$BodyPublishers/ofString body))
                .build)
        resp (.send http-client req (HttpResponse$BodyHandlers/ofString))]
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
                          (catch Exception _ nil))]
                  ;; lectura rota: CONSERVAR los params previos — un EDN
                  ;; a medias no puede degradar el trading 30 s (F9)
                  (reset! champion-cache {:at now :params (or p params)})
                  (or p params))
                params)]
    (let [sec (get fresh mode)]
      (if (and (:k sec) (:w sec) (:threshold sec))
        (select-keys sec [:k :w :threshold :side])
        (if (= :scalp mode)
          {:k 20 :w 120 :threshold 1.5 :side :long-dip}
          {:k 60 :w 360 :threshold 3.0 :side :long-dip})))))

(defn venue-order!
  "Orden YA DECIDIDA a la pasarela. :side SIEMPRE presente (fallo C2:
  sin él el desk rellena como SELL — cada fill en dirección contraria)."
  ([id qty ctx] (venue-order! id "buy" qty ctx))
  ([id side qty ctx]
   (try
     (json/parse-string
      (post! (str gateway-url "/order/" id)
             (json/generate-string {:side side
                                    :qty (double qty)
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
                            ;; CONTRATO: [sentinel target] — el PRIMER
                            ;; elemento ancla la posición de partida y
                            ;; el segundo es el objetivo bruto; al revés
                            ;; delta==0 para siempre (fallo F1, medido)
                            ;; spot (coinbase/kraken): el venue real no
                            ;; permite short — clamp a no-negativo
                            executed (live/band-executor
                                      [pos (if (:long-only spec)
                                             (max 0.0 want) want)]
                                      0.10)
                            newpos (second executed)
                            delta (- newpos pos)
                            spread-bps (if (and bid ask (pos? bid))
                                         (* 10000.0 (/ (- ask bid) bid))
                                         9999.0)]
                        ;; MILISEGUNDOS (C1): µs rompía los ejes de los
                        ;; gráficos y podía congelar el desk completo
                        (emit {:type "bar" :venue id :ts (now-ms)
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
                              flipped (and (not (zero? pos))
                                           (not (zero? newpos))
                                           (neg? (* pos newpos)))
                              closed-trip (or (zero? newpos) flipped)
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
                                 :entry (if (or (and (not (zero? newpos))
                                                     (zero? pos)) flipped)
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
                                (let [r (venue-order! id "buy" 0.002
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
                                    r (venue-order! id side-o (Math/abs ^double delta)
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
                    ;; swing: velas 1m ya cerradas que publica el venue.
                    ;; ANTI-ECO (fallo C3): los bar del PROPIO cerebro
                    ;; vuelven por el relé y llevan :target — una vela
                    ;; nativa jamás lo lleva; sin el guard el cerebro se
                    ;; re-decide sobre sus ecos (~2/s) y envenena
                    ;; :last-bar-ts con microsegundos.
                    (let [[_ st] (first (filter (fn [[_ s]]
                                                  (= (:tag (:spec s))
                                                     (:venue m)))
                                                @rt))]
                      (when (and st (nil? (:target m)) (number? (:close m)))
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
    (let [builder (.newWebSocketBuilder (HttpClient/newHttpClient))
          fut (.buildAsync builder (URI/create ws-url) listener)]
      (try
        @(.orTimeout fut 15 java.util.concurrent.TimeUnit/SECONDS)
        (catch Exception e
          (emit {:type "error" :venue "brain"
                 :msg (str "ws handshake falló: " (.getMessage e))}))))
    ;; reconciliación cada 10 s, presupuesto de errores: el venue gana
    ;; watchdog + reconciliación: sin mensajes del relé en 90 s el
    ;; cerebro está sordo — se suicida y el supervisor lo levanta
    ;; (lo que no se puede podrir en silencio, se reinicia)
    (loop [errs (long 0) tick (long 0)]
      (Thread/sleep ^long reconcile-ms)
      (when (> (- (now-ms) @last-msg-ms) 90000)
        (emit {:type "error" :venue "brain"
               :msg "watchdog: relé mudo >90s, reinicio"})
        (System/exit 1))
      ;; telemetría cada minuto: velas acumuladas por venue y edad del
      ;; relé — el estado del cerebro es observable, nunca adivinado
      (when (zero? (mod (inc tick) 6))
        (emit {:type "tick" :venue "brain"
               :relay-age-ms (- (now-ms) @last-msg-ms)
               :closes (into {} (map (fn [[id st]]
                                       [id (count @(:closes st))])
                                     @rt))}))
      (when (> (- (now-ms) @last-msg-ms) 90000)
        (emit {:type "error" :venue "brain"
               :msg "watchdog: relé mudo >90s, reinicio"})
        (System/exit 1))
      (let [answered (atom 0)
            err2
            (reduce (fn [acc {:keys [id]}]
                      (let [vp (venue-position! id)
                            st (get @rt id)]
                        (cond
                          ;; venue inalcanzable: CONSERVAR la posición
                          ;; interna, no inventar plano
                          (nil? vp) acc
                          :else
                          (do (swap! answered inc)
                              (let [diff (- vp @(:pos st))]
                                (if (> (Math/abs ^double diff) 1e-6)
                                  (do (emit {:type "reconcile" :venue id
                                             :interno @(:pos st) :venue-pos vp})
                                      (reset! (:pos st) vp)))
                                acc)))))
                    0 venue-specs)
            ;; presupuesto de errores CONSECUTIVOS: si NADIE responde
            ;; error-budget rondas seguidas, el cerebro está roto —
            ;; reinicio limpio por el supervisor (F7)
            errs2 (if (zero? @answered) (inc errs) 0)]
        (when (>= errs2 error-budget)
          (emit {:type "error" :venue "brain"
                 :msg (str "presupuesto de errores agotado (" errs2
                           " reconciliaciones sin respuesta)")})
          (System/exit 1))
        (recur (long errs2) (inc tick))))))
