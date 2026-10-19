#!/bin/bash
# OKX history downloader — public REST, no key. Paginates backwards with
# `after`. Produces kline arrays (close = idx 4), the format
# trade.learn-hot/parse-closes already understands.
# Usage: dl_okx.sh <INST> <BAR> <N_BARS>   e.g. dl_okx.sh BTC-USDT-SWAP 1s 200000
set -euo pipefail
INST="${1:-BTC-USDT-SWAP}"; BAR="${2:-1s}"; WANT="${3:-200000}"
OUT="data/market/OKX_${INST}_${BAR}.json"
ROWS="[]"
AFTER=""
N=0
while [ "$N" -lt "$WANT" ]; do
  PAGE=$(curl -s "https://www.okx.com/api/v5/market/history-candles?instId=${INST}&bar=${BAR}&limit=300${AFTER:+&after=${AFTER}}")
  BATCH=$(echo "$PAGE" | python3 -c "import json,sys; print(json.dumps(json.load(sys.stdin)['data']))")
  COUNT=$(echo "$BATCH" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))")
  [ "$COUNT" -eq 0 ] && break
  ROWS=$(python3 -c "import json,sys; a=json.loads(sys.argv[1]); b=json.loads(sys.argv[2]); print(json.dumps(b+a))" "$BATCH" "$ROWS")
  AFTER=$(echo "$BATCH" | python3 -c "import json,sys; print(json.load(sys.stdin)[0][0])")
  N=$((N + COUNT))
  sleep 0.15
done
mkdir -p data/market
python3 -c "import json,sys; rows=json.loads(sys.argv[1]); rows.sort(key=lambda r:int(r[0])); json.dump(rows, open(sys.argv[2],'w')); print(len(rows),'velas ->', sys.argv[2])" "$ROWS" "$OUT"
