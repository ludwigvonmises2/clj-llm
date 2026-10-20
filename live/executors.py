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
PX_STALE_S = 10       # un print más viejo que esto = mercado muerto

def make_state(instrument):
    return {"pos": 0.0, "entry": 0.0, "realized": 0.0,
            "instrument": instrument, "last_px": None, "last_px_ts": 0.0}

def synthetic_book(rv, last):
    """L2 sintético: el spread se ensancha con la volatilidad 1s."""
    hs = HALF_SPREAD_BPS + 300.0 * rv if rv else HALF_SPREAD_BPS
    return last * (1 - hs / 10000), last * (1 + hs / 10000)

def execute(state, side, qty, why="signal"):
    """Fill de mercado de una orden YA DECIDIDA por el cerebro.
    Muta state; devuelve el fill (px medio, fee, slippage, tramos)."""
    last = state["last_px"]
    # precio obsoleto = mercado muerto: NO llenar (fallo F6 del review)
    if not last or time.time() - state.get("last_px_ts", 0) > PX_STALE_S:
        return {"ok": False, "msg": "sin precio de mercado vivo"}
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
    qty_eff = filled                      # jamás mover posición sin fill
    fee = avg_px * qty_eff * FEE
    # CONTABILIDAD long/short (una regla, simétrica):
    #   mismo sentido y crece  -> entry ponderado, sin realized
    #   mismo sentido y reduce -> realized (avg_px - entry) * cerrado * signo
    #   cierre total / flip    -> realized de la pierna vieja; entry fresco
    old_pos = state["pos"]
    entry = state["entry"]
    signed_delta = qty_eff if side == "buy" else -qty_eff
    new_pos = old_pos + signed_delta
    realized_delta = 0.0
    if old_pos != 0 and (new_pos == 0 or ((old_pos > 0) != (new_pos > 0))):
        closed = abs(old_pos)
        realized_delta = (avg_px - entry) * closed * (1 if old_pos > 0 else -1)
        new_entry = avg_px if new_pos != 0 else 0.0
    elif old_pos == 0:
        new_entry = avg_px
    elif abs(new_pos) < abs(old_pos):
        realized_delta = (avg_px - entry) * abs(signed_delta) \
            * (1 if old_pos > 0 else -1)
        new_entry = entry
    else:
        new_entry = ((entry * abs(old_pos)) + avg_px * abs(signed_delta)) \
            / abs(new_pos)
    state["pos"] = new_pos
    state["entry"] = new_entry
    state["realized"] = state["realized"] + realized_delta - fee
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
            try:
                ev = q.get()
                if ev.get("type") != "print":
                    continue
                px = float(ev["px"])
                state["last_px"] = px
                state["last_px_ts"] = time.time()
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
            except Exception as e:
                print(f"drainer error (continúa): {e}", flush=True)
                time.sleep(0.2)
    threading.Thread(target=work, daemon=True).start()
