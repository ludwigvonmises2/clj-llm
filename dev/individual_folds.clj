;; Per-asset walk-forward fold verification, daily bars: can ANY single
;; causal system beat buy&hold per asset (Sharpe AND maxDD) in every
;; fold? Candidates (all long/short regime-switching, vol-targeted):
;;   ls-50/200   : SMA 50/200 crossover long/short (regime switch)
;;   ls-20/60    : SMA 20/60 crossover long/short (faster)
;;   triple-ls   : triple-trend gates + short leg below the slow SMA
;; Run: clojure -M dev/individual_folds.clj
(require '[trade.data :as data]
         '[trade.signals :as signals]
         '[trade.portfolio :as portfolio])

(def stock-cost 0.0002)
(def crypto-cost 0.0015)
(def ppy 365)

(defn ls-sma [c f s tv]
  (signals/sma-crossover {:closes c :fast f :slow s :target-vol tv
                          :vol-window 30 :max-lev 2.0 :ppy ppy}))

(defn triple-ls
  "Triple-trend gates for the long side; when the fast SMA is below the
  slow SMA (established downtrend) go short vol-targeted."
  [c]
  (let [tv 0.20
        tt (signals/triple-trend {:closes c :f1 20 :s1 60 :mid 100
                                  :target-vol tv :vol-window 30
                                  :max-lev 2.0 :ppy ppy})
        ls (ls-sma c 20 60 tv)]
    (mapv (fn [t x] (if (pos? t) t x)) tt ls)))

(defn bh-sigs [c] (vec (concat [0.0] (repeat (dec (count c)) 1.0))))

(println "\n═══ por activo, folds diarios (criterio doble: Sharpe Y maxDD) ═══")
(doseq [[lbl path loadf cost]
        [["AAPL"  "data/market/stock_AAPL_alpaca.json"  data/load-alpaca-json stock-cost]
         ["MSFT"  "data/market/stock_MSFT_alpaca.json"  data/load-alpaca-json stock-cost]
         ["NVDA"  "data/market/stock_NVDA_alpaca.json"  data/load-alpaca-json stock-cost]
         ["AMZN"  "data/market/stock_AMZN_alpaca.json"  data/load-alpaca-json stock-cost]
         ["META"  "data/market/stock_META_alpaca.json"  data/load-alpaca-json stock-cost]
         ["GOOGL" "data/market/stock_GOOGL_alpaca.json" data/load-alpaca-json stock-cost]
         ["BTC"   "data/market/BTCUSDT_1d.json"         data/load-binance-json crypto-cost]
         ["ETH"   "data/market/ETHUSDT_1d.json"         data/load-binance-json crypto-cost]
         ["SOL"   "data/market/SOLUSDT_1d.json"         data/load-binance-json crypto-cost]
         ["BNB"   "data/market/BNBUSDT_1d.json"         data/load-binance-json crypto-cost]
         ["XRP"   "data/market/XRPUSDT_1d.json"         data/load-binance-json crypto-cost]]
        :let [c (data/closes (loadf path))]]
  (println (str "  " lbl ":"))
  (doseq [[nm sigfn] [["ls-50/200" #(ls-sma % 50 200 0.60)]
                      ["ls-20/60"  #(ls-sma % 20 60 0.60)]
                      ["triple-ls" triple-ls]]]
    (let [[w n] (portfolio/fold-verify c sigfn ppy cost 6 120 false)]
      (println (format "    %-9s %d/%d pliegues GANAN a B&H" nm w n)))))
