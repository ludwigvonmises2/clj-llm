import asyncio, json, aiohttp
async def main():
    async with aiohttp.ClientSession() as s:
        async with s.ws_connect("http://localhost:7777/ws") as ws:
            hist, live, notes = 0, 0, []
            try:
                while hist + live < 12:
                    msg = await asyncio.wait_for(ws.receive(), 15)
                    ev = json.loads(msg.data)
                    if ev["type"] == "history": hist += 1
                    elif ev["type"] == "bar": live += 1
                    elif ev["type"] == "notice": notes.append(f'{ev.get("venue")}: {str(ev.get("msg"))[:50]}')
            except asyncio.TimeoutError: pass
            for n in notes[:3]: print("notice:", n)
            print(f"history={hist} live_bars={live}")
asyncio.run(main())
