;; REPL diagnosis of the brain's relay pipeline with REAL events:
;; rebuilds the runtime state exactly like -main, listens to the desk
;; relay for 12 s, and runs each event through the actual on-print
;; path (tag filter + bar closer). Prints what fires and what doesn't.
(require '[trade.live-clojure :as brain]
         '[cheshire.core :as json])
(import '(java.net.http HttpClient WebSocket WebSocket$Listener)
        'java.net.URI)

(def rt (into {}
              (map (fn [s]
                     [(:id s)
                      {:spec s :closes (atom []) :pos (atom 0.0)
                       :bid (atom nil) :ask (atom nil)
                       :closer (atom nil) :last-order (atom 0)}])
                   brain/venue-specs)))

(def counts (atom {:recv 0 :matched 0 :closes {} :types {}}))

(defn on-print-probe [m]
  (let [hit (first (filter (fn [[_ s]] (= (:tag (:spec s)) (:venue m))) rt))]
    (swap! counts update-in [:types "print"] inc)
    (when hit
      (let [[id st] hit]
        (swap! counts update :matched inc)
        (swap! (:closes st) conj (double (:px m)))
        (swap! counts assoc-in [:closes id] (count @(:closes st)))))))

(def l (reify WebSocket$Listener
         (onOpen [this ws] (.request ws 1))
         (onText [this ws data last]
           (.request ws 1)
           (when last
             (try
               (let [m (json/parse-string (str data) true)]
                 (swap! counts update :recv inc)
                 (swap! counts update-in [:types (str (:type m))] #(inc (or % 0)))
                 (when (= (:type m) "print") (on-print-probe m)))
               (catch Exception e (println "EXC" (.getMessage e))))))
         (onError [this ws e] (println "WS ERROR" e))
         (onClose [this ws c r] (println "WS CLOSE" c))))

(let [^java.util.concurrent.CompletableFuture fut
      (.buildAsync (.newWebSocketBuilder (HttpClient/newHttpClient))
                   (URI/create brain/ws-url) l)]
  @(.orTimeout fut 10 java.util.concurrent.TimeUnit/SECONDS))
(println "conectado; escuchando 12s...")
(Thread/sleep 12000)
(println "recibidos:" (:recv @counts) "por tipo:" (:types @counts))
(println "matched-print:" (:matched @counts))
(println "closes por venue:" (:closes @counts))
(System/exit 0)
