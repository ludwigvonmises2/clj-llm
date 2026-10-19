#!/usr/bin/env python3
"""Virtual ULTRA executor — ONE factory for every virtual venue.

DECISION LIVES IN CLOJURE (rule 1): this module only fills orders the
brain already decided. Ultra-realistic fills: latency, vol-dependent
synthetic book, size impact, 5 bps taker fee, partial tranches. Long
AND short (negative position = short, perp-style accounting)."""
import math, random, threading, time, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay

FEE = 0.0005          # taker 5 bps
LATENCY_S = (0.020, 0.060)
TOP_DEPTH = 40.0      # BTC synthetic top-of-book depth
HALF_SPREAD_BPS = 1.2 # synthetic half-spread at rest

def make_state(instrument):
    return {"pos": 0.0, "entry": 0.0, "realized": 0.0,
            "instrument": instrument, "last_px": None}

def synthetic_book(rv, last):
    """L2 sintético: el spread se ensancha con la volatilidad 1s."""
    hs = HALF_SPREAD_BPS + 300.0 * rv if rv else HALF_SPREAD_BPS
    return last * (1 - hs / 10000), last * (1 + hs / 10000)

def execute(state, side, qty, why="signal"):
    """Fill de mercado de una orden YA DECIDIDA por el cerebro.
    Muta state; devuelve el fill (px medio, fee, slippage, tramos)."""
    last = state["last_px"]
    if not last:
        return {"ok": False, "msg": "sin precio de mercado aún"}
    time.sleep(random.uniform(*LATENCY_S) / 10)   # 2-6 ms efectivos
    # vol 1s de los últimos prints: spread sensible al régimen
    rv = state.get("rv")
    hs = HALF_SPREAD_BPS + 300.0 * rv if rv else HALF_SPREAD_BPS
    bid, ask = synthetic_book(rv, last)
    px = ask if side == "buy" else bid
    remaining, filled, cost, tranches = qty, 0.0, 0.0, []
    levels = [(TOP_DEPTH / 2, 1.0), (TOP_DEPTH, 2.5)]
    for depth, impact in levels:
        take = min(remaining, depth)
        if take <= 0:
            break
        slip = (hs + 300.0 * (rv or 0) / 10000 * impact) / 10000
        fill_px = px * (1 + slip) if side == "buy" else px * (1 - slip)
        tranches.append((take, fill_px))
        filled += take
        cost += take * fill_px
        remaining -= take
    avg_px = cost / filled if filled else last
    fee = avg_px * qty * FEE
    # contabilidad long/short simétrica (cerrar corto con compra realiza
    # entry - avg_px; cerrar largo con venta, avg_px - entry)
    pos, entry, realized = state["pos"], state["entry"], state["realized"]
    if side == "buy":
        if pos < 0:
            realized += (entry - avg_px) * min(qty, -pos)
        new_pos = pos + qty
        new_entry = ((entry * abs(pos)) + avg_px * qty) / (abs(pos) + qty) \
            if (pos + qty) != 0 else avg_px
        state["pos"], state["entry"] = new_pos, new_entry
        if new_pos <= 0:
            state["entry"] = 0.0
    else:
        if pos > 0:
            realized += (avg_px - entry) * min(qty, pos)
        state["pos"] = pos - qty
        state["realized"] = realized
        if state["pos"] <= 0:
            state["entry"] = 0.0
    state["realized"] = realized
    return {"ok": True, "avg_px": avg_px, "fee": fee,
            "tranches": len(tranches),
            "slippage_bps": abs(avg_px / last - 1) * 10000,
            "pos": state["pos"], "realized": state["realized"], "why": why}

def start_drainer(tag, state):
    """Sigue los prints del relé: último precio + vol 1s para el libro.
    El WS del venue tiene UN dueño (feeds.py); esto solo observa."""
    def work():
        q = relay.subscribe(tag)
        closes = []
        cur = None
        while True:
            ev = q.get()
            if ev.get("type") != "print":
                continue
            px = float(ev["px"])
            state["last_px"] = px
            sec = int(ev["sec"])
            if cur is None or sec == cur[0]:
                closes.append(px)
                cur = (sec, px)
            else:
                if len(closes) > 2:
                    rets = [math.log(closes[i] / closes[i - 1])
                            for i in range(1, len(closes))]
                    m = sum(rets) / len(rets)
                    state["rv"] = math.sqrt(
                        sum((r - m) ** 2 for r in rets) / (len(rets) - 1))
                closes, cur = [px], (sec, px)
    threading.Thread(target=work, daemon=True).start()
