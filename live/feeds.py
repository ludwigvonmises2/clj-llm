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
import asyncio, datetime, json, time

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


# ---- OKX: perps + spot en UNA conexión. Sin ping de texto el socket
# muere en ~30 s (protocolo propio, no estándar WS) — pinger cada 20 s.
OKX_WS = "wss://ws.okx.com:8443/ws/v5/public"
OKX_SYMBOLS = ["BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP",
               "BTC-USDT", "ETH-USDT"]

async def okx_feed(_, on_event, stop):
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect(OKX_WS, heartbeat=None) as ws:
            args = [{"channel": "trades", "instId": i} for i in OKX_SYMBOLS]
            args += [{"channel": "books5", "instId": "BTC-USDT-SWAP"},
                     {"channel": "books5", "instId": "BTC-USDT"}]
            await ws.send_str(json.dumps({"op": "subscribe", "args": args}))
            on_event({"type": "notice", "venue": "okx",
                      "msg": f"perps+spot: {len(OKX_SYMBOLS)} instrumentos"})

            async def pinger():
                while not stop.is_set():
                    await ws.send_str("ping")
                    await asyncio.sleep(20)
            asyncio.get_event_loop().create_task(pinger())
            async for msg in ws:
                if msg.type != aiohttp.WSMsgType.TEXT:
                    if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                        raise ConnectionError("ws cerrado")
                    continue
                if stop.is_set():
                    return
                try:
                    m = json.loads(msg.data)
                    if m == "pong" or "event" in m:
                        continue
                    ch, rows = m["arg"]["channel"], m.get("data") or []
                    inst = m["arg"]["instId"].lower()
                    for r in rows:
                        if ch == "trades":
                            on_event({"type": "print", "venue": f"okx:{inst}",
                                      "sec": int(r["ts"]) // 1000,
                                      "px": float(r["px"])})
                        elif ch == "books5":
                            on_event({"type": "quote", "venue": f"okx:{inst}",
                                      "bid": float(r["bids"][0][0]),
                                      "ask": float(r["asks"][0][0])})
                except Exception:
                    pass


# ---- Bybit perps lineales (v5): trade + libro 1 nivel. Ping por op. ----
BYBIT_WS = "wss://stream.bybit.com/v5/public/linear"
BYBIT_SYMBOLS = ["BTCUSDT", "ETHUSDT", "SOLUSDT", "XRPUSDT", "DOGEUSDT"]

async def bybit_feed(_, on_event, stop):
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect(BYBIT_WS, heartbeat=None) as ws:
            args = [f"publicTrade.{i}" for i in BYBIT_SYMBOLS]
            args += [f"orderbook.1.{i}" for i in BYBIT_SYMBOLS[:2]]
            await ws.send_str(json.dumps({"op": "subscribe", "args": args}))
            on_event({"type": "notice", "venue": "bybit",
                      "msg": f"perps: {len(BYBIT_SYMBOLS)} símbolos"})

            async def pinger():
                while not stop.is_set():
                    await ws.send_str(json.dumps({"op": "ping"}))
                    await asyncio.sleep(20)
            asyncio.get_event_loop().create_task(pinger())
            async for msg in ws:
                if msg.type != aiohttp.WSMsgType.TEXT:
                    if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                        raise ConnectionError("ws cerrado")
                    continue
                if stop.is_set():
                    return
                try:
                    m = json.loads(msg.data)
                    topic, rows = m.get("topic", ""), m.get("data") or []
                    if topic.startswith("publicTrade."):
                        sym = topic.split(".")[1].lower()
                        for r in rows:
                            on_event({"type": "print", "venue": f"bybit:{sym}",
                                      "sec": int(r["T"]) // 1000,
                                      "px": float(r["p"])})
                    elif topic.startswith("orderbook.1."):
                        sym = topic.split(".")[2].lower()
                        on_event({"type": "quote", "venue": f"bybit:{sym}",
                                  "bid": float(rows[0][0]),
                                  "ask": float(rows[-1][0])})
                except Exception:
                    pass


# ---- Coinbase spot (el venue US más regulado): matches. ----
COINBASE_WS = "wss://ws-feed.exchange.coinbase.com"
COINBASE_PRODUCTS = ["BTC-USD", "ETH-USD", "SOL-USD", "XRP-USD", "DOGE-USD"]

async def coinbase_feed(_, on_event, stop):
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect(COINBASE_WS, heartbeat=20) as ws:
            await ws.send_str(json.dumps({
                "type": "subscribe", "product_ids": COINBASE_PRODUCTS,
                "channels": ["matches"]}))
            on_event({"type": "notice", "venue": "coinbase",
                      "msg": f"spot: {len(COINBASE_PRODUCTS)} productos"})
            async for msg in ws:
                if msg.type != aiohttp.WSMsgType.TEXT:
                    if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                        raise ConnectionError("ws cerrado")
                    continue
                if stop.is_set():
                    return
                try:
                    r = json.loads(msg.data)
                    if r.get("type") == "match":
                        on_event({"type": "print",
                                  "venue": "coinbase:" + r["product_id"].lower(),
                                  "sec": int(datetime.datetime
                                             .fromisoformat(r["time"].replace("Z", "+00:00"))
                                             .timestamp()),
                                  "px": float(r["price"])})
                except Exception:
                    pass


# ---- Kraken spot (v2): trade + heartbeat propio del canal. ----
KRAKEN_WS = "wss://ws.kraken.com/v2"
KRAKEN_SYMBOLS = ["BTC/USD", "ETH/USD", "SOL/USD", "XRP/USD"]

async def kraken_feed(_, on_event, stop):
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect(KRAKEN_WS, heartbeat=20) as ws:
            await ws.send_str(json.dumps({
                "method": "subscribe",
                "params": {"channel": "trade", "symbol": KRAKEN_SYMBOLS}}))
            on_event({"type": "notice", "venue": "kraken",
                      "msg": f"spot: {len(KRAKEN_SYMBOLS)} pares"})
            async for msg in ws:
                if msg.type != aiohttp.WSMsgType.TEXT:
                    if msg.type in (aiohttp.WSMsgType.CLOSED, aiohttp.WSMsgType.ERROR):
                        raise ConnectionError("ws cerrado")
                    continue
                if stop.is_set():
                    return
                try:
                    m = json.loads(msg.data)
                    for r in (m.get("data") or []):
                        if m.get("channel") == "trade":
                            sym = r["symbol"].lower().replace("/", "-")
                            on_event({"type": "print", "venue": f"kraken:{sym}",
                                      "sec": int(r["timestamp"]) // 1000,
                                      "px": float(r["price"])})
                except Exception:
                    pass


FEEDS.update({"okx": okx_feed, "bybit": bybit_feed,
              "coinbase": coinbase_feed, "kraken": kraken_feed})

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
