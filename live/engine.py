#!/usr/bin/env python3
"""Shared scalping engine — the Python twin of trade.signals/mr-zscore
:long-dip, parameterizable per venue/timeframe. Decision math only:
each venue adapter owns its own execution, storage and state."""
import math, os

class DipEngine:
    """z = k-bar cumulative log return over realized vol; long on
    z < -thr, exit on z >= 0; target-vol scaled, capped."""
    def __init__(self, mode="scalp", k=None, w=None, thr=None, ppy=525600,
                 target_vol=0.30, max_lev=2.0):
        # FUENTE ÚNICA de parámetros: live/champion.edn, sección del
        # modo de este venue (scalp 1s / swing 1m). Defaults si falta.
        champ = {}
        try:
            txt = open(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                    "champion.edn")).read()
            sec = "scalp" if mode == "scalp" else "swing"
            block = re.search(rf":{sec}\s+\{{([^}}]*)\}}", txt)
            if block:
                for key in ("k", "w", "threshold"):
                    m = re.search(rf":{key}\s+([0-9.]+)", block.group(1))
                    if m:
                        champ[key] = float(m.group(1))
        except Exception:
            pass
        k = champ.get("k", k if k else 20)
        w = champ.get("w", w if w else 120)
        thr = champ.get("threshold", thr if thr else 1.5)
        self.k, self.w, self.thr = k, w, thr
        self.tdv = target_vol / math.sqrt(ppy)
        self.max_lev = max_lev
        self.closes, self.lrets, self.rets = [], [0.0], [0.0]
        self.state = 0

    def on_bar(self, c):
        n = len(self.closes)
        if n:
            self.lrets.append(math.log(c) - math.log(self.closes[-1]))
            self.rets.append(c / self.closes[-1] - 1)
        self.closes.append(c)
        i = len(self.closes) - 1
        z, rv = 0.0, None
        if i >= self.w:
            w = self.rets[i - self.w + 1:i + 1]
            m = sum(w) / self.w
            sd = math.sqrt(sum((x - m) ** 2 for x in w) / (self.w - 1))
            rv = sd if sd > 0 else None
        if i >= self.k and rv:
            z = sum(self.lrets[i - self.k + 2:i + 2]) / (rv * math.sqrt(self.k))
        if self.state == 0 and z < -self.thr:
            self.state = 1
        elif self.state == 1 and z >= 0:
            self.state = 0
        scale = min(self.tdv / rv, self.max_lev) if rv else 0.0
        return self.state * scale, z
