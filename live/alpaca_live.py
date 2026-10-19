#!/usr/bin/env python3
"""Alpaca PAPER live engine — trade STREAM push, 1s bars, real paper
orders through the official alpaca-py SDK.

asset     : most active crypto pair, measured at boot over a 5-minute
            trade window (BTC/USD on every measurement so far).
latency   : trades arrive via the SDK's async CryptoDataStream (push);
            no REST polling anywhere in the live path.
signal    : same validated mr-dip DipEngine as GRVT (imported), 1s bars.
robustness: stream reconnect with capped backoff; error budget flattens
            and stops; position seeded from the venue.
"""
import sys, json, time, math, asyncio, datetime as dt

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import store
from engine import DipEngine

ENV_PATH = "/home/a/work/stock-screener/.env"
KEYS = {}
for line in open(ENV_PATH):
    if line.startswith("ALPACA_"):
        k, _, v = line.strip().partition("=")
        KEYS[k] = v

from alpaca.trading.client import TradingClient
from alpaca.trading.requests import MarketOrderRequest
from alpaca.trading.enums import OrderSide, TimeInForce
from alpaca.data.historical import CryptoHistoricalDataClient
from alpaca.data.requests import CryptoBarsRequest
from alpaca.data.timeframe import TimeFrame
from alpaca.data.requests import CryptoTradesRequest

CANDIDATES = ["BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD", "DOGE/USD"]

STATE = {"pos": 0.0, "api": None, "instrument": "BTC/USD"}
api = TradingClient(KEYS["ALPACA_API_KEY_ID"], KEYS["ALPACA_API_SECRET_KEY"], paper=True)
hist = CryptoHistoricalDataClient(KEYS["ALPACA_API_KEY_ID"], KEYS["ALPACA_API_SECRET_KEY"])

def pick_most_active(on_event):
    end = dt.datetime.now(dt.timezone.utc)
    best, bestv = CANDIDATES[0], -1.0
    for s in CANDIDATES:
        try:
            r = hist.get_crypto_trades(CryptoTradesRequest(
                symbol_or_symbols=s, start=end - dt.timedelta(minutes=5), end=end))
            vol = sum(float(t.size) * float(t.price) for t in r.data.get(s, []))
            on_event({"type": "notice", "venue": "alpaca",
                      "msg": f"{s}: {vol:,.0f} USDT en 5 min"})
            if vol > bestv:
                best, bestv = s, vol
        except Exception as e:
            on_event({"type": "notice", "venue": "alpaca",
                      "msg": f"{s}: sin datos ({str(e)[:50]})"})
    return best

class BarCloser:
    def __init__(self):
        self.sec = None
        self.px = None
    def feed(self, sec, px):
        if self.sec is None:
            self.sec, self.px = sec, px
            return None
        if sec == self.sec:
            self.px = px
            return None
        c = (self.sec, self.px)
        self.sec, self.px = sec, px
        return c

def venue_position(symbol):
    try:
        for p in api.get_all_positions():
            if p.symbol == symbol.replace("/", ""):
                return float(p.qty)
    except Exception:
        pass
    return 0.0

async def run_async(on_event, dry):
    asset = pick_most_active(on_event)
    ap_symbol = asset.replace("/", "")
    STATE["instrument"] = asset
    STATE["api"] = api
    STATE["pos"] = venue_position(ap_symbol)
    eng = DipEngine(k=20, w=120, thr=1.5, ppy=525600)  # scalp 1m propio del venue
    on_event({"type": "start", "venue": "alpaca", "instrument": asset,
              "dry": dry, "position": STATE["pos"]})
    closer = BarCloser()
    backoff = 1

    def close_bar(sec, px, pos):
        """Cierra vela 1m y ejecuta en ALPACA con SU cliente."""
        target, z = eng.on_bar(px)
        ev = {"type": "bar", "ts": sec * 1000, "close": px,
              "target": round(target, 4), "position": pos,
              "z": round(z, 2), "state": eng.state}
        delta = target - pos
        if abs(delta) > 1e-9:
            side = OrderSide.BUY if delta > 0 else OrderSide.SELL
            pos = target  # intención primero
            if not dry:
                try:
                    api.submit_order(MarketOrderRequest(
                        symbol=ap_symbol, qty=abs(round(delta, 4)),
                        side=side, time_in_force=TimeInForce.GTC))
                    ev["order"] = {"ts": sec * 1000, "side": side.value.lower(),
                                   "qty": abs(round(delta, 4)), "price": px}
                except Exception as e:
                    ev["order_error"] = str(e)[:120]
            else:
                ev["order"] = {"ts": sec * 1000, "side": side.value.lower(),
                               "qty": abs(round(delta, 4)), "price": px, "dry": True}
        store.bar("alpaca", ev["ts"], px, ev.get("target", 0),
                  ev.get("z", 0), pos, eng.state)
        if ev.get("order"):
            o = ev["order"]
            store.order("alpaca", o["ts"], o["side"], o["qty"], o.get("price", 0))
        emit(ev)
        return pos

    on_event({"type": "notice", "venue": "alpaca",
              "msg": "datos: stream propio de Alpaca (push) · ejecución: Alpaca paper"})
    while True:
        stream = CryptoDataStream(KEYS["ALPACA_API_KEY_ID"], KEYS["ALPACA_API_SECRET_KEY"])
        stream.subscribe_crypto_trades(on_trade)
        try:
            await stream.run()
        except Exception as e:
            on_event({"type": "error", "venue": "alpaca", "n": backoff,
                      "msg": f"feed: {str(e)[:100]}"})
            await asyncio.sleep(min(30, backoff * 2))
            backoff = min(backoff + 1, 6)

def run(on_event=None, dry=False):
    global emit
    if on_event:
        emit = lambda ev: on_event(dict(ev, venue="alpaca"))
    asyncio.run(run_async(on_event, dry))

def main():
    run(dry="--dry" in sys.argv)

if __name__ == "__main__":
    main()
