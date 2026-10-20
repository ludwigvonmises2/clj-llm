#!/usr/bin/env python3
"""Virtual ULTRA executor — ONE factory for every virtual venue.

DECISION LIVES IN CLOJURE (rule 1): this module only fills orders the
brain already decided. Ultra-realistic fills: latency, vol-dependent
synthetic book, size impact, 5 bps taker fee, partial tranches. Long
AND short (negative position = short, perp-style accounting)."""
import math, random, threading, time, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import relay

LATENCY_S = (0.020, 0.060)
TOP_DEPTH_USD = 3.2e6 # synthetic top-of-book depth in NOTIONAL (≈40 BTC);
                      # units per asset = USD / price (60 'units' of BTC
                      # was applied to SOL/XRP/DOGE alike: R2)
HALF_SPREAD_BPS = 1.2 # synthetic half-spread at rest (fallback)
PX_STALE_S = 10       # un print más viejo que esto = mercado muerto
QUOTE_STALE_S = 5     # quote real más viejo -> sintético
FUNDING_PER_S = 0.0001 / 28800.0   # 0.01%/8h prorrateado (perp real)

def make_state(instrument, fee_bps=5.0, funding=True):
    """fee_bps = tarifa TAKER REAL del venue/mercado (binance perp 5.0,
    bybit 5.5, okx 5.0, coinbase spot 8.0, kraken spot 26.0, hl 4.5,
    aster 3.5). funding solo en perps (spot no paga)."""
    return {"pos": 0.0, "entry": 0.0, "realized": 0.0,
            "instrument": instrument, "last_px": None, "last_px_ts": 0.0,
            "real_bid": None, "real_ask": None, "real_quote_ts": 0.0,
            "funding_accrued_ts": None,
            "fee_bps": float(fee_bps), "funding": bool(funding)}

def synthetic_book(rv, last):
    """L2 sintético: el spread se ensancha con la volatilidad 1s."""
    hs = HALF_SPREAD_BPS + 300.0 * rv if rv else HALF_SPREAD_BPS
    return last * (1 - hs / 10000), last * (1 + hs / 10000)

def execute(state, side, qty, why="signal"):
    """Fill de mercado de una orden YA DECIDIDA por el cerebro.
    Fidelidad al venue real: spread del LIBRO REAL cuando el quote del
    venue está vivo (sintético solo como fallback), impacto sqrt del
    tamaño, fees reales, funding de perp prorrateado continuo."""
    last = state["last_px"]
    # precio obsoleto = mercado muerto: NO llenar (fallo F6 del review)
    if not last or time.time() - state.get("last_px_ts", 0) > PX_STALE_S:
        return {"ok": False, "msg": "sin precio de mercado vivo"}
    time.sleep(random.uniform(*LATENCY_S) / 10)   # 2-6 ms efectivos
    # funding de perp prorrateado desde el último fill (0.01%/8h base):
    # mantener posición cuesta; el scalping lo paga mínimo pero REAL.
    # Spot nunca paga funding (state["funding"] False).
    now = time.time()
    if state.get("funding") and state["pos"] != 0 \
            and state.get("funding_accrued_ts"):
        dt = now - state["funding_accrued_ts"]
        state["realized"] -= abs(state["pos"]) * last * FUNDING_PER_S * dt
    state["funding_accrued_ts"] = now
    # spread REAL del venue si el quote está vivo; sintético si no
    qb, qa = state.get("real_bid"), state.get("real_ask")
    if qb and qa and now - state.get("real_quote_ts", 0) < QUOTE_STALE_S:
        bid, ask = qb, qa
        hs = (qa - qb) / 2 / qa * 10000
    else:
        rv = state.get("rv")
        hs = HALF_SPREAD_BPS + 300.0 * rv if rv else HALF_SPREAD_BPS
        bid, ask = synthetic_book(rv, last)
    px = ask if side == "buy" else bid
    # impacto √tamaño sobre la profundidad: el real no es lineal
    remaining, filled, cost, tranches = qty, 0.0, 0.0, []
    depth_u = TOP_DEPTH_USD / last
    levels = [(depth_u / 2, 1.0), (depth_u, 2.5)]
    for depth, impact_mult in levels:
        take = min(remaining, depth)
        if take <= 0:
            break
        # impacto extra √tamaño × vol 1s: el cruce del spread ya está
        # en px (ask/bid); el impacto real añade raíz del llenado
        slip_bps = 300.0 * (state.get("rv") or 0) * impact_mult \
            * math.sqrt(take / depth)
        slip = slip_bps / 10000
        fill_px = px * (1 + slip) if side == "buy" else px * (1 - slip)
        tranches.append((take, fill_px))
        filled += take
        cost += take * fill_px
        remaining -= take
    avg_px = cost / filled if filled else last
    qty_eff = filled                      # jamás mover posición sin fill
    fee = avg_px * qty_eff * state["fee_bps"] / 10000.0
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
    return {"ok": True, "avg_px": avg_px, "fee": fee, "filled": qty_eff,
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
                t = ev.get("type")
                if t == "quote":
                    # libro REAL del venue (books5/orderbook.1): spread
                    # fiel para los fills mientras el quote esté vivo
                    state["real_bid"] = float(ev["bid"])
                    state["real_ask"] = float(ev["ask"])
                    state["real_quote_ts"] = time.time()
                    continue
                if t != "print":
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
