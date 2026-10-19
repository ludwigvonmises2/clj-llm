from pysdk.grvt_ccxt import GrvtCcxt
from pysdk.grvt_ccxt_env import GrvtEnv
import json

g = GrvtCcxt(env=GrvtEnv.TESTNET, parameters={
    "api_key": "3KvMTdOTdezG4RtuPP5tD9Ztifd",
    "private_key": "0x3e2020dad058146210f3f49a7f70e5a6fa43c44a9ebe4e88d91f65d7e9d91df3",
    "trading_account_id": "1038018686688896",
})
print("markets:", len(g.markets))
t = g.fetch_ticker("SOL_USDT_Perp")
print("SOL:", {k: t.get(k) for k in ("last", "bid", "ask")})
o = g.create_order("SOL_USDT_Perp", "market", "buy", 0.1)
print("order:", json.dumps(o, default=str)[:400])
