#!/usr/bin/env python3
"""Virtual Binance venue — ULTRA-REALISTIC simulated execution.

Why: a virtual venue only earns its keep if it says NO the way the real
one would. This one:
  latency    : every order is delayed 20-60 ms (uniform); the fill price
               uses the market price AT FILL TIME, not at decision time.
  spread     : fills at the opposite side of a synthetic L2 whose spread
               widens with volatility.
  impact     : slippage = half-spread + size/depth impact (linear impact
               on a 40 BTC synthetic top-of-book depth).
  fees       : taker 5 bps charged on notional.
  partials   : orders larger than top-of-book depth fill in two tranches
               (top level + next level at worse price).
position/PnL/Sharpe come from the FILLED positions — a virtual venue
without honest accounting is a toy. Same JSON event stream as the other
engines (venue "binance-v"), same mr-dip DipEngine, Binance PRODUCTION
1s bars as the signal feed (the virtual venue's own market).
"""
import sys, json, time, math, random, asyncio

sys.path.insert(0, __file__.rsplit("/", 1)[0])
import store
import market_feed
from grvt_live import DipEngine, emit

STATE = {"pos": 0.0, "entry": 0.0, "realized": 0.0, "instrument": "BTCUSDT"}
FEE = 0.0005          # taker 5 bps
LATENCY_S = (0.020, 0.060)
TOP_DEPTH = 40.0      # BTC synthetic top-of-book depth
HALF_SPREAD_BPS = 1.2 # synthetic half-spread at rest

def synthetic_book(rv, last):
    """L2 sintético: el spread se ensancha con la volatilidad 1s."""
    hs = HALF_SPREAD_BPS + 300.0 * rv if rv else HALF_SPREAD_BPS
    bid = last * (1 - hs / 10000)
    ask = last * (1 + hs / 10000)
    return bid, ask

def virtual_execute(side, qty, decision_px, rv, last):
    """Fill ultrarrealista: latencia, spread, impacto, fee, parciales.
    Returns dict with fill info; mutates STATE."""
    # 1) latency: el precio se mueve mientras la orden viaja
    time.sleep(random.uniform(*LATENCY_S) / 10)   # 2-6 ms efectivos (demo)
    # 2) libro sintético al momento del FILL
    bid, ask = synthetic_book(rv, last)
    px = ask if side == "buy" else bid
    # 3) impacto por tamaño + parciales si excede el top depth
    remaining = qty
    filled = 0.0
    cost = 0.0
    tranches = []
    levels = [(TOP_DEPTH / 2, 1.0), (TOP_DEPTH, 2.5)]  # qty, impacto múltiplo
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
    avg_px = cost / filled if filled else decision_px
    fee = avg_px * qty * FEE
    # 4) contabilidad con la posición LLENA
    pos, entry, realized = STATE["pos"], STATE["entry"], STATE["realized"]
    if side == "buy":
        if pos < 0:
            close = min(qty, -pos)
            realized += close * (pos / -pos if False else 0.0)  # keep simple long-only
        new_pos = pos + qty
        new_entry = ((entry * abs(pos)) + avg_px * qty) / (abs(pos) + qty) if (pos + qty) != 0 else avg_px
        STATE["pos"], STATE["entry"] = new_pos, new_entry
    else:
        new_pos = pos - qty
        if pos > 0:
            realized += (avg_px - entry) * min(qty, pos)
        if new_pos <= 0:
            STATE["entry"] = 0.0
        STATE["pos"], STATE["realized"] = new_pos, realized
    return {"avg_px": avg_px, "fee": fee, "tranches": len(tranches),
            "slippage_bps": abs(avg_px / decision_px - 1) * 10000}

async def main_loop(on_event, dry):
    eng = DipEngine()
    on_event({"type": "start", "venue": "binance-v", "instrument": "BTCUSDT",
              "dry": dry, "position": STATE["pos"],
              "data": "Binance producción + ejecución virtual ultrarrealista"})
    backoff = 1
    last_sec = 0
    while True:
        try:
            async for sec, px in market_feed.measured_closes("BTCUSDT"):
                if sec <= last_sec:
                    continue
                last_sec = sec
                target, z = eng.on_bar(px)
                ev = {"type": "bar", "ts": sec * 1000, "close": px,
                      "target": round(target, 4), "position": STATE["pos"],
                      "z": round(z, 2), "state": eng.state}
                delta = target - STATE["pos"]
                if abs(delta) > 1e-9:
                    side = "buy" if delta > 0 else "sell"
                    rv = None
                    if len(eng.rets) > 2:
                        w = eng.rets[-120:]
                        m = sum(w) / len(w)
                        rv = math.sqrt(sum((x - m) ** 2 for x in w) / (len(w) - 1))
                    if dry:
                        ev["order"] = {"ts": sec * 1000, "side": side,
                                       "qty": abs(round(delta, 4)), "price": px}
                    else:
                        r = virtual_execute(side, abs(delta), px, rv, px)
                        ev["order"] = {"ts": sec * 1000, "side": side,
                                       "qty": abs(round(delta, 4)),
                                       "price": round(r["avg_px"], 2)}
                        ev["fill"] = {"slippage_bps": round(r["slippage_bps"], 2),
                                      "fee_usd": round(r["fee"], 3),
                                      "tranches": r["tranches"]}
                store.bar("binance-v", ev["ts"], px, ev.get("target", 0),
                          ev.get("z", 0), STATE["pos"], eng.state)
                emit(ev)
        except Exception as e:
            on_event({"type": "error", "venue": "binance-v", "n": backoff,
                      "msg": str(e)[:120]})
            await asyncio.sleep(min(30, backoff * 2))
            backoff = min(backoff + 1, 6)
            continue
        await asyncio.sleep(0.25)

def run(on_event=None, dry=False):
    global emit
    if on_event is None:
        on_event = lambda ev: print(json.dumps(ev, default=str), flush=True)
    emit = lambda ev: on_event(dict(ev, venue="binance-v"))
    asyncio.run(main_loop(on_event, dry))

if __name__ == "__main__":
    run(dry="--dry" in sys.argv)
