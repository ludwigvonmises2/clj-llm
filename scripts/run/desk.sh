#!/bin/bash
# Supervisor único del desk (lockfile: nunca dos instancias)
cd /home/a/work/clj-llm/live
LOCK=/tmp/desk.lock
[ -f "$LOCK" ] && kill -0 $(cat $LOCK) 2>/dev/null && { echo "ya activo"; exit 0; }
echo $$ > $LOCK
while true; do
  .venv-grvt/bin/python server.py >> /tmp/desk.log 2>&1
  sleep 3
done
