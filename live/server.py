#!/usr/bin/env python3
"""Live desk — ONE Python process: aiohttp server + both real engines
(GRVT testnet, Alpaca paper) as threads. The engines are the same
modules the CLI uses; here their emit() is redirected into the WebSocket
broadcast. The desk's only command is FLATTEN (per venue), executed with
each engine's own SDK session."""
import asyncio, json, os, sys, threading, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

from aiohttp import web, WSMsgType
import alpaca_live
import executors
import store

clients = set()
loop = None  # main asyncio loop, set at startup

# THE VENUE REGISTRY: an exchange is a module exposing
# run(on_event, dry) + STATE{pos,api,instrument}. Adding one is a new
# module here + a line — no server changes anywhere else.
VENUES = [
    {"id": "grvt",     "label": "GRVT · BTC testnet REAL", "brain": "clojure",
     "feed": "BTC_USDT_Perp"},
    {"id": "alpaca",   "module": "alpaca_live", "label": "Alpaca · BTC paper REAL", "brain": "python"},
    {"id": "binance-v","executor": "binance:btcusdt", "instrument": "BTCUSDT",
     "label": "Binance · virtual ULTRA"},
    {"id": "okx-v",    "executor": "okx:btc-usdt-swap", "instrument": "BTC-USDT-SWAP",
     "label": "OKX · perp virtual ULTRA (L/S)"},
    {"id": "okx",      "feed": "BTC-USDT-SWAP", "label": "OKX · datos perp+spot"},
    {"id": "bybit",    "feed": "BTCUSDT", "label": "Bybit · datos perps"},
    {"id": "coinbase", "feed": "BTC-USD", "label": "Coinbase · datos spot"},
    {"id": "kraken",   "feed": "BTC/USD", "label": "Kraken · datos spot"},
]

pre_loop_queue = []

def broadcast(ev):
    ev = dict(ev)
    ev.setdefault("venue", "sistema")
    # las cintas las escribe UN dueño: el recorder (capa 0). El desk
    # solo retransmite: UI, cerebro y venues virtuales por relé interno.
    import relay
    relay.publish(ev)
    if loop is None:
        pre_loop_queue.append(ev)   # se difunden en cuanto exista el loop
        return
    asyncio.run_coroutine_threadsafe(_push(json.dumps(ev, default=str)), loop)

def start_engine(venue_id, mod, dry=False):
    def worker():
        while True:                # un engine muerto NO es opción en un desk
            try:
                mod.run(on_event=lambda ev: broadcast(dict(ev, venue=venue_id)),
                        dry=dry)
            except Exception as e:
                broadcast({"type": "notice", "venue": venue_id,
                           "msg": f"{mod.__name__} reinicia: {e}"})
            time.sleep(3)
    threading.Thread(target=worker, daemon=True).start()

async def flatten(request):
    venue = request.match_info["venue"]
    v = next((v for v in VENUES if v["id"] == venue), None)
    if not v: return web.json_response({"ok": False, "msg": "venue desconocido"})
    # venues virtuales: flatten long o short vía el ejecutor
    if v.get("executor"):
        st = v["state"]; pos = st["pos"]
        if abs(pos) > 1e-9:
            r = executors.execute(st, "sell" if pos > 0 else "buy",
                                  abs(pos), why="flatten")
            ok, msg = r.get("ok", False), f"flatten {pos} enviado"
        else:
            ok, msg = True, "posición ya plana"
        broadcast({"type": "notice", "venue": venue, "msg": msg})
        return web.json_response({"ok": ok, "msg": msg})
    api = (v.get("state") or {}).get("api")
    inst = (v.get("state") or {}).get("instrument")
    pos = (v.get("state") or {}).get("pos", 0.0)
    ok, msg = False, "engine no disponible"
    if api is not None and pos and pos > 0:
        try:
            if venue == "grvt":
                api.create_order(inst, "market", "sell", pos)
            else:
                from alpaca.trading.requests import MarketOrderRequest, GetOrdersRequest
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

async def place_order(request):
    """Execution gateway: the Clojure brain decides, this signs/fills."""
    venue = request.match_info["venue"]
    body = await request.json()
    v = next((v for v in VENUES if v["id"] == venue), None)
    if not v: return web.json_response({"ok": False, "msg": "venue desconocido"})
    side = body.get("side"); qty = float(body.get("qty") or 0)
    why = body.get("why", "signal")
    # venue virtual: el ejecutor ULTRA hace el fill (largo o corto)
    if v.get("executor"):
        import executors
        r = executors.execute(v["state"], side, qty, why=why)
        if r.get("ok"):
            store.order(venue, int(time.time() * 1000), side, qty,
                        r["avg_px"], f"{venue}-{int(time.time() * 1000)}",
                        z=body.get("z"), target=body.get("target"),
                        spread=body.get("spread"), why=why)
            return web.json_response({"ok": True,
                                      "avg_px": round(r["avg_px"], 2),
                                      "fee": round(r["fee"], 3),
                                      "slippage_bps": round(r["slippage_bps"], 2),
                                      "pos": round(r["pos"], 6)})
        return web.json_response({"ok": False, "msg": r.get("msg", "fill falló")})
    inst = body.get("instrument") or v["state"].get("instrument")
    api = (v.get("state") or {}).get("api")
    if api is None: return web.json_response({"ok": False, "msg": "engine no disponible"})
    try:
        if venue == "grvt":
            r = api.create_order(inst, "market", side, qty)
            resp = {"order_id": str(r.get("order_id", r))[:40]}
        else:
            from alpaca.trading.requests import MarketOrderRequest, GetOrdersRequest
            from alpaca.trading.enums import OrderSide, TimeInForce
            o = api.submit_order(MarketOrderRequest(
                symbol=inst.replace("/", ""), qty=qty,
                side=OrderSide.BUY if side == "buy" else OrderSide.SELL,
                time_in_force=TimeInForce.GTC))
            resp = {"order_id": str(o.id)[:40]}
        store.order(venue, int(time.time() * 1000), side, qty, 0.0,
                    resp.get("order_id"), z=body.get("z"),
                    target=body.get("target"), spread=body.get("spread"),
                    why=body.get("why", "scalp"))
        return web.json_response({"ok": True, **resp})
    except Exception as e:
        return web.json_response({"ok": False, "msg": str(e)[:160]})

async def open_orders(request):
    venue = request.match_info["venue"]
    v = next((v for v in VENUES if v["id"] == venue), None)
    if not v: return web.json_response([])
    api = (v.get("state") or {}).get("api")
    if api is None: return web.json_response([])
    try:
        if venue == "grvt":
            r = api.post("/full/v1/open_orders", {})
            rows = (r.get("result") or {}).get("open_orders") or []
        else:
            rows = [ {"symbol": o.symbol, "qty": str(o.qty), "side": str(o.side),
                      "id": str(o.id)[:12]}
                     for o in api.list_orders(GetOrdersRequest(status="open")) ]
        return web.json_response(rows)
    except Exception as e:
        return web.json_response({"error": str(e)[:120]})

async def trade_history(request):
    venue = request.match_info["venue"]
    rows = store.conn().execute(
        "SELECT ts, side, qty, price, resp FROM orders WHERE venue=? ORDER BY ts DESC LIMIT 50",
        (venue,)).fetchall()
    return web.json_response([{"ts": t, "side": s, "qty": q, "price": p, "id": r}
                              for t, s, q, p, r in rows])

async def venue_position(request):
    """THE truth: the venue's own position, via the SDK."""
    venue = request.match_info["venue"]
    v = next((v for v in VENUES if v["id"] == venue), None)
    if not v or (v.get("state") or {}).get("api") is None:
        return web.json_response({"position": None})
    try:
        if venue == "grvt":
            rows = v["state"]["api"].fetch_positions()
            pos = next((float(p["size"]) for p in rows
                        if p.get("instrument") == v["state"].get("instrument")), 0.0)
        else:
            inst = v["state"].get("instrument", "")
            pos = next((float(p.qty) for p in v["state"]["api"].get_all_positions()
                        if p.symbol == inst.replace("/", "")), 0.0)
        v["state"]["pos"] = pos
        return web.json_response({"position": pos, "instrument": v["state"].get("instrument")})
    except Exception as e:
        return web.json_response({"position": None, "error": str(e)[:120]})

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

async def libcharts(request):
    return web.FileResponse(os.path.join(HERE, "lib.charts.js"),
                            headers={"content-type": "application/javascript"})

async def _capture_loop(app):
    global loop
    loop = asyncio.get_running_loop()
    # drenar la cola pre-loop POR EL LOOP: los hilos nunca tocan aiohttp
    for ev2 in pre_loop_queue:
        await _push(dict(ev2, replay=True))
    pre_loop_queue.clear()

async def _push(msg):
    dead = []
    for ws in clients:
        try: await ws.send_str(msg)
        except Exception: dead.append(ws)
    for ws in dead: clients.discard(ws)

def feed_thread(venue_id, feed_spec):
    """Un hilo por feed: el ÚNICO suscriptor del WS de ese venue."""
    loop2 = asyncio.new_event_loop()
    asyncio.set_event_loop(loop2)
    import feeds
    stop = asyncio.Event()
    loop2.run_until_complete(feeds.run_feed(venue_id, feed_spec,
                                            broadcast, stop))

def main():
    dry = "--dry" in sys.argv
    app = web.Application()
    app.on_startup.append(_capture_loop)
    app.router.add_get("/", index)
    app.router.add_get("/lib.charts.js", libcharts)
    app.router.add_get("/api/venues", api_venues)
    app.router.add_get("/api/health", health)
    app.router.add_get("/ws", ws_handler)
    app.router.add_post("/flatten/{venue}", flatten)
    app.router.add_post("/order/{venue}", place_order)
    app.router.add_get("/api/open-orders/{venue}", open_orders)
    app.router.add_get("/api/trades/{venue}", trade_history)
    app.router.add_get("/api/position/{venue}", venue_position)
    app.router.add_post("/api/position/{venue}", venue_position)
    import importlib
    clojure_ids = {v["id"] for v in VENUES if v.get("brain") == "clojure"}
    for v in VENUES:
        if v["id"] in clojure_ids:
            # sesión SDK para que la PASARELA firme las órdenes que el
            # cerebro Clojure decide (el cerebro nunca firma)
            from grvt_sdk_order import client as grvt_client
            v["state"] = {"pos": 0.0, "api": grvt_client(),
                          "instrument": "BTC_USDT_Perp"}
            continue
        if v.get("module"):
            mod = importlib.import_module(v["module"])
            v["state"] = mod.STATE
            start_engine(v["id"], mod, dry=dry)
    # venues virtuales: solo EJECUTAN (la decisión vive en el cerebro)
    import executors
    for v in VENUES:
        if v.get("executor"):
            st = executors.make_state(v["instrument"])
            v["state"] = st
            executors.start_drainer(v["executor"], st)
    for v in VENUES:
        if v.get("feed"):
            threading.Thread(target=feed_thread, daemon=True,
                             args=(v["id"], v["feed"])).start()
    threading.Thread(target=feed_thread, daemon=True,
                     args=("binance-all", "BTCUSDT")).start()
    if clojure_ids:
        def tail():
            p = os.path.join(HERE, "clj_events.jsonl")
            while not os.path.exists(p):
                time.sleep(0.5)
            with open(p) as f:
                f.seek(0, 2)
                while True:
                    line = f.readline()
                    if line.strip():
                        try:
                            ev = json.loads(line)
                            if ev.get("venue") == "clojure":
                                ev["venue"] = "grvt"
                            broadcast(ev)
                        except Exception:
                            pass
                    else:
                        time.sleep(0.3)
        threading.Thread(target=tail, daemon=True).start()
    web.run_app(app, port=7777, print=None)

async def health(request):
    """¿Todo corre? Edad de cada pieza en un vistazo."""
    import time as _t
    hb = None
    try: hb = _t.time() - int(open("/tmp/feed_heartbeat").read())
    except Exception: pass
    last_bar = None
    try:
        import sqlite3
        c = sqlite3.connect("desk.db")
        row = c.execute("SELECT MAX(ts) FROM bars").fetchone()[0]
        last_bar = (_t.time() - row / 1000) if row else None
    except Exception: pass
    return web.json_response({
        "feed_hb_s": hb,
        "last_bar_s": last_bar,
        "learner_log": os.path.exists("/tmp/learning_log.jsonl"),
        "clients_ws": len(clients)})

async def api_venues(request):
    out = []
    for v in VENUES:
        st = v.get("state") or {}
        out.append({"id": v["id"], "label": v["label"],
                    "instrument": st.get("instrument"),
                    "position": st.get("pos", 0.0),
                    **store.stats(v["id"])})
    return web.json_response(out)

if __name__ == "__main__":
    main()
