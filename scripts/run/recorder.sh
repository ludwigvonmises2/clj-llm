#!/bin/bash
# Grabador: dueño de los WS de venues, cintas binarias. Nunca muere.
cd /home/a/work/clj-llm/live
LOCK=/tmp/recorder.lock
[ -f "$LOCK" ] && kill -0 $(cat $LOCK) 2>/dev/null && exit 0
echo $$ > $LOCK
while true; do
  .venv-grvt/bin/python recorder.py >> /tmp/recorder.log 2>&1
  sleep 3
done
