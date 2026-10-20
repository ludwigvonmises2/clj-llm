#!/usr/bin/env python3
"""Alpaca PAPER venue — OWN data (REST crypto bars, this venue's
microstructure) + OWN execution (official alpaca-py SDK).

NO decision math here (house rule 1): the module streams closed 1m bars
to the relay; the Clojure brain decides and routes orders back through
the gateway, which signs with the SDK session in STATE["api"]."""
import sys, os, json, time, datetime as dt

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import store

ENV_PATH = "/home/a/work/stock-screener/.env"
KEYS = {}
for line in open(ENV_PATH):
    if line.startswith("ALPACA_"):
        k, _, v = line.strip().partition("=")
        KEYS[k] = v

from alpaca.trading.client import TradingClient
from alpaca.data.historical import CryptoHistoricalDataClient
from alpaca.data.requests import CryptoBarsRequest
from alpaca.data.timeframe import TimeFrame

STATE = {"pos": 0.0, "api": None, "instrument": "BTC/USD"}
api = TradingClient(KEYS["ALPACA_API_KEY_ID"], KEYS["ALPACA_API_SECRET_KEY"],
                    paper=True)
hist = CryptoHistoricalDataClient(KEYS["ALPACA_API_KEY_ID"],
                                  KEYS["ALPACA_API_SECRET_KEY"])

def venue_position(symbol):
    """Posición real de Alpaca o None si la API falla — jamás 0.0
    inventado (el venue es la fuente de verdad; 0 falso = phantom flat)."""
    try:
        for p in api.get_all_positions():
            if p.symbol == symbol.replace("/", ""):
                return float(p.qty)
        return 0.0
    except Exception as e:
        print(f"alpaca position error: {e}", flush=True)
        return None

def run(on_event=None, dry=False):
    """Data pump: closed 1m bars of Alpaca's own feed → relay."""
    if on_event is None:
        on_event = lambda ev: print(json.dumps(ev, default=str), flush=True)
    emit = lambda ev: on_event(dict(ev, venue="alpaca"))
    asset = STATE["instrument"]
    STATE["api"] = api
    pos = venue_position(asset)
    if pos is not None:
        STATE["pos"] = pos
    emit({"type": "start", "instrument": asset, "dry": dry,
          "position": STATE["pos"],
          "data": "velas 1m propias de Alpaca; decisión en el cerebro"})
    last_ts = 0
    backoff = 1
    while True:
        try:
            end = dt.datetime.now(dt.timezone.utc)
            r = hist.get_crypto_bars(CryptoBarsRequest(
                symbol_or_symbols=asset, timeframe=TimeFrame.Minute,
                start=end - dt.timedelta(minutes=3), end=end))
            for b in r.data.get(asset, []):
                ts = int(b.timestamp.timestamp() * 1000)
                if ts > last_ts:
                    last_ts = ts
                    emit({"type": "bar", "ts": ts, "close": float(b.close),
                          "position": STATE["pos"]})
                    store.bar("alpaca", ts, float(b.close), 0.0, 0.0,
                              STATE["pos"], 0)
            time.sleep(10)
            backoff = 1
        except Exception as e:
            emit({"type": "error", "n": backoff, "msg": str(e)[:100]})
            time.sleep(min(30, backoff * 2))
            backoff = min(backoff + 1, 6)

if __name__ == "__main__":
    run()
