;; Pure delivery-rate probe: how fast does the java WS client actually
;; receive, per second? No parsing, no processing.
(import '(java.net.http HttpClient WebSocket WebSocket$Listener)
        'java.net.URI 'java.util.concurrent.atomic.AtomicLong)

(def n (AtomicLong. 0))
(def sec0 (System/currentTimeMillis))
(def last-sec (atom -1))

(def l (reify WebSocket$Listener
         (onOpen [this ws] (.request ws 1000000))
         (onText [this ws data last]
           (.request ws 1000000)
           (let [c (.incrementAndGet n)
                 s (quot (- (System/currentTimeMillis) sec0) 1000)]
             (when (not= s @last-sec)
               (reset! last-sec s)
               (println (str "t+" s "s acum=" c)))
             (when (= c 4000) (System/exit 0))))
         (onError [this ws e] (println "ERR" e) (System/exit 1))))

(let [^java.util.concurrent.CompletableFuture fut
      (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                   (URI/create "ws://localhost:7777/ws") l)]
  @(.orTimeout fut 10 java.util.concurrent.TimeUnit/SECONDS))
(Thread/sleep 15000)
(println "total recibido en 15s:" (.get n))
(System/exit 0)
