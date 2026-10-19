from grvt_sdk_order import client
import json
g = client()
pos = g.fetch_positions()
print("POSICIONES GRVT:", json.dumps(pos, default=str)[:400])
