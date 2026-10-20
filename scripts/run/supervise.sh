#!/bin/bash
# Generic supervisor — one instance per piece, enforced by flock on a
# user-owned lockfile (liveness is inherent: no PID cat, no kill, no
# /tmp symlinks). Adding a piece or venue = one wrapper line.
# Usage: supervise.sh <name> <workdir> <cmd> [args...]
set -u
NAME="${1:?usage: supervise.sh <name> <workdir> <cmd...>}"; shift
DIR="${1:?}"; shift

RUNDIR="$HOME/.local/run/clj-llm"
LOGDIR="/home/a/work/clj-llm/live/logs"
mkdir -p "$RUNDIR" "$LOGDIR"

exec 9>"$RUNDIR/$NAME.lock" || exit 1
flock -n 9 || { echo "$NAME: ya activo"; exit 0; }

cd "$DIR" || exit 1
while true; do
  LOG="$LOGDIR/$NAME.log"
  [ -f "$LOG" ] && [ "$(stat -c%s "$LOG")" -gt 52428800 ] && mv -- "$LOG" "$LOG.1"
  "$@" >> "$LOG" 2>&1
  rc=$?; echo "[$(date -Is)] $NAME exited ($rc) — restart in 3s" >> "$LOGDIR/$NAME.supervisor.log"
  sleep 3
done
