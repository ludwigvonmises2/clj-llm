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
            [trade.learn-hot :as hot])
  (:import (java.net.http HttpClient HttpClient$Version HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers WebSocket WebSocket$Builder
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
   :long-only long-only :capital-usd 10000.0})

(def venue-specs
  (vec (concat
        [{:id "grvt" :tag "grvt" :instrument "BTC_USDT_Perp"
          :tf :s1 :pp 31536000 :ignition true :capital-usd 1000.0}
         {:id "alpaca" :tag "alpaca" :instrument "BTC/USD"
          :tf :m1 :pp 525600 :capital-usd 10000.0}]
        (map #(mk-spec (conj % false)) perp-specs)
        (map #(mk-spec (conj % true)) spot-specs))))

(def ^:private min-order-usd 1.0)
(def ^:private band 0.10)

(defn trim-window
  "conj x and keep the last n — copied, so the backing array is freed
  (subvec+conj kept every close ever seen alive: R5)."
  [v x n]
  (let [v (conj v x) c (count v)]
    (if (> c n) (into [] (subvec v (- c n))) v)))

(defn valid-px? [x] (and (number? x) (Double/isFinite (double x)) (pos? (double x))))

(defn weight->units
  "The signal speaks in exposure weights (fraction of capital, as in the
  backtest); venues speak in coin units. 1.0 = capital-usd of notional."
  ^double [^double weight ^double capital-usd ^double px]
  (if (pos? px) (/ (* weight capital-usd) px) 0.0))

(defn units->weight
  ^double [^double units ^double capital-usd ^double px]
  (if (pos? capital-usd) (/ (* units px) capital-usd) 0.0))

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

;; HTTP/1.1 explicit: the JDK default tries an h2c upgrade on POST and
;; aiohttp rejects it (desk log: BadHttpMethod b'{}') — reconcile reads
;; were silently failing
(defn- http11 ^HttpClient []
  (-> (HttpClient/newBuilder) (.version HttpClient$Version/HTTP_1_1) .build))

(def ^HttpClient http-client (http11))

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
                                    :client_order_id (:coid ctx)
                                    :qty (double qty)
                                    :z (double (or (:z ctx) 0.0))
                                    :target (double (or (:target ctx) 0.0))
                                    :spread (double (or (:spread ctx) 0.0))
                                    :why (str (or (:why ctx) "signal"))}))
      true)
     ;; transport failure (timeout, 500, broken JSON): the order MAY have
     ;; filled — outcome unknown, never "not filled" (R6)
     (catch Exception e
       {:ok false :unknown true :msg (.getMessage e)}))))

;; R15: a restart used to forget every close — positions went unmanaged
;; for w bars (2-12 min, hours on thin venues). The recorder's tape of the
;; SAME venue (rule 4) refills the window at boot.
(defn tape-closes
  "Last n 1s closes of `tag` from the newest tape in dir, [] if none."
  [dir tag n]
  (let [suffix (str "_" tag ".prints")
        f (->> (.listFiles (java.io.File. ^String dir))
               (filter (fn [^java.io.File f] (.endsWith (.getName f) suffix)))
               (sort-by (fn [^java.io.File f] (.getName f)))
               last)]
    (if f
      (let [cs (try (hot/read-prints-tape (.getPath ^java.io.File f) (* 40 (long n)))
                    (catch Exception _ []))]
        (vec (take-last n (filter valid-px? cs))))
      [])))

(def quote-stale-ms 5000)

(defn fresh-quote
  "bid/ask only while the quote is younger than quote-stale-ms: a dead
  quote feed must not gate (or pass) on a minutes-old spread (R14)."
  [st k]
  (when (< (- (now-ms) (long @(:quote-ts st))) quote-stale-ms)
    @(get st k)))

(def ^:private order-seq (atom 0))
(defn new-coid
  "Client order id: the gateway executes each id at most once."
  [id] (str id "-" (now-ms) "-" (swap! order-seq inc)))

(def settle-ms
  "An order younger than this may not be visible in the venue's position
  yet: reconcile must not overwrite with a read that may predate it (R4)."
  3000)

(defn reconcile-apply?
  "Apply a venue position read that STARTED at t0? Only when no order was
  sent from settle-ms before the read began, and the position did not
  move while the HTTP read was in flight."
  [t0 last-order-ms pos-before pos-now]
  (and (< (long last-order-ms) (- (long t0) settle-ms))
       (= pos-before pos-now)))

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

;; DECISION CORE, pure: state in -> action out. decide! only performs the
;; effects (emit / order). Everything the brain decides is testable here.
(defn plan
  "cs: closes window (already including px); pos-u: venue position in
  UNITS. Returns {:action :warming} until w closes exist, else the target
  and one action: :throttle | :ignite | :gate | :order | :hold."
  [{:keys [cs k w threshold side spec px pos-u bid ask since-last-ms ignited?
           awaiting?]}]
  (cond
    (< (count cs) w) {:action :warming}
    (not (valid-px? px)) {:action :hold :pos-u pos-u}
    :else
    (let [cap (double (:capital-usd spec 10000.0))
          px (double px)
          pos-u (double pos-u)
          ;; the signal speaks WEIGHTS (as in the backtest); venues, units
          pos (units->weight pos-u cap px)
          z-now (z-of cs k w)
          want (double (peek (signals/mr-zscore
                              {:closes cs :k k :w w :threshold threshold
                               :side (or side :long-dip)
                               :target-vol 0.30 :max-lev 2.0 :ppy (:pp spec)})))
          ;; CONTRATO: [sentinel target] — el PRIMER elemento ancla la
          ;; posición de partida (fallo F1); spot: clamp a no-negativo
          want (if (:long-only spec) (max 0.0 want) want)
          ;; no-trade band measured from the HELD weight, exactly as
          ;; band-executor does bar to bar in the backtest (R10: the
          ;; [pos want] sentinel measured it from 0 and dumped |pos|<=0.10)
          newpos (if (> (Math/abs (- want pos)) band) want pos)
          newpos-u (weight->units newpos cap px)
          delta-u (- newpos-u pos-u)
          spread-bps (if (and bid ask (pos? (double bid)))
                       (* 10000.0 (/ (- (double ask) (double bid)) (double bid)))
                       9999.0)
          base {:z z-now :target newpos :newpos-u newpos-u :pos-u pos-u
                :spread-bps spread-bps}]
      (assoc base :action
             (cond
               awaiting? :await
               (< (double since-last-ms) min-order-gap-ms) :throttle
               (and (:ignition spec) (zero? pos-u) (not ignited?)) :ignite
               ;; >= $1 notional: the units<->weight round trip leaves
               ;; ~1e-16 residue that must not trade
               (< (* (Math/abs delta-u) px) min-order-usd) :hold
               ;; R14: gate what ADDS risk (long or short entry), never
               ;; an exit; unknown spread passes (thin venues without a
               ;; quote feed would never trade otherwise)
               (and (< spread-bps 9000.0)
                    (> (Math/abs newpos-u) (Math/abs pos-u))
                    (> spread-bps max-spread-bps)) :gate
               :else :order)
             :side (if (pos? delta-u) "buy" "sell")
             :qty (Math/abs delta-u)))))

(defn -main [& args]
  (let [dry (some #{"--dry"} args)
        ;; runtime por venue: barras, posición, libro, throttle
        rt (atom (into {}
                       (map (fn [s]
                              [(:id s)
                               {:spec s :closes (atom []) :pos (atom 0.0)
                                :bid (atom nil) :ask (atom nil)
                                :closer (atom nil) :last-order (atom 0) :await (atom false)
                                :quote-ts (atom 0)
                                :ignited (atom false)
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
                        cs (swap! (:closes st) trim-window px (* 2 w))]
                    (let [{:keys [action z target newpos-u pos-u spread-bps]
                           :as pl}
                          (plan {:cs cs :k k :w w :threshold threshold
                                 :side side :spec spec :px px :pos-u pos
                                 :bid bid :ask ask
                                 :since-last-ms (- (now-ms) @(:last-order st))
                                 :ignited? @(:ignited st)
                                 :awaiting? @(:await st)})]
                     (if (= action :warming)
                      pos
                      (let [z-now z
                            newpos target]
                        ;; MILISEGUNDOS (C1): µs rompía los ejes de los
                        ;; gráficos y podía congelar el desk completo
                        (emit {:type "bar" :venue id :ts (now-ms)
                               :close px :target newpos :position pos-u
                               :z z-now
                               :spread (when (< spread-bps 9000.0)
                                         (format "%.1f" spread-bps))})
                        ;; ejecución: el plan ya decidió; aquí solo efectos
                        (case action
                          (:throttle :hold :await) pos-u
                          :ignite
                          (do (reset! (:ignited st) true)
                              ;; stamp BEFORE sending: throttle + reconcile
                              ;; see the in-flight order (R4/R15)
                              (reset! (:last-order st) (now-ms))
                              (let [r (venue-order! id "buy" 0.002
                                                    {:z z-now :target newpos
                                                     :spread spread-bps
                                                     :coid (new-coid id)
                                                     :why "ignition"})]
                                (when (:unknown r) (reset! (:await st) true))
                                (emit {:type "order" :venue id :side "buy"
                                       :qty 0.002 :px px :why "ignition"
                                       :z z-now :resp (str r)})
                                (if (:ok r) 0.002 pos-u)))
                          :gate
                          (do (emit {:type "notice" :venue id
                                     :msg (format "entrada retenida: spread %.1f bps"
                                                  spread-bps)})
                              pos-u)
                          :order
                          (let [_ (reset! (:last-order st) (now-ms))
                                r (venue-order! id (:side pl) (:qty pl)
                                                {:z z-now :target newpos
                                                 :spread spread-bps
                                                 :coid (new-coid id)
                                                 :why "scalp"})]
                            ;; unknown outcome: stop deciding this venue
                            ;; until reconcile reads the truth (R6)
                            (when (:unknown r) (reset! (:await st) true))
                            (emit {:type "order" :venue id :side (:side pl)
                                   :qty (:qty pl) :px px
                                   :z z-now :resp (str r)})
                            ;; the venue's own post-fill position wins
                            ;; (partial fills), else the planned one
                            (if (:ok r)
                              (let [vp (:pos r)] (if (number? vp) (double vp) newpos-u))
                              pos-u))))))))
        ;; cierra la vela de un venue (prints 1s) y decide
        on-close! (fn [id px sec]
                    (let [st (get @rt id)]
                      (when st
                        (let [newpos (decide! {:id id :spec (:spec st) :px px
                                               :pos @(:pos st)
                                               :bid (fresh-quote st :bid) :ask (fresh-quote st :ask)})]
                          (reset! (:pos st) newpos)))))
        on-print (fn [m]
                   (let [[venue-id st] (first (filter (fn [[_ s]]
                                                        (= (:tag (:spec s))
                                                           (:venue m)))
                                                      @rt))]
                     (when (and st (valid-px? (:px m)))
                       (let [px (double (:px m)) sec (long (:sec m))
                             closer (:closer st)]
                         (if (or (nil? @closer) (= sec (:sec @closer)))
                           (reset! closer {:sec sec :px px})
                           (let [{cpx :px csec :sec} @closer]
                             ;; advance FIRST: a throwing decision must not
                             ;; re-close the same bar on every print (R12)
                             (reset! closer {:sec sec :px px})
                             (on-close! venue-id cpx csec)))))))
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
                      (when (and st (nil? (:target m)) (valid-px? (:close m)))
                        (let [ts (long (:ts m))]
                          (when (> ts @(:last-bar-ts st))
                            (reset! (:last-bar-ts st) ts)
                            (reset! (:pos st)
                                    (decide! {:id (:id (:spec st)) :spec (:spec st)
                                              :px (double (:close m)) :pos @(:pos st)
                                              :bid (fresh-quote st :bid) :ask (fresh-quote st :ask)})))))))
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
                                     (reset! (:ask st) (double (:ask m)))
                                     (reset! (:quote-ts st) (now-ms))))))
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
    ;; ventana sembrada desde la cinta del propio venue (R15)
    (doseq [{:keys [id tag tf]} venue-specs
            :when (= :s1 tf)]
      (let [cs (tape-closes "live/tape" tag
                            (* 2 (long (:w (champion-params :scalp)))))]
        (when (seq cs)
          (reset! (:closes (get @rt id)) cs))))
    (emit {:type "notice" :venue "brain"
           :msg (str "ventanas sembradas desde cintas: "
                     (count (filter (fn [[_ st]] (seq @(:closes st))) @rt))
                     "/" (count @rt))})
    ;; handshake con límite duro: un deref bloqueante sin timeout se
    ;; cuelga en silencio (fallo medido hoy) — si falla, lo grita y el
    ;; watchdog del loop fuerza el reinicio limpio
    (let [builder (.newWebSocketBuilder (http11))
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
                      (let [st (get @rt id)
                            t0 (now-ms)
                            p0 @(:pos st)
                            vp (venue-position! id)]
                        (cond
                          ;; venue inalcanzable: CONSERVAR la posición
                          ;; interna, no inventar plano
                          (nil? vp) acc
                          :else
                          (do (swap! answered inc)
                              ;; R4: a read that may predate an in-flight
                              ;; order is skipped (next round re-reads)
                              (when (reconcile-apply? t0 @(:last-order st)
                                                      p0 @(:pos st))
                                (when (> (Math/abs (- (double vp) (double p0))) 1e-9)
                                  (when (compare-and-set! (:pos st) p0 vp)
                                    (emit {:type "reconcile" :venue id
                                           :interno p0 :venue-pos vp})))
                                (reset! (:await st) false))
                              acc))))
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
