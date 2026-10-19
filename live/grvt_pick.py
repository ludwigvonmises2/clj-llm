from pysdk.grvt_ccxt import GrvtCcxt
from pysdk.grvt_ccxt_env import GrvtEnv
g = GrvtCcxt(env=GrvtEnv.TESTNET, parameters={})
rows = []
for s in list(g.markets)[:40]:
    if "_Perp" not in s: continue
    try:
        t = g.fetch_ticker(s)
        rows.append((float(t.get("base_volume") or 0), s))
    except Exception: pass
for v, s in sorted(rows, reverse=True)[:6]:
    print(f"{s:22s} vol={v:.1f}")
