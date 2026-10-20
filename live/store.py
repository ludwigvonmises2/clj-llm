#!/usr/bin/env python3
"""Desk memory: SQLite (WAL) — bars, orders and events persist across
restarts, and a freshly connected dashboard replays the session so far.
stdlib sqlite3 only; one connection per writer thread."""
import sqlite3, os, time, threading, json, glob, struct

DB = os.path.join(os.path.dirname(os.path.abspath(__file__)), "desk.db")
_local = threading.local()

SCHEMA = """
CREATE TABLE IF NOT EXISTS bars(
  ts INTEGER, venue TEXT, close REAL, target REAL, z REAL,
  position REAL, state INTEGER, PRIMARY KEY (venue, ts));
CREATE TABLE IF NOT EXISTS orders(
  ts INTEGER, venue TEXT, side TEXT, qty REAL, price REAL, resp TEXT,
  z REAL, target REAL, spread REAL, why TEXT);
CREATE TABLE IF NOT EXISTS events(
  ts INTEGER, venue TEXT, type TEXT, msg TEXT);
CREATE INDEX IF NOT EXISTS idx_bars_vt ON bars(venue, ts);
"""

def conn():
    c = getattr(_local, "c", None)
    if c is None:
        c = sqlite3.connect(DB)
        c.execute("PRAGMA journal_mode=WAL")
        c.execute("PRAGMA synchronous=NORMAL")
        _local.c = c
    return c

def init():
    conn().execute("""CREATE TABLE IF NOT EXISTS executor_state (
        venue TEXT PRIMARY KEY, pos REAL, entry REAL, realized REAL,
        ts INTEGER)""")
    # fee column: per-fill real taker fee (net result per trade, L142/143)
    try:
        conn().execute("ALTER TABLE orders ADD COLUMN fee REAL")
    except Exception:
        pass  # column already exists
    conn().commit()
    conn().executescript(SCHEMA)

def save_executor_state(venue, state):
    """Write-through del venue virtual: su posición ES verdad y debe
    sobrevivir restarts del desk (si no, cada reinicio miente)."""
    conn().execute(
        "INSERT OR REPLACE INTO executor_state VALUES (?,?,?,?,?)",
        (venue, state["pos"], state["entry"], state["realized"],
         int(time.time() * 1000)))
    conn().commit()

def load_executor_state(venue):
    """Restauración VALIDADA: una fila corrupta (NaN/inf/texto) jamás
    envenena el estado vivo — se descarta y el venue arranca plano."""
    row = conn().execute(
        "SELECT pos, entry, realized FROM executor_state WHERE venue=?",
        (venue,)).fetchone()
    if not row:
        return None
    try:
        import math
        vals = [float(x) for x in row]
        if not all(math.isfinite(x) for x in vals):
            raise ValueError("no finito")
        return {"pos": vals[0], "entry": vals[1], "realized": vals[2]}
    except Exception:
        return None

def bar(venue, ts, close, target, z, position, state):
    conn().execute(
        "INSERT OR REPLACE INTO bars VALUES (?,?,?,?,?,?,?)",
        (int(ts), venue, close, target, z, position, int(state)))
    conn().commit()

def order(venue, ts, side, qty, price, resp="", z=None, target=None,
          spread=None, why="", fee=None):
    conn().execute(
        "INSERT INTO orders VALUES (?,?,?,?,?,COALESCE(?, ''),?,?,?,?,?)",
        (int(ts), venue, side, qty, price, str(resp)[:200],
         z, target, spread, str(why)[:60], fee))
    conn().commit()

def event(venue, type_, msg):
    conn().execute("INSERT INTO events VALUES (?,?,?,?)",
                   (int(time.time() * 1000), venue, type_, str(msg)[:300]))
    conn().commit()

def recent_bars(venue, n=600):
    rows = conn().execute(
        "SELECT ts, close, target, z, position, state FROM bars "
        "WHERE venue=? ORDER BY ts DESC LIMIT ?", (venue, n)).fetchall()
    return list(reversed(rows))

def recent_orders(n=40):
    return conn().execute(
        "SELECT ts, venue, side, qty, price, fee FROM orders ORDER BY ts DESC LIMIT ?",
        (n,)).fetchall()

def round_trips(fee_bps, n=300):
    """Fills grouped into round trips (flat -> flat) per venue, oldest first.
    Net = gross P&L of the fills - fees; fee falls back to the venue's real
    taker bps (same rule the executor applies) for fills before the fee
    column existed. A flip closes the trip and opens a new one with the
    remainder. The last trip of a venue may still be open."""
    rows = conn().execute(
        "SELECT ts, venue, side, qty, price, fee FROM orders "
        "WHERE qty > 0 AND price > 0 ORDER BY venue, ts").fetchall()
    trips, cur = [], {}
    for ts, venue, side, qty, px, fee in rows:
        bps = fee_bps.get(venue) or 0.0
        if fee is None:
            fee = px * qty * bps / 1e4
        sgn = 1.0 if side == "buy" else -1.0
        while qty > 1e-12:
            t = cur.get(venue)
            if t is None:
                t = cur[venue] = {"venue": venue, "side": "long" if sgn > 0 else "short",
                                  "open_ts": ts, "close_ts": None, "pos": 0.0,
                                  "qty": 0.0, "cash": 0.0, "fee": 0.0, "fills": 0,
                                  "entry_px": px, "exit_px": None}
            # take only up to flat: the remainder opens the next trip
            closing = t["pos"] * sgn < 0
            take = min(qty, abs(t["pos"])) if closing else qty
            part = take / qty
            t["pos"] += sgn * take
            t["cash"] -= sgn * take * px
            t["fee"] += fee * part
            t["fills"] += 1
            if not closing:
                t["qty"] += take
            fee -= fee * part
            qty -= take
            if closing:
                t["exit_px"] = px
            if abs(t["pos"]) < 1e-9:
                t["close_ts"] = ts
                trips.append(t)
                del cur[venue]
    trips.extend(cur.values())
    out = []
    for t in trips:
        closed = t["close_ts"] is not None
        gross = t["cash"] if closed else None
        out.append({"venue": t["venue"], "side": t["side"],
                    "open_ts": t["open_ts"], "close_ts": t["close_ts"],
                    "qty": round(t["qty"], 8), "entry_px": t["entry_px"],
                    "exit_px": t["exit_px"], "fills": t["fills"],
                    "fee": round(t["fee"], 4),
                    "gross": round(gross, 4) if closed else None,
                    "net": round(gross - t["fee"], 4) if closed else None})
    out.sort(key=lambda r: r["close_ts"] or r["open_ts"], reverse=True)
    return out[:n]

def stats(venue):
    c = conn()
    bars = c.execute("SELECT COUNT(*) FROM bars WHERE venue=?", (venue,)).fetchone()[0]
    orders = c.execute("SELECT COUNT(*) FROM orders WHERE venue=?", (venue,)).fetchone()[0]
    return {"bars": bars, "orders": orders}

def tape(venue, obj):
    """Cinta append-only BINARIA del venue (rotación diaria, UTC):
    print  -> <day>_<venue>.prints  12 B/registro  struct '<Id' (sec u32, px f64)
    quote  -> <day>_<venue>.quotes  20 B/registro  struct '<Idd' (sec, bid, ask)
    Un solo formato en todo el sistema: recorder escribe, convert_tape
    (Parquet) y trade.learn-hot (Clojure) leen lo mismo. Materia prima
    de la reproducibilidad: re-ejecutar el cerebro sobre la cinta debe
    reproducir las mismas decisiones."""
    os.makedirs(TAPE_DIR, exist_ok=True)
    day = time.strftime("%Y%m%d", time.gmtime())
    t = obj.get("type")
    if t == "quote":
        path = os.path.join(TAPE_DIR, f"{day}_{venue}.quotes")
        sec = int(obj.get("sec") or time.time())
        with open(path, "ab") as f:
            f.write(struct.pack("<Idd", sec,
                                float(obj["bid"]), float(obj["ask"])))
    else:
        path = os.path.join(TAPE_DIR, f"{day}_{venue}.prints")
        with open(path, "ab") as f:
            f.write(struct.pack("<Id", int(obj["sec"]), float(obj["px"])))
