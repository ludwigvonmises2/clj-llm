#!/usr/bin/env python3
"""Shared market feed: Binance PRODUCTION trade stream -> 1s bar closes.

Why production data for paper execution: the testnet order books are so
thin that prints arrive minutes apart — no signal can live there. The
standard pattern is data venue != execution venue: the signal feeds from
the real market (Binance spot public WS, thousands of prints/second,
free, no key) while orders route to the paper venues (GRVT testnet,
Alpaca paper).

Usage: async for close in closes(symbol): ...   (1s bar close prices)
"""
import asyncio, json, time
import aiohttp

STREAM = "wss://stream.binance.com:9443/ws"

async def trades(symbol):
    """Yields (epoch_sec, price) for every trade print, sub-second."""
    url = f"{STREAM}/{symbol.lower()}@trade"
    async with aiohttp.ClientSession() as s:
        while True:
            try:
                async with s.ws_connect(url) as ws:
                    async for msg in ws:
                        if msg.type != aiohttp.WSMsgType.TEXT:
                            continue
                        t = json.loads(msg.data)
                        if "p" in t:
                            yield int(t["T"]) // 1000, float(t["p"])
            except Exception:
                await asyncio.sleep(2)

async def closes(symbol):
    """Yields (epoch_sec, close) once per second with a print in it."""
    cur_sec, cur_px = None, None
    async for sec, px in trades(symbol):
        if cur_sec is None:
            cur_sec, cur_px = sec, px
        elif sec == cur_sec:
            cur_px = px
        else:
            yield cur_sec, cur_px
            cur_sec, cur_px = sec, px

async def measured_closes(symbol):
    """Sensible 1s cadence on thin venues: if no print arrives for a
    second, the previous close carries forward (a bar exists every
    second; it simply didn't trade). Stops long stalls being invisible."""
    last_px = None
    async for sec, px in closes(symbol):
        if last_px is not None:
            gap = sec - last_px[0]
            for fill in range(1, gap):
                yield last_px[0] + fill, last_px[1]
        last_px = (sec, px)
        yield sec, px
