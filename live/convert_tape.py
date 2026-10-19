#!/usr/bin/env python3
"""Conversión nocturna: cintas binarias -> Parquet particionado por
venue/día (5-10x más compacto, lectura columnar para entrenar).
Uso: convert_tape.py [YYYYMMDD]  (por defecto: ayer y hoy)
"""
import sys, os, glob, struct
import pandas as pd

TAPE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tape")
PARQUET_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "parquet")

def read_prints_bin(path):
    """FORMATO ÚNICO de cinta: '<Id' intercalado little-endian, 12 B.
    El mismo que escribe store.tape y lee trade.learn-hot."""
    raw = open(path, "rb").read()
    rows = list(struct.iter_unpack("<Id", raw[:len(raw) - len(raw) % 12]))
    return pd.DataFrame({"ts": [r[0] for r in rows],
                         "px": [r[1] for r in rows]})

def main():
    days = sys.argv[1:] or [time.strftime("%Y%m%d", time.gmtime(-86400)),
                            time.strftime("%Y%m%d", time.gmtime())]
    os.makedirs(PARQUET_DIR, exist_ok=True)
    for day in days:
        for path in glob.glob(os.path.join(TAPE_DIR, f"{day}_*.prints")):
            venue = os.path.basename(path)[len(day) + 1:-len(".prints")]
            df = read_prints_bin(path)
            if df.empty:
                continue
            out = os.path.join(PARQUET_DIR, f"{day}_{venue}.parquet")
            df.to_parquet(out, compression="zstd", index=False)
            print(f"{out}: {len(df)} prints "
                  f"({os.path.getsize(path)//1024}KB -> {os.path.getsize(out)//1024}KB)")

if __name__ == "__main__":
    import time
    main()
