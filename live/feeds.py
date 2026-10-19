#!/usr/bin/env python3
"""Venue feed adapters — the SINGLE owner of every venue WebSocket.

One connection per venue for the WHOLE system: the desk runs these
adapters, normalizes their events, and relays them to every consumer
(the Clojure brain, the dashboard) over its own broadcast. A brain
restart never drops a venue stream; rate limits are consumed once.

Each adapter implements the same contract:
    run(on_event, stop)  — reconnecting loop, emits normalized dicts:
        {"type": "print", "venue": ..., "sec": int, "px": float}
        {"type": "quote", "venue": ..., "bid": float, "ask": float}
"""
import asyncio, json, time

import aiohttp

GRVT_WS = "wss://market-data.testnet.grvt.io/ws/full"

async def grvt_feed(instrument, on_event, stop):
    """GRVT prints + mini-book, JSON-RPC dialect measured in f-trader."""
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect(GRVT_WS, heartbeat=10) as ws:
            # SOLO v1.trade: una suscripción con selector inválido
            # (p.ej. mini.s con rate fuera de la escalera) devuelve el
            # error 3030 y SILENCIA todo el socket — medido. El spread
            # se añadirá en una conexión aparte cuando se necesite.
            for i, (stream, sel) in enumerate([
                    ("v1.trade", f"{instrument}@50")]):
                await ws.send_str(json.dumps({
                    "jsonrpc": "2.0", "method": "subscribe", "id": f"s{i}",
                    "params": {"stream": stream, "selectors": [sel]}}))
            on_event({"type": "notice", "venue": "grvt",
                      "msg": "feed propio suscrito (prints + mini book)"})
            quote = {"bid": None, "ask": None}
            async for msg in ws:
                if msg.type != aiohttp.WSMsgType.TEXT:
                    if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                        raise ConnectionError("ws cerrado")
                    continue
                if stop.is_set():
                    return
                try:
                    m = json.loads(msg.data)
                except Exception:
                    continue
                stream = m.get("stream")
                trade = m.get("feed")
                with open("/tmp/feed_heartbeat", "w") as hf:
                    hf.write(str(int(time.time())))
                if stream == "v1.trade" and trade:
                    with open("/tmp/feed_events.log", "a") as ef:
                        ef.write(json.dumps({"type": "print", "venue": "grvt",
                                             "sec": int(trade["event_time"]) // 1_000_000_000,
                                             "px": float(trade["price"])}) + "\n")
                if stream == "v1.trade" and trade:
                    # f-trader GrvtMarketData: sequence_number "0" = snapshot
                    # (los 50 últimos prints al suscribir y en CADA
                    # resuscripción) — descartar siempre o inyectamos
                    # volumen pasado como presente
                    if str(trade.get("sequence_number")) == "0":
                        continue
                    try:
                        px = float(trade["price"])
                        sec = int(trade["event_time"]) // 1_000_000_000
                        ev = {"type": "print", "venue": "grvt", "sec": sec, "px": px}
                        if quote["bid"]:
                            ev["bid"], ev["ask"] = quote["bid"], quote["ask"]
                        on_event(ev)
                    except Exception:
                        pass


# ---- feed combinado de Binance: N símbolos en UNA conexión (push, sin
# cuota) — la fuente de las cintas del universo entero ----
BINANCE_WS = "wss://stream.binance.com:9443/stream"
BINANCE_SYMBOLS = ["btcusdt", "ethusdt", "solusdt", "xrpusdt", "dogeusdt",
                   "bnbusdt", "adausdt", "linkusdt", "ltcusdt", "dotusdt"]

async def binance_feed(_, on_event, stop):
    streams = "/".join(f"{s}@trade" for s in BINANCE_SYMBOLS)
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect(f"{BINANCE_WS}?streams={streams}", heartbeat=20) as ws:
            on_event({"type": "notice", "venue": "binance-v",
                      "msg": f"cinta combinada de {len(BINANCE_SYMBOLS)} símbolos (1 conexión)"})
            async for msg in ws:
                if msg.type != aiohttp.WSMsgType.TEXT:
                    if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                        raise ConnectionError("ws cerrado")
                    continue
                if stop.is_set():
                    return
                try:
                    d = json.loads(msg.data)
                    t = d.get("data") or d
                    if "p" in t:
                        px = float(t["p"])
                        sec = int(t["T"]) // 1000
                        sym = t["s"].lower()
                        on_event({"type": "print", "venue": f"binance:{sym}",
                                  "sec": sec, "px": px})
                except Exception:
                    pass

FEEDS = {"grvt": grvt_feed, "binance-all": binance_feed}

async def run_feed(venue, instrument, on_event, stop):
    """Reconnecting wrapper: a feed that stays down is not an option."""
    backoff = 1
    while not stop.is_set():
        try:
            await FEEDS[venue](instrument, on_event, stop)
            backoff = 1
        except Exception as e:
            on_event({"type": "error", "venue": venue,
                      "msg": f"feed: {str(e)[:100]}"})
            await asyncio.sleep(min(30, backoff * 2))
            backoff = min(backoff + 1, 6)
