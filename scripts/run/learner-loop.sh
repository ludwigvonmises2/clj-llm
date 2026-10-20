#!/bin/bash
# Hot-learning cycle: retrain scalp (1s) and swing (1m), then rest.
# The supervisor restarts the whole cycle if it dies.
cd /home/a/work/clj-llm || exit 1
while true; do
  clojure -M -m trade.learn-hot --1s data/market/BTCUSDT_1s.json
  clojure -M -m trade.learn-hot --1m
  clojure -M -m trade.learn-hot --tapes
  sleep 1800
done
