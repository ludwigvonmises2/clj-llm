#!/bin/bash
# Stops everything cleanly: supervisors first, then their children.
# Locks are flock-held: releasing the fd is enough, no stale files.
ps aux | grep -E "[s]upervise\.sh|[r]un/(desk|brain|recorder|learner|learner-loop)\.sh" | awk '{print $2}' | xargs -r kill 2>/dev/null
sleep 1
ps aux | grep -E "[l]ive-clojure|[b]in/python (server|recorder)\.py" | awk '{print $2}' | xargs -r kill 2>/dev/null
sleep 1
echo "parado: $(pgrep -fc 'live-clojure|bin/python (server|recorder)\.py' || echo 0) procesos restantes"
