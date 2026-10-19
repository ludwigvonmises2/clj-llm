#!/usr/bin/env bash
# Bitstamp OHLC downloader: dl_bitstamp.sh PAIR STEP OUT.json
# PAIR like btcusd, STEP one of 60 180 300 900 1800 3600 ...
set -euo pipefail
PAIR=$1; STEP=$2; OUT=$3
start=$(date -d "$4" +%s 2>/dev/null || echo "$4")
: > /tmp/bs_${PAIR}_${STEP}.jsonl
while :; do
  curl -sf "https://www.bitstamp.net/api/v2/ohlc/${PAIR}/?step=${STEP}&limit=1000&start=${start}" \
    | python3 -c "import json,sys; d=json.load(sys.stdin)['data']['ohlc']; [print(json.dumps(r)) for r in d]"
  n=$(curl -sf "https://www.bitstamp.net/api/v2/ohlc/${PAIR}/?step=${STEP}&limit=1000&start=${start}" \
    | python3 -c "import json,sys; print(len(json.load(sys.stdin)['data']['ohlc']))")
  [ "$n" -eq 0 ] && break
  start=$((start + n * STEP))
  [ "$start" -gt "$(date +%s)" ] && break
  sleep 0.2
done
python3 - "$OUT" <<'PYEOF'
import json, sys
rows = {}
for line in open(f"/tmp/bs_{sys.argv[2]}_{sys.argv[1]}.jsonl"):
    r = json.loads(line)
    rows[int(r["timestamp"])] = [int(r["timestamp"])*1000, r["open"], r["high"], r["low"], r["close"], r["volume"]]
arr = [rows[k] for k in sorted(rows)]
json.dump(arr, open(sys.argv[3], "w"))
print(f"{sys.argv[3]}: {len(arr)} velas")
PYEOF
