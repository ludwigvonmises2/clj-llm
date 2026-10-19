#!/usr/bin/env python3
"""Live desk — ONE Python process: aiohttp server + both real engines
(GRVT testnet, Alpaca paper) as threads. The engines are the same
modules the CLI uses; here their emit() is redirected into the WebSocket
broadcast. The desk's only command is FLATTEN (per venue), executed with
each engine's own SDK session."""
import asyncio, json, os, sys, threading

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from aiohttp import web, WSMsgType
import grvt_live, alpaca_live
import store

clients = set()
loop = None  # main asyncio loop, set at startup

# THE VENUE REGISTRY: an exchange is a module exposing
# run(on_event, dry) + STATE{pos,api,instrument}. Adding one is a new
# module here + a line — no server changes anywhere else.
VENUES = [
    {"id": "grvt",   "module": "grvt_live",   "label": "GRVT · BTC testnet"},
    {"id": "alpaca", "module": "alpaca_live", "label": "Alpaca · BTC paper"},
]

def broadcast(ev):
    ev = dict(ev)
    ev.setdefault("venue", "sistema")
    msg = json.dumps(ev, default=str)
    async def push():
        dead = []
        for ws in clients:
            try: await ws.send_str(msg)
            except Exception: dead.append(ws)
        for ws in dead: clients.discard(ws)
    asyncio.run_coroutine_threadsafe(push(), loop)

def start_engine(venue_id, mod, dry=False):
    def worker():
        try: mod.run(on_event=lambda ev: broadcast(dict(ev, venue=venue_id)), dry=dry)
        except Exception as e:
            broadcast({"type": "notice", "venue": venue_id,
                       "msg": f"{mod.__name__} murió: {e}"})
    threading.Thread(target=worker, daemon=True).start()

async def flatten(request):
    venue = request.match_info["venue"]
    v = next((v for v in VENUES if v["id"] == venue), None)
    if not v: return web.json_response({"ok": False, "msg": "venue desconocido"})
    api, inst, pos = v["state"].get("api"), v["state"].get("instrument"), v["state"].get("pos", 0.0)
    ok, msg = False, "engine no disponible"
    if api is not None and pos and pos > 0:
        try:
            if venue == "grvt":
                api.create_order(inst, "market", "sell", pos)
            else:
                from alpaca.trading.requests import MarketOrderRequest
                from alpaca.trading.enums import OrderSide, TimeInForce
                api.submit_order(MarketOrderRequest(
                    symbol=inst.replace("/", ""), qty=pos, side=OrderSide.SELL,
                    time_in_force=TimeInForce.GTC))
            ok, msg = True, f"flatten {pos} {inst} enviado"
            v["state"]["pos"] = 0.0
        except Exception as e:
            msg = f"flatten falló: {e}"
    elif pos == 0:
        ok, msg = True, "posición ya plana"
    broadcast({"type": "notice", "venue": venue, "msg": msg})
    return web.json_response({"ok": ok, "msg": msg})

async def ws_handler(request):
    ws = web.WebSocketResponse()
    await ws.prepare(request)
    clients.add(ws)
    # session memory: the new client sees the session so far, not a void
    for v in VENUES:
        venue = v["id"]
        for ts, close, target, z, position, state in store.recent_bars(venue):
            await ws.send_str(json.dumps({"type": "history", "venue": venue, "ts": ts,
                "close": close, "target": target, "z": z, "position": position, "state": state}))
        for ts, v, side, qty, price in store.recent_orders():
            if v == venue:
                await ws.send_str(json.dumps({"type": "order", "venue": venue, "ts": ts,
                    "side": side, "qty": qty, "price": price}))
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    m = json.loads(msg.data)
                    if m.get("type") == "flatten":
                        request.app.loop.create_task(flatten(request.clone(match_info={"venue": m["venue"]})))
                except Exception:
                    pass
    finally:
        clients.discard(ws)
    return ws

async def index(request):
    return web.FileResponse(os.path.join(HERE, "index.html"))

async def _capture_loop(app):
    global loop
    loop = asyncio.get_running_loop()

def main():
    dry = "--dry" in sys.argv
    app = web.Application()
    app.on_startup.append(_capture_loop)
    app.router.add_get("/", index)
    app.router.add_get("/api/venues", api_venues)
    app.router.add_get("/ws", ws_handler)
    app.router.add_post("/flatten/{venue}", flatten)
    import importlib
    for v in VENUES:
        mod = importlib.import_module(v["module"])
        v["state"] = mod.STATE
        start_engine(v["id"], mod, dry=dry)
    web.run_app(app, port=7777, print=None)

async def api_venues(request):
    out = [{"id": v["id"], "label": v["label"],
            "instrument": v["state"].get("instrument"),
            "position": v["state"].get("pos", 0.0),
            **store.stats(v["id"])} for v in VENUES]
    return web.json_response(out)

if __name__ == "__main__":
    main()
