import asyncio, json, base64, aiohttp
async def main():
    async with aiohttp.ClientSession() as s:
        targets = await (await s.get("http://localhost:9222/json")).json()
        page = next(t for t in targets if t["type"] == "page")
        async with s.ws_connect(page["webSocketDebuggerUrl"]) as ws:
            await ws.send_str(json.dumps({"id":1,"method":"Page.captureScreenshot",
                                          "params":{"format":"png"}}))
            while True:
                m = json.loads((await ws.receive()).data)
                if m.get("id")==1:
                    open("/tmp/desk.png","wb").write(base64.b64decode(m["result"]["data"]))
                    print("captura ok")
                    break
asyncio.run(main())
