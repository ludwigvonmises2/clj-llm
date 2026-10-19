#!/bin/bash
# Brain: trade.live-clojure decides with the kaocha-tested signal.
exec "$(dirname "$0")/supervise.sh" brain /home/a/work/clj-llm \
  clojure -M -m trade.live-clojure
