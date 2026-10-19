import asyncio, json, aiohttp
async def main():
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect("http://localhost:7777/ws") as ws:
            t0 = asyncio.get_event_loop().time()
            msgs = []
            while asyncio.get_event_loop().time() - t0 < 12:
                try:
                    m = await asyncio.wait_for(ws.receive(), 13)
                    ev = json.loads(m.data)
                    if ev.get("venue") == "alpaca" and ev["type"] in ("notice","error"):
                        msgs.append(str(ev.get("msg",""))[:130])
                except asyncio.TimeoutError: break
            for x in list(dict.fromkeys(msgs))[:4]: print(x)
asyncio.run(main())
