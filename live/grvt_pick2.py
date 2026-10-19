from pysdk.grvt_ccxt import GrvtCcxt
from pysdk.grvt_ccxt_env import GrvtEnv
g = GrvtCcxt(env=GrvtEnv.TESTNET, parameters={})
for s in ("BTC_USDT_Perp", "ETH_USDT_Perp", "SOL_USDT_Perp", "XRP_USDT_Perp"):
    t = g.fetch_ticker(s)
    print(s, {k: v for k, v in t.items() if v not in (None, 0, "0", "0.0")})
