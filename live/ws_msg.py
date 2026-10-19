import asyncio, json, aiohttp
async def main():
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect("http://localhost:7777/ws") as ws:
            t0 = asyncio.get_event_loop().time()
            seen = set()
            while asyncio.get_event_loop().time() - t0 < 15:
                try:
                    msg = await asyncio.wait_for(ws.receive(), 16)
                    ev = json.loads(msg.data)
                    if ev["type"] in ("notice","error","reconcile") and ev["venue"] not in seen:
                        seen.add(ev["venue"])
                        print(ev["venue"], "|", ev["type"], "|", str(ev.get("msg",""))[:110])
                except asyncio.TimeoutError: break
asyncio.run(main())
