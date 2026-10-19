#!/usr/bin/env bash
# Paginated Binance spot klines downloader.
# Usage: dl_klines.sh SYMBOL INTERVAL START_EPOCH_MS OUT.json
# Pulls klines backwards from now to START, 1000 per request, and writes a
# single de-duplicated, time-sorted JSON array (same shape the API returns).
set -euo pipefail
SYMBOL=$1; INTERVAL=$2; START=$3; OUT=$4
PARTDIR=$(mktemp -d)
trap 'rm -rf "$PARTDIR"' EXIT
end=$(date +%s%3N)
i=0
while [ "$end" -gt "$START" ]; do
  url="https://api.binance.com/api/v3/klines?symbol=${SYMBOL}&interval=${INTERVAL}&limit=1000&endTime=${end}"
  f="$PARTDIR/p$i.json"
  curl -sf "$url" -o "$f"
  n=$(python3 -c "import json;print(len(json.load(open('$f'))))")
  [ "$n" -eq 0 ] && break
  first=$(python3 -c "import json;print(json.load(open('$f'))[0][0])")
  i=$((i+1)); end=$((first-1))
  sleep 0.15
done
python3 - "$PARTDIR" "$OUT" <<'EOF'
import json, sys, glob
d, out = sys.argv[1], sys.argv[2]
rows = {}
for f in glob.glob(d + "/p*.json"):
    for r in json.load(open(f)):
        rows[r[0]] = r
arr = [rows[k] for k in sorted(rows)]
json.dump(arr, open(out, "w"))
print(f"{out}: {len(arr)} klines")
EOF
