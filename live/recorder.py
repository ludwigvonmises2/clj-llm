#!/usr/bin/env python3
"""CAPA 0 — el grabador: dueño de TODOS los WS de venues, escribe cintas
append-only y no sabe nada de estrategias. Es el único proceso que debe
nunca morir: no depende del cerebro, del desk ni de la UI — ellos se
alimentan de sus cintas. Cada feed con reconexión interna; cada print
persistido inmediatamente (un crash pierde el print en curso, nada más).

Uso: recorder.py   (bajo scripts/run/recorder.sh, restart always)
"""
import sys, json, time, asyncio, glob, os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import feeds

TAPE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tape")
FEEDS = [
    ("grvt", "BTC_USDT_Perp"),
    ("binance-all", "BTCUSDT"),
    ("okx", "BTC-USDT-SWAP"),
    ("bybit", "BTCUSDT"),
    ("coinbase", "BTC-USD"),
    ("kraken", "BTC/USD"),
]

def tape_write(venue, ev):
    import store
    store.tape(venue, ev)   # cinta binaria: 12-20 bytes por registro

def on_event(ev):
    """Cinta inmediata + stdout (por si alguien quiere el relé)."""
    venue = ev.get("venue", "?")
    if ev.get("type") in ("print", "quote"):
        tape_write(venue, ev)
    print(json.dumps(ev, default=str), flush=True)

async def main():
    import aiohttp  # noqa: garantiza el paquete presente en este venv
    tasks = []
    for venue, instrument in FEEDS:
        stop = asyncio.Event()
        tasks.append(feeds.run_feed(venue, instrument, on_event, stop))
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    asyncio.run(main())
