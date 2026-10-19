import asyncio, json, sys, aiohttp

async def main():
    async with aiohttp.ClientSession() as s:
        targets = await (await s.get("http://localhost:9222/json")).json()
        page = next(t for t in targets if t["type"] == "page")
        async with s.ws_connect(page["webSocketDebuggerUrl"]) as ws:
            i = 0
            async def cmd(method, params=None):
                nonlocal i
                i += 1
                await ws.send_str(json.dumps({"id": i, "method": method, "params": params or {}}))
                while True:
                    m = json.loads((await ws.receive()).data)
                    if m.get("id") == i:
                        return m.get("result", {})
            await cmd("Runtime.enable")
            expr = sys.argv[1]
            r = await cmd("Runtime.evaluate", {"expression": expr, "returnByValue": True,
                                               "awaitPromise": True})
            print(json.dumps(r.get("result", {}).get("value"), ensure_ascii=False)[:900])
asyncio.run(main())
