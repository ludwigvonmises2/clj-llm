#!/bin/bash
# Aprendizaje en caliente: re-entrena scalp (1s) y swing (1m) con la
# señal real, escribe live/champion.edn; cerebro y motores lo recargan.
cd /home/a/work/clj-llm
while true; do
  clojure -M -m trade.learn-hot --1s data/market/BTCUSDT_1s.json >> /tmp/learning.log 2>&1
  clojure -M -m trade.learn-hot --1m >> /tmp/learning.log 2>&1
  sleep 1800
done
