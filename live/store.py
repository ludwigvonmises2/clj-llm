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
    row = conn().execute(
        "SELECT pos, entry, realized FROM executor_state WHERE venue=?",
        (venue,)).fetchone()
    return {"pos": row[0], "entry": row[1], "realized": row[2]} if row else None

def bar(venue, ts, close, target, z, position, state):
    conn().execute(
        "INSERT OR REPLACE INTO bars VALUES (?,?,?,?,?,?,?)",
        (int(ts), venue, close, target, z, position, int(state)))
    conn().commit()

def order(venue, ts, side, qty, price, resp="", z=None, target=None,
          spread=None, why=""):
    conn().execute(
        "INSERT INTO orders VALUES (?,?,?,?,?,COALESCE(?, ''),?,?,?,?)",
        (int(ts), venue, side, qty, price, str(resp)[:200],
         z, target, spread, str(why)[:60]))
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
        "SELECT ts, venue, side, qty, price FROM orders ORDER BY ts DESC LIMIT ?",
        (n,)).fetchall()

def stats(venue):
    c = conn()
    bars = c.execute("SELECT COUNT(*) FROM bars WHERE venue=?", (venue,)).fetchone()[0]
    orders = c.execute("SELECT COUNT(*) FROM orders WHERE venue=?", (venue,)).fetchone()[0]
    return {"bars": bars, "orders": orders}

init()

TAPE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tape")

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
