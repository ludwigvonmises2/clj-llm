import asyncio, json, aiohttp
async def main():
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect("http://localhost:7777/ws") as ws:
            t0 = asyncio.get_event_loop().time()
            counts = {}
            while asyncio.get_event_loop().time() - t0 < 20:
                try:
                    msg = await asyncio.wait_for(ws.receive(), 25)
                    ev = json.loads(msg.data)
                    k = (ev.get("venue"), ev["type"])
                    counts[k] = counts.get(k, 0) + 1
                    if ev["type"] in ("notice","error","start") and ev.get("venue")=="alpaca":
                        print("alpaca:", ev["type"], str(ev.get("msg",""))[:90])
                except asyncio.TimeoutError: break
            for k, v in sorted(counts.items()): print(k, v)
asyncio.run(main())
