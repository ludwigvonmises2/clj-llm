from pysdk.grvt_ccxt import GrvtCcxt
from pysdk.grvt_ccxt_env import GrvtEnv
import json
g = GrvtCcxt(env=GrvtEnv.TESTNET, parameters={
    "api_key": "3KvMTdOTdezG4RtuPP5tD9Ztifd",
    "private_key": "0x3e2020dad058146210f3f49a7f70e5a6fa43c44a9ebe4e88d91f65d7e9d91df3",
    "trading_account_id": "1038018686688896"})
pos = g.fetch_positions()
print("posiciones:", json.dumps(pos, default=str)[:300])
bal = g.fetch_balance()
print("balance:", json.dumps(bal, default=str)[:200])
