#!/bin/bash
# Para todo limpiamente: supervisores, desk, brain, recorder, learner.
ps aux | grep -E "[r]un/(desk|brain|recorder|learner).sh|[l]ive-clojure|[b]in/python (server|recorder)" | awk '{print $2}' | while read p; do kill -9 $p 2>/dev/null; done
sleep 1
for pid in $(ps aux | grep -E "[l]ive-clojure|[b]in/python (server|recorder)" | awk '{print $2}'); do kill -9 $pid 2>/dev/null; done
rm -f /tmp/desk.lock /tmp/brain.lock /tmp/recorder.lock /tmp/learner.lock
echo "parado: $(ps aux | grep -cE '[l]ive-clojure|[b]in/python (server|recorder)' || true) procesos restantes"
