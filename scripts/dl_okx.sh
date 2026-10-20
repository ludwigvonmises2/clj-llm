#!/bin/bash
# OKX history downloader — public REST, no key. Paginates backwards with
# `after`. Produces kline arrays (close = idx 4), the format
# trade.learn-hot/parse-closes already understands.
# Usage: dl_okx.sh <INST> <BAR> <N_BARS>   e.g. dl_okx.sh BTC-USDT-SWAP 1s 200000
set -euo pipefail
INST="${1:-BTC-USDT-SWAP}"; BAR="${2:-1s}"; WANT="${3:-200000}"
OUT="data/market/OKX_${INST}_${BAR}.json"
ROWS="/tmp/okx_rows_$$.json"
echo "[]" > "$ROWS"
AFTER=""
N=0
while [ "$N" -lt "$WANT" ]; do
  PAGE=$(curl -s "https://www.okx.com/api/v5/market/history-candles?instId=${INST}&bar=${BAR}&limit=300${AFTER:+&after=${AFTER}}")
  echo "$PAGE" > /tmp/okx_page_$$.json
  COUNT=$(python3 -c "import json; print(len(json.load(open('/tmp/okx_page_$$.json'))['data']))")
  [ "$COUNT" -eq 0 ] && break
  python3 - <<PYEOF
import json
b = json.load(open("/tmp/okx_page_$$.json"))["data"]
r = json.load(open("$ROWS"))
json.dump(b + r, open("$ROWS", "w"))
PYEOF
  # cursor = registro MÁS VIEJO de la página (OKX devuelve newest→oldest;
  # usar el más nuevo re-descarga la misma ventana, corpus duplicado 99%)
  AFTER=$(python3 -c "import json; print(json.load(open('/tmp/okx_page_$$.json'))['data'][-1][0])")
  N=$((N + COUNT))
  sleep 0.15
done
mkdir -p data/market
python3 - "$ROWS" "$OUT" <<'PYEOF'
import json, sys
rows = json.load(open(sys.argv[1]))
rows.sort(key=lambda r: int(r[0]))
json.dump(rows, open(sys.argv[2], "w"))
print(len(rows), "velas ->", sys.argv[2])
PYEOF
rm -f "$ROWS" /tmp/okx_page_$$.json
