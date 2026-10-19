#!/bin/bash
# Stops everything cleanly: supervisors first, then their children.
# Matches absolute AND relative invocations (./brain.sh vs run/brain.sh)
# — the gap that left a pre-flock supervisor running twice.
ps aux | grep -E "[s]upervise\.sh|[ /](desk|brain|recorder|learner|learner-loop)\.sh" | awk '{print $2}' | xargs -r kill 2>/dev/null
sleep 1
ps aux | grep -E "[l]ive-clojure|[b]in/python (server|recorder)\.py|[l]earn-hot" | awk '{print $2}' | xargs -r kill 2>/dev/null
sleep 1
ps aux | grep -E "[l]ive-clojure|[b]in/python (server|recorder)\.py|[l]earn-hot" | awk '{print $2}' | xargs -r kill -9 2>/dev/null
echo "parado: $(pgrep -fc 'live-clojure|bin/python (server|recorder)\.py|learn-hot' 2>/dev/null || echo 0) procesos restantes"
