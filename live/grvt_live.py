#!/usr/bin/env python3
"""GRVT testnet LIVE engine — WebSocket PUSH, tick-fed 1s bars, real orders.

latency   : the old loop polled fetch_trades once a second; on the thin
            testnet prints land every 10-60s. Now we SUBSCRIBE to the
            venue trade stream (wss://market-data.../ws/full, JSON-RPC
            `v1.trade` selector `BTC_USDT_Perp@50`, the exact dialect
            f-trader measured) — every print arrives in milliseconds and
            each 1s bar closes on schedule.
signal    : unchanged validated mr-dip (z = 60s cumulative log return
            over realized vol; long on z < -3, exit z >= 0, 30% ann
            target, 2x cap).
robustness: WS auto-reconnect with capped backoff; warmup from REST
            prints at boot; error budget flattens and stops; intent
            persisted BEFORE the order; venue position seeded at boot.
"""
import sys, json, time, math, asyncio

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import store
import market_feed
from grvt_sdk_order import client

INSTRUMENT = "BTC_USDT_Perp"
WS_URL = "wss://market-data.testnet.grvt.io/ws/full"
K, W, THR = 60, 360, 3.0
PPY = 31536000
TDV = 0.30 / math.sqrt(PPY)
MAX_LEV = 2.0

def emit(ev):
    print(json.dumps(ev, default=str), flush=True)

STATE = {"pos": 0.0, "api": None, "instrument": INSTRUMENT}

class DipEngine:
    """Same reference logic as trade.signals/mr-zscore :long-dip.
    Params configurable per venue/timeframe (defaults: GRVT 1s)."""
    def __init__(self, k=K, w=W, thr=THR, ppy=PPY, target_vol=0.30, max_lev=MAX_LEV):
        self.k, self.w, self.thr = k, w, thr
        self.tdv = target_vol / math.sqrt(ppy)
        self.max_lev = max_lev
        self.closes, self.lrets, self.rets = [], [0.0], [0.0]
        self.state = 0

    def on_bar(self, c):
        n = len(self.closes)
        if n:
            self.lrets.append(math.log(c) - math.log(self.closes[-1]))
            self.rets.append(c / self.closes[-1] - 1)
        self.closes.append(c)
        i = len(self.closes) - 1
        z, rv = 0.0, None
        if i >= self.w:
            w = self.rets[i - self.w + 1:i + 1]
            m = sum(w) / self.w
            sd = math.sqrt(sum((x - m) ** 2 for x in w) / (self.w - 1))
            rv = sd if sd > 0 else None
        if i >= self.k and rv:
            z = sum(self.lrets[i - self.k + 2:i + 2]) / (rv * math.sqrt(self.k))
        if self.state == 0 and z < -self.thr:
            self.state = 1
        elif self.state == 1 and z >= 0:
            self.state = 0
        scale = min(self.tdv / rv, self.max_lev) if rv else 0.0
        return self.state * scale, z

class BarCloser:
    """Aggregates prints into 1s bars; closes a bar when a print from a
    later second arrives (the schedule check in the WS loop also closes
    on wall-clock for quiet seconds)."""
    def __init__(self):
        self.sec = None
        self.px = None

    def feed(self, sec, px):
        """Returns (closed_sec, closed_px) if a bar just completed."""
        if self.sec is None:
            self.sec, self.px = sec, px
            return None
        if sec == self.sec:
            self.px = px
            return None
        closed = (self.sec, self.px)
        self.sec, self.px = sec, px
        return closed

    def force_close(self):
        if self.sec is None:
            return None
        c = (self.sec, self.px)
        self.sec, self.px = None, None
        return c

def close_and_maybe_trade(eng, sec, px, pos, dry, on_event):
    target, z = eng.on_bar(px)
    ev = {"type": "bar", "ts": sec * 1000, "close": px,
          "target": round(target, 4), "position": pos,
          "z": round(z, 2), "state": eng.state}
    delta = target - pos
    if abs(delta) > 1e-9:
        side = "buy" if delta > 0 else "sell"
        pos = target  # persist intent BEFORE the order
        STATE["pos"] = pos
        json.dump({INSTRUMENT: pos},
                  open(__file__.rsplit("/", 1)[0] + "/grvt_state.json", "w"))
        if dry:
            ev["order"] = {"ts": sec * 1000, "side": side,
                           "qty": abs(round(delta, 4)), "price": px}
        else:
            try:
                api = STATE["api"] or client()
                STATE["api"] = api
                api.create_order(INSTRUMENT, "market", side, abs(round(delta, 4)))
                ev["order"] = {"ts": sec * 1000, "side": side,
                               "qty": abs(round(delta, 4)), "price": px}
            except Exception as e:
                ev["order_error"] = str(e)[:120]
    store.bar("grvt", ev["ts"], px, ev.get("target", 0), ev.get("z", 0), pos, eng.state)
    if ev.get("order"):
        o = ev["order"]
        store.order("grvt", o["ts"], o["side"], o["qty"], o.get("price", 0))
    on_event(ev)
    return pos

async def ws_stream(eng, pos, dry, on_event):
    import aiohttp
    closer = BarCloser()
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect(WS_URL, heartbeat=20) as ws:
            await ws.send_str(json.dumps({
                "jsonrpc": "2.0", "method": "subscribe", "id": 1,
                "params": {"stream": "v1.trade",
                           "selectors": [f"{INSTRUMENT}@50"]}}))
            on_event({"type": "notice", "venue": "grvt", "msg": "stream v1.trade suscrito (push)"})
            async for msg in ws:
                if msg.type != aiohttp.WSMsgType.TEXT:
                    if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                        raise ConnectionError("stream cerrado")
                    continue
                try:
                    m = json.loads(msg.data)
                except Exception:
                    continue
                data = (m.get("params") or {}).get("data") or []
                for t in data:
                    try:
                        px = float(t["price"])
                        sec = int(t["event_time"]) // 1_000_000_000
                    except Exception:
                        continue
                    closed = closer.feed(sec, px)
                    if closed:
                        pos = close_and_maybe_trade(eng, closed[0], closed[1],
                                                    pos, dry, on_event)
                        STATE["pos"] = pos

async def run_async(on_event, dry):
    api = client()
    STATE["api"] = api
    pos = 0.0
    try:
        for p in api.fetch_positions():
            if p.get("instrument") == INSTRUMENT:
                pos = float(p.get("size") or 0)
    except Exception as e:
        on_event({"type": "notice", "venue": "grvt", "msg": f"seed: {str(e)[:80]}"})
    STATE["pos"] = pos
    eng = DipEngine()
    try:
        tr = api.fetch_trades(INSTRUMENT, limit=500)
        trades = tr.get("result", tr) if isinstance(tr, dict) else tr
        buckets = {}
        for t in trades:
            sec = int(t["event_time"]) // 1_000_000_000
            buckets[sec] = float(t["price"])
        for sec in sorted(buckets):
            eng.on_bar(buckets[sec])
        on_event({"type": "notice", "venue": "grvt",
                  "msg": f"warmup {len(buckets)} velas 1s"})
    except Exception as e:
        on_event({"type": "notice", "venue": "grvt", "msg": f"warmup vacío: {str(e)[:80]}"})
    on_event({"type": "start", "venue": "grvt", "instrument": INSTRUMENT,
              "dry": dry, "position": pos})
    on_event({"type": "notice", "venue": "grvt",
              "msg": "datos: Binance producción (push) · ejecución: GRVT testnet"})
    backoff = 1
    while True:
        try:
            async for sec, px in market_feed.measured_closes("BTCUSDT"):
                pos = close_and_maybe_trade(eng, sec, px, STATE["pos"], dry, on_event)
                STATE["pos"] = pos
        except Exception as e:
            on_event({"type": "error", "venue": "grvt", "n": backoff,
                      "msg": f"feed: {str(e)[:100]}"})
            await asyncio.sleep(min(30, backoff * 2))
            backoff = min(backoff + 1, 6)

def run(on_event=None, dry=False):
    global emit
    if on_event:
        emit = lambda ev: on_event(dict(ev, venue="grvt"))
    asyncio.run(run_async(on_event, dry))

def main():
    run(dry="--dry" in sys.argv)

if __name__ == "__main__":
    main()
