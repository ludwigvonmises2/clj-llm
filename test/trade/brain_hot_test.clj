(ns trade.brain-hot-test
  "Tests of the brain's pure pieces and the hot learner's contracts:
  champion param resolution per mode (the single source both the brain
  and the engines read), the 1s audit z (sign/scale agreement with the
  production signal), and the corpus parsers (both data formats)."  (:require [clojure.test :refer [deftest is testing]]
                                                                              [trade.live-clojure :as brain]
                                                                              [trade.signals :as signals]
                                                                              [trade.learn-hot :as hot]))

(deftest champion-params-resolves-per-mode
  ;; el campeón es LA fuente única: el cerebro y el learner deben leer
  ;; secciones distintas (:scalp/:swing) y caer a defaults sanos
  (let [{k :k w :w thr :threshold} (brain/champion-params :scalp)]
    (is (and (pos? k) (pos? w) (pos? thr)) "params :scalp presentes")
    (is (< k w) "el scalp mira menos historial que su ventana de vol"))
  (let [{k :k w :w thr :threshold} (brain/champion-params :swing)]
    (is (and (pos? k) (pos? w) (pos? thr)) "params :swing presentes")))

(deftest z-of-agrees-with-signal-direction
  ;; el z de auditoría debe tener el mismo signo que la señal real:
  ;; un dip (z negativo profundo) dispara entrada long en mr-zscore
  (let [ramp (mapv (fn [i] (+ 100.0 (* 0.05 i))) (range 300))   ; deriva suave
        dip   (into ramp (mapv (fn [i] (* (peek (vec ramp)) (Math/pow 0.99 i)))
                               (range 40)))
        z-dip (brain/z-of dip 20 120)
        sigs  (signals/mr-zscore {:closes dip :k 20 :w 120 :threshold 1.5
                                  :side :long-dip :target-vol 0.30
                                  :max-lev 2.0 :ppy 31536000})]
    (is (neg? z-dip) "el dip debe dar z negativo")
    (is (pos? (peek sigs)) "y la señal real debe querer long en ese dip")))

(deftest z-of-safe-on-degenerate-input
  (is (zero? (brain/z-of [100.0 100.0 100.0] 2 3)) "varianza cero -> z 0")
  (is (zero? (brain/z-of [100.0] 20 120)) "sin ventana -> 0, nunca explota"))

(deftest parse-closes-handles-both-corpus-formats
  ;; 1m = mapas :c; 1s/1h = klines crudos de Binance (close idx 4) —
  ;; el formato vector fue el que reventó el grid en producción
  (is (= [100.0 101.0] (hot/parse-closes [{:c "100.0"} {:c 101.0}])))
  (is (= [82516.78 82516.78]
         (hot/parse-closes [[1791827298000 "82516.77000000" "82516.78"
                             "82516.77" "82516.78000000" "0.0068" 0 "562" 8 "0" "0" "0"]
                            [1791827299000 "82516.77" "82516.78" "82516.77"
                             "82516.78000000" "0.0068" 0 "562" 8 "0" "0" "0"]]))))

(deftest monte-carlo-scenarios-preserve-scale
  ;; amplificación de vol x2: los retornos duplican magnitud, el nivel
  ;; de precio se mantiene coherente (los escenarios deben ser
  ;; "pudieron pasar y no pasaron", no otro universo)
  (let [base (mapv (fn [i] (+ 100.0 (* 0.01 (Math/sin i)))) (range 100))
        amp  (hot/amplify base 2.0)]
    (is (= 100.0 (first amp)) "el primer precio no cambia")
    (is (= (count base) (count amp)) "misma longitud")
    (is (pos? (reduce max amp)) "precios positivos, serie válida"))

  (testing "block bootstrap genera una serie del tamaño pedido"
    (let [base (vec (repeatedly 500 #(+ 100.0 (rand 10))))
          rng (java.util.Random. 42)
          bs (hot/block-bootstrap base rng)]
      (is (>= (count bs) (count base)) "cubre la longitud del original")
      (is (every? #(> % 90.0) (take 50 bs)) "valores del mismo dominio"))))

(deftest weight-units-roundtrip
  (testing "the same weight buys the same NOTIONAL on every asset (fallo: 1 BTC vs 1 XRP)"
    (let [btc (brain/weight->units 1.0 10000.0 80000.0)
          xrp (brain/weight->units 1.0 10000.0 1.3)]
      (is (< (Math/abs (- (* btc 80000.0) 10000.0)) 1e-6))
      (is (< (Math/abs (- (* xrp 1.3) 10000.0)) 1e-6))))
  (testing "units->weight inverts weight->units"
    (is (< (Math/abs (- 0.37 (brain/units->weight (brain/weight->units 0.37 5000.0 104.7) 5000.0 104.7))) 1e-12)))
  (testing "zero price / capital never divide by zero"
    (is (= 0.0 (brain/weight->units 1.0 10000.0 0.0)))
    (is (= 0.0 (brain/units->weight 1.0 0.0 100.0)))))
