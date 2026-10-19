#!/usr/bin/env python3
"""Order bridge for the dashboard: place a market order on GRVT testnet
through the OFFICIAL SDK (pysdk). Usage: grvt_sdk_order.py INSTRUMENT BUY|SELL SIZE"""
import sys, json
from pysdk.grvt_ccxt import GrvtCcxt
from pysdk.grvt_ccxt_env import GrvtEnv

def client():
    return GrvtCcxt(env=GrvtEnv.TESTNET, parameters={
        "api_key": "3KvMTdOTdezG4RtuPP5tD9Ztifd",
        "private_key": "0x3e2020dad058146210f3f49a7f70e5a6fa43c44a9ebe4e88d91f65d7e9d91df3",
        "trading_account_id": "1038018686688896"})

if __name__ == "__main__":
    inst, side, size = sys.argv[1], sys.argv[2].lower(), float(sys.argv[3])
    g = client()
    r = g.create_order(inst, "market", side, size)
    print(json.dumps({"ok": True, "response": r}, default=str)[:500])
