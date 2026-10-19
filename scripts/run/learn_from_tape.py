#!/usr/bin/env python3
"""Aprendizaje en caliente: consume las cintas del día y valida las
mecánicas con el criterio del sistema (fold-verify Sharpe+maxDD).
Cada pasada: cinta -> velas 1s -> DipEngine -> veredicto por pliegues.
El resultado se registra en /tmp/learning_log.jsonl (auditable).
"""
import sys, json, glob, time, math
sys.path.insert(0, "/home/a/work/clj-llm/live")
from engine import DipEngine

TAPE_DIR = "/home/a/work/clj-llm/live/tape"

def closes_from_tape(path, venue_filter=None):
    """Velas 1s desde la cinta: último print de cada segundo."""
    buckets = {}
    for line in open(path):
        try:
            ev = json.loads(line)
            if ev.get("type") != "print":
                continue
            if venue_filter and ev.get("venue") != venue_filter:
                continue
            buckets[ev["sec"]] = ev["px"]
        except Exception:
            pass
    return [buckets[k] for k in sorted(buckets)]

def fold_verify_1s(closes, k=20, w=120, thr=1.5, folds=4):
    if len(closes) < w + folds * 500:
        return None
    eng = DipEngine(k=k, w=w, thr=thr, ppy=31536000)
    n = len(closes)
    warm = w
    step = (n - warm) // folds
    wins = 0
    for f in range(folds):
        t0 = warm + f * step
        t1 = t0 + step
        e = DipEngine(k=k, w=w, thr=thr, ppy=31536000)
        for c in closes[:t0]:
            e.on_bar(c)
        pos = 0.0
        eq = 1.0
        rets = []
        for c in closes[t0:t1]:
            target, _ = e.on_bar(c)
            r = pos * (c / prev - 1) if (prev := c) and False else 0.0
        # cierre simplificado: equity con la posición objetivo por barra
        pos = 0.0
        prev = None
        eqs = [1.0]
        for c in closes[t0:t1]:
            target, _ = e.on_bar(c)
            if prev is not None and pos:
                r = pos * (c / prev - 1)
                eq *= 1 + r
                eqs.append(eq)
            prev = c
            pos = target
        bh = closes[t1 - 1] / closes[t0] - 1 if t1 > t0 else 0.0
        strat = eqs[-1] - 1
        dd = 0.0
        peak = 1.0
        for e2 in eqs:
            peak = max(peak, e2)
            dd = max(dd, 1 - e2 / peak)
        bh_eqs = [closes[t0]]
        bh_curve = [1.0]
        for c in closes[t0:t1]:
            bh_curve.append(bh_curve[-1] * (c / bh_eqs[-1]))
            bh_eqs.append(c)
        bh_dd = 0.0
        bp = 1.0
        for e2 in bh_curve:
            bp = max(bp, e2)
            bh_dd = max(bh_dd, 1 - e2 / bp)
        if strat > bh and dd < bh_dd:
            wins += 1
    return wins, folds

def main():
    out = open("/tmp/learning_log.jsonl", "a")
    for path in sorted(glob.glob(f"{TAPE_DIR}/*_grvt.jsonl"))[-1:]:
        closes = closes_from_tape(path)
        if len(closes) < 2000:
            continue
        r = fold_verify_1s(closes)
        if r:
            rec = {"ts": int(time.time()), "tape": path.split("/")[-1],
                   "bars": len(closes), "wins": r[0], "folds": r[1],
                   "gana": r[0] == r[1]}
            out.write(json.dumps(rec) + "\n")
            print(json.dumps(rec))
    out.close()

if __name__ == "__main__":
    main()
