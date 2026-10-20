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
    # UN ejecutor virtual por venue×activo: la decisión sigue en el
    # cerebro Clojure; aquí solo fills ULTRA. Spot = solo-long (el
    # venue real no permite short); perps = long/short.
    {"id": "binance-v", "executor": "binance:btcusdt", "instrument": "BTCUSDT", "label": "BIN · BTC perp L/S", "fee_bps": 5.0, "funding": True},
    {"id": "binance-eth-v", "executor": "binance:ethusdt", "instrument": "ETHUSDT", "label": "BIN · ETH perp L/S", "fee_bps": 5.0, "funding": True},
    {"id": "binance-sol-v", "executor": "binance:solusdt", "instrument": "SOLUSDT", "label": "BIN · SOL perp L/S", "fee_bps": 5.0, "funding": True},
    {"id": "okx-v",    "executor": "okx:btc-usdt-swap", "instrument": "BTC-USDT-SWAP", "fee_bps": 5.0, "funding": True, "label": "OKX · BTC perp L/S"},
    {"id": "okx-eth-v","executor": "okx:eth-usdt-swap", "instrument": "ETH-USDT-SWAP", "label": "OKX · ETH perp L/S", "fee_bps": 5.0, "funding": True},
    {"id": "okx-sol-v","executor": "okx:sol-usdt-swap", "instrument": "SOL-USDT-SWAP", "label": "OKX · SOL perp L/S", "fee_bps": 5.0, "funding": True},
    {"id": "bybit-v",  "executor": "bybit:btcusdt",  "instrument": "BTCUSDT", "fee_bps": 5.5, "funding": True, "label": "BYB · BTC perp L/S"},
    {"id": "bybit-eth-v","executor": "bybit:ethusdt","instrument": "ETHUSDT", "label": "BYB · ETH perp L/S", "fee_bps": 5.5, "funding": True},
    {"id": "bybit-sol-v","executor": "bybit:solusdt","instrument": "SOLUSDT", "label": "BYB · SOL perp L/S", "fee_bps": 5.5, "funding": True},
    {"id": "bybit-xrp-v","executor": "bybit:xrpusdt","instrument": "XRPUSDT", "label": "BYB · XRP perp L/S", "fee_bps": 5.5, "funding": True},
    {"id": "bybit-doge-v","executor": "bybit:dogeusdt","instrument": "DOGEUSDT", "label": "BYB · DOGE perp L/S", "fee_bps": 5.5, "funding": True},
    {"id": "cbse-v",    "executor": "coinbase:btc-usd", "instrument": "BTC-USD", "long_only": True, "fee_bps": 8.0, "label": "CBS · BTC spot L"},
    {"id": "cbse-eth-v","executor": "coinbase:eth-usd", "instrument": "ETH-USD", "long_only": True, "label": "CBS · ETH spot L", "fee_bps": 8.0, "funding": False},
    {"id": "cbse-sol-v","executor": "coinbase:sol-usd", "instrument": "SOL-USD", "long_only": True, "label": "CBS · SOL spot L", "fee_bps": 8.0, "funding": False},
    {"id": "cbse-xrp-v","executor": "coinbase:xrp-usd", "instrument": "XRP-USD", "long_only": True, "label": "CBS · XRP spot L", "fee_bps": 8.0, "funding": False},
    {"id": "krkn-v",    "executor": "kraken:btc-usd", "instrument": "BTC-USD", "long_only": True, "fee_bps": 26.0, "label": "KRK · BTC spot L"},
    {"id": "krkn-eth-v","executor": "kraken:eth-usd", "instrument": "ETH-USD", "long_only": True, "label": "KRK · ETH spot L", "fee_bps": 26.0, "funding": False},
    {"id": "krkn-sol-v","executor": "kraken:sol-usd", "instrument": "SOL-USD", "long_only": True, "label": "KRK · SOL spot L", "fee_bps": 26.0, "funding": False},
    {"id": "krkn-xrp-v","executor": "kraken:xrp-usd", "instrument": "XRP-USD", "long_only": True, "label": "KRK · XRP spot L", "fee_bps": 26.0, "funding": False},
    {"id": "hl-v",     "executor": "hl:btc", "instrument": "BTC", "fee_bps": 4.5, "funding": True, "label": "HL · BTC perp L/S"},
    {"id": "hl-eth-v", "executor": "hl:eth", "instrument": "ETH", "fee_bps": 4.5, "funding": True, "label": "HL · ETH perp L/S"},
    {"id": "hl-sol-v", "executor": "hl:sol", "instrument": "SOL", "fee_bps": 4.5, "funding": True, "label": "HL · SOL perp L/S"},
    {"id": "aster-v",  "executor": "aster:btcusdt", "instrument": "BTCUSDT", "fee_bps": 3.5, "funding": True, "label": "ASTER · BTC perp L/S"},
    {"id": "aster-eth-v","executor": "aster:ethusdt", "instrument": "ETHUSDT", "fee_bps": 3.5, "funding": True, "label": "ASTER · ETH perp L/S"},
    {"id": "aster-sol-v","executor": "aster:solusdt", "instrument": "SOLUSDT", "fee_bps": 3.5, "funding": True, "label": "ASTER · SOL perp L/S"},
    {"id": "okx",      "feed": "BTC-USDT-SWAP", "label": "OKX · datos perp+spot"},
    {"id": "bybit",    "feed": "BTCUSDT", "label": "Bybit · datos perps"},
    {"id": "coinbase", "feed": "BTC-USD", "label": "Coinbase · datos spot"},
    {"id": "kraken",   "feed": "BTC/USD", "label": "Kraken · datos spot"},
]

pre_loop_queue = []

def persist_executor(venue, v):
    """Write-through compartido por TODA mutación de posición virtual:
    fill, flatten — ninguna ruta puede saltárselo. Si la DB falla, el
    fill cuenta (ya ocurrió) pero se alerta: estado DB ≠ vivo."""
    try:
        store.save_executor_state(venue, v["state"])
    except Exception as e:
        print(f"PERSISTENCIA FALLÓ {venue}: {e}", flush=True)

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
    # venues virtuales: flatten long Y short vía el ejecutor
    if v.get("executor"):
        st = v["state"]; pos = st["pos"]
        if abs(pos) > 1e-9:
            r = executors.execute(st, "sell" if pos > 0 else "buy",
                                  abs(pos), why="flatten")
            if r.get("ok"):
                persist_executor(venue, v)
                ok, msg = True, f"flatten {pos} @ {r['avg_px']:.2f}"
            else:
                ok, msg = False, f"flatten falló: {r.get('msg')}"
        else:
            ok, msg = True, "posición ya plana"
        broadcast({"type": "notice", "venue": venue, "msg": msg})
        return web.json_response({"ok": ok, "msg": msg})
    api = (v.get("state") or {}).get("api")
    inst = (v.get("state") or {}).get("instrument")
    pos = (v.get("state") or {}).get("pos", 0.0)
    ok, msg = False, "engine no disponible"
    # corto o largo: el flatten invierte el signo con orden de mercado
    if api is not None and abs(pos) > 1e-9:
        try:
            if venue == "grvt":
                api.create_order(inst, "market",
                                 "sell" if pos > 0 else "buy", abs(pos))
            else:
                from alpaca.trading.requests import MarketOrderRequest, GetOrdersRequest
                from alpaca.trading.enums import OrderSide, TimeInForce
                api.submit_order(MarketOrderRequest(
                    symbol=inst.replace("/", ""), qty=abs(pos),
                    side=OrderSide.SELL if pos > 0 else OrderSide.BUY,
                    time_in_force=TimeInForce.GTC))
            ok, msg = True, f"flatten {pos} {inst} enviado"
            v["state"]["pos"] = 0.0
        except Exception as e:
            msg = f"flatten falló: {e}"
    elif abs(pos) <= 1e-9:
        ok, msg = True, "posición ya plana"
    broadcast({"type": "notice", "venue": venue, "msg": msg})
    return web.json_response({"ok": ok, "msg": msg})

async def place_order(request):
    """Execution gateway: the Clojure brain decides, this signs/fills."""
    venue = request.match_info["venue"]
    body = await request.json()
    v = next((v for v in VENUES if v["id"] == venue), None)
    if not v: return web.json_response({"ok": False, "msg": "venue desconocido"})
    side = body.get("side"); why = body.get("why", "signal")
    try:
        qty = float(body.get("qty") or 0)
    except (TypeError, ValueError):
        return web.json_response({"ok": False, "msg": "qty inválida"})
    import math as _m
    if not (_m.isfinite(qty) and 0 < qty <= 100):
        return web.json_response({"ok": False, "msg": "qty fuera de límites"})
    # FAIL-CLOSED (fallo C2): un side ausente jamás rellena como SELL
    if side not in ("buy", "sell"):
        return web.json_response({"ok": False, "msg": "side requerido"})
    # venue virtual: el ejecutor ULTRA hace el fill (largo o corto)
    if v.get("executor"):
        import executors
        r = executors.execute(v["state"], side, qty, why=why)
        if r.get("ok"):
            persist_executor(venue, v)
            store.order(venue, int(time.time() * 1000), side, qty,
                        r["avg_px"], f"{venue}-{int(time.time() * 1000)}",
                        z=body.get("z"), target=body.get("target"),
                        spread=body.get("spread"), why=why, fee=r["fee"])
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
    n = min(int(request.query.get("n", 50)), 300)   # paging: ?n= up to 300
    rows = store.conn().execute(
        "SELECT ts, side, qty, price, fee, resp, why FROM orders WHERE venue=? "
        "ORDER BY ts DESC LIMIT ?", (venue, n)).fetchall()
    return web.json_response([{"ts": t, "side": s, "qty": q, "price": p,
                               "fee": (round(f, 4) if f is not None else None),
                               "id": r, "why": w}
                              for t, s, q, p, f, r, w in rows])

async def venue_position(request):
    """THE truth: the venue's own position, via the SDK. Para venues
    virtuales, el estado del ejecutor — devolver null haría que el
    cerebro 'reconciliara' hacia cero venues vivos."""
    venue = request.match_info["venue"]
    v = next((v for v in VENUES if v["id"] == venue), None)
    if not v:
        return web.json_response({"position": None})
    if v.get("executor"):
        return web.json_response({"position": v["state"]["pos"]})
    if (v.get("state") or {}).get("api") is None:
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
        for ts, v, side, qty, price, _fee in store.recent_orders():
            if v == venue:
                await ws.send_str(json.dumps({"type": "order", "venue": venue, "ts": ts,
                    "side": side, "qty": qty, "price": price}))
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    m = json.loads(msg.data)
                    if m.get("type") == "flatten":
                        # aiohttp clone() no acepta match_info (fallo I2):
                        # llamar a la pasarela HTTP real, que ya funciona
                        import aiohttp as _a
                        async with _a.ClientSession() as s:
                            await s.post(f"http://127.0.0.1:7777/flatten/{m['venue']}")
                except Exception as e:
                    print(f"flatten ws: {e}", flush=True)
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
    # lista congelada: un cliente conectándose durante el broadcast no
    # mata la corrutina entera (fallo I6); los muertos se quitan después
    dead = []
    for ws in list(clients):
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
    app.router.add_get("/api/metrics/{venue}", api_metrics)
    app.router.add_get("/api/roundtrips", api_roundtrips)
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
            st = executors.make_state(v["instrument"],
                                      fee_bps=v.get("fee_bps", 5.0),
                                      funding=v.get("funding", False))
            persisted = store.load_executor_state(v["id"])
            if persisted:
                # el venue virtual SOBREVIVE restarts: su posición es
                # verdad persistida, no memoria volátil
                st.update(persisted)
            v["state"] = st
            executors.start_drainer(v["executor"], st)
    for v in VENUES:
        if v.get("feed"):
            threading.Thread(target=feed_thread, daemon=True,
                             args=(v["id"], v["feed"])).start()
    threading.Thread(target=feed_thread, daemon=True,
                     args=("binance-all", "BTCUSDT")).start()
    for extra in ("hl", "aster"):
        threading.Thread(target=feed_thread, daemon=True,
                         args=(extra, "BTC")).start()
    if clojure_ids:
        def tail():
            p = os.path.join(HERE, "clj_events.jsonl")
            while not os.path.exists(p):
                time.sleep(0.5)
            with open(p) as f:
                f.seek(0, 2)
                buf = ""
                while True:
                    chunk = f.readline()
                    if not chunk:
                        time.sleep(0.3)
                        continue
                    buf += chunk
                    if not buf.endswith("\n"):
                        continue   # línea a medias: esperar el resto
                    for line in buf.splitlines():
                        if not line.strip():
                            continue
                        try:
                            ev = json.loads(line)
                            if ev.get("venue") == "clojure":
                                ev["venue"] = "grvt"
                            broadcast(ev)
                        except Exception:
                            pass
                    buf = ""
        threading.Thread(target=tail, daemon=True).start()
    web.run_app(app, host="127.0.0.1", port=7777, print=None)

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
    # edad del cerebro: si no emite nada en mucho tiempo, está podrido
    brain = None
    try:
        brain = _t.time() - os.path.getmtime("clj_events.jsonl")
    except Exception: pass
    return web.json_response({
        "feed_hb_s": hb,
        "last_bar_s": last_bar,
        "brain_age_s": brain,
        "learner_log": os.path.exists("/tmp/learning_log.jsonl"),
        "clients_ws": len(clients)})

async def api_venues(request):
    out = []
    for v in VENUES:
        st = v.get("state") or {}
        # VERDAD PERSISTIDA: realized del executor (sobrevive restarts)
        # + unrealized calculado en BACKEND (pos×(last−entry)); la UI
        # solo pinta, jamás inventa PnL (fallo L141)
        realized = st.get("realized")
        last = st.get("last_px")
        pos = st.get("pos", 0.0)
        entry = st.get("entry") or 0.0
        unreal = (pos * (last - entry)) if (last and entry and pos) else 0.0
        out.append({"id": v["id"], "label": v["label"],
                    "instrument": st.get("instrument"),
                    "position": pos,
                    "realized": round(realized, 2) if realized is not None else None,
                    "unrealized": round(unreal + (realized or 0), 2),
                    "fee_bps": v.get("fee_bps"),
                    **store.stats(v["id"])})
    return web.json_response(out)

async def api_roundtrips(request):
    """Trade history grouped flat -> flat with net P&L (fees inside)."""
    n = min(int(request.query.get("n", 300)), 1000)
    fees = {v["id"]: v.get("fee_bps") for v in VENUES}
    return web.json_response(store.round_trips(fees, n))

async def api_metrics(request):
    """Sharpe/hit/maxDD desde las BARRAS PERSISTIDAS — verdad de SQLite."""
    return web.json_response(store.metrics(request.match_info["venue"]))

if __name__ == "__main__":
    main()
