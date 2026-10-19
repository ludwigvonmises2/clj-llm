#!/usr/bin/env bash
# Bybit v5 klines (spot or linear): dl_bybit.sh CATEGORY SYMBOL INTERVAL START OUT.json
# CATEGORY: spot|linear. INTERVAL in minutes. Writes ascending
# [ts,o,h,l,c,v] rows (Binance kline shape).
set -euo pipefail
CAT=$1; SYM=$2; INT=$3; FROM=$4; OUT=$5
start=$(date -d "$FROM" +%s%3N)
now=$(date +%s%3N)
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
step=$((60000 * INT))
while [ "$start" -lt "$now" ]; do
  if ! curl -sf "https://api.bybit.com/v5/market/kline?category=${CAT}&symbol=${SYM}&interval=${INT}&start=${start}&limit=1000" \
       | python3 -c "
import json,sys
try:
    rows=json.load(sys.stdin)['result']['list']
except Exception:
    sys.exit(0)
for r in reversed(rows):
    print(json.dumps([int(r[0]), r[1], r[2], r[3], r[4], r[5]]))" >> "$tmp"; then :; fi
  lines=$(wc -l < "$tmp")
  [ "$lines" -eq 0 ] && break
  last=$(tail -1 "$tmp" | python3 -c "import json,sys; print(json.loads(sys.stdin.read())[0])")
  [ "$last" -le $((start - step)) ] && break
  start=$((last + step))
  sleep 0.2
done
python3 - "$tmp" "$OUT" <<'PYEOF'
import json, sys
rows = {}
for line in open(sys.argv[1]):
    r = json.loads(line)
    rows[r[0]] = r
arr = [rows[k] for k in sorted(rows)]
json.dump(arr, open(sys.argv[2], "w"))
print(f"{sys.argv[2]}: {len(arr)} velas")
PYEOF
