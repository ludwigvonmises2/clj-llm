#!/bin/bash
# Arranca las 4 capas con supervisores (lockfile: nunca duplicados).
cd "$(dirname "$0")"
for s in recorder desk brain learner; do
  setsid -f ./$s.sh
  sleep 1
done
sleep 12
curl -s -m 5 -o /dev/null -w "desk: %{http_code}\n" http://localhost:7777/api/health
