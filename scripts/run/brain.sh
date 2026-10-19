#!/bin/bash
cd /home/a/work/clj-llm
LOCK=/tmp/brain.lock
[ -f "$LOCK" ] && kill -0 $(cat $LOCK) 2>/dev/null && { echo "ya activo"; exit 0; }
echo $$ > $LOCK
while true; do
  clojure -M -m trade.live-clojure >> /tmp/brain2.log 2>&1
  sleep 5
done
