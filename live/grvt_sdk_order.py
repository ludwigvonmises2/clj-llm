#!/usr/bin/env python3
"""Order bridge for the dashboard: place a market order on GRVT testnet
through the OFFICIAL SDK (pysdk). Usage: grvt_sdk_order.py INSTRUMENT BUY|SELL SIZE

Credenciales por ENV (jamás en el repo — fallo M7):
  GRVT_API_KEY / GRVT_PRIVATE_KEY / GRVT_ACCOUNT_ID"""
import sys, json, os
from pysdk.grvt_ccxt import GrvtCcxt
from pysdk.grvt_ccxt_env import GrvtEnv

def client():
    try:
        return GrvtCcxt(env=GrvtEnv.TESTNET, parameters={
            "api_key": os.environ["GRVT_API_KEY"],
            "private_key": os.environ["GRVT_PRIVATE_KEY"],
            "trading_account_id": os.environ["GRVT_ACCOUNT_ID"]})
    except KeyError as e:
        raise SystemExit(f"GRVT creds ausentes en env: {e}")

if __name__ == "__main__":
    inst, side, size = sys.argv[1], sys.argv[2].lower(), float(sys.argv[3])
    g = client()
    r = g.create_order(inst, "market", side, size)
    print(json.dumps({"ok": True, "response": r}, default=str)[:500])
