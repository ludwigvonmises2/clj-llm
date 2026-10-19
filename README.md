# clj-llm — sistema de trading mecánico en Clojure

Scalping intradía y swing en cripto con aprendizaje en caliente: el
sistema graba los venues en cintas, valida mecánicas con fold-verify y
Monte Carlo sobre datos reales y sintéticos, y opera en vivo con la
misma señal testada que valida el backtest.

**Lee `ARCHITECTURE.md`**: es el contrato de las 5 capas y de las
decisiones (un cerebro para todos los venues, el venue como fuente de
verdad, formatos por consumidor).

## Arquitectura en 30 segundos

```
RECORDER  → dueño de los WS de venues, escribe cintas binarias (nunca muere)
CINTAS    → tape/ (binario 12 B/print) → Parquet nocturno
DESK      → pasarela de firma + relé a UI + memoria SQLite (server.py)
BRAIN     → trade.live-clojure: decide con signals/mr-zscore (kaocha-tested)
LEARNER   → re-valida mecánicas cada 30 min sobre cintas frescas
            + Monte Carlo sintético → live/champion.edn (recargado en caliente)
```

Venues: **GRVT testnet** (scalping 1s, órdenes reales), **Alpaca paper**
(swing 1m, órdenes reales), **Binance virtual** (simulador
ultrarrealista: latencia, spread por volatilidad, impacto, fees,
fills parciales).

## Inicio rápido

```bash
scripts/run/start_all.sh    # recorder + desk + brain + learner
curl localhost:7777/api/health
scripts/run/stop_all.sh
scripts/run/reset_all.sh    # para, borra memoria de sesión, relanza
```

Dashboard: `http://localhost:7777` (velas 1s por venue, posición real
del venue, órdenes con contexto, P&L, flatten por venue).

## Verificación (obligatoria antes de tocar src/)

```bash
clojure -M:test      # 12 tests / 389 aserciones: causalidad, costes,
                     # paridad live/backtest, contratos fold-verify
clj-kondo --lint src dev   # 0 errores
```

## Entrenar el scalping

```bash
clojure -M -m trade.learn-hot --1s data/market/BTCUSDT_1s.json  # grid 1s
clojure -M -m trade.learn-hot --1m                              # swing 1m
cat live/champion.edn   # el campeón que brain y motores recargan
```

El grid selecciona por **estabilidad de vecinos** (anti-sobreajuste: la
config ganadora debe tener vecindario ganador), no por mejor puntaje.

## Estructura

```
src/trade/   el sistema (Clojure): signals, live, live-clojure,
             portfolio, backtest, metrics, data, core
live/        ejecución (Python): desk-gateway, feeds, recorder,
             motores por venue, cintas, Parquet
scripts/run/ supervisores con lockfile: desk, brain, recorder, learner
scripts/     descargadores de datos (Binance, Bybit, Bitstamp)
dev/         validadores: carteras, folds, robustez, intradía
test/        kaocha: causalidad, costes, paridad, contratos
```

## Datos

`data/market/`: 20 criptos × 1 año de 1m; BTC/ETH 1h × 3 años;
BTC/ETH 1s × 7 días; xStocks 6 meses; futuros Bybit 1 año; 11
stocks/ETFs Alpaca ajustados. Descarga: `scripts/dl_klines.sh`,
`dl_bybit.sh`, `dl_bitstamp.sh`.

## Costes de referencia

taker 5 bps (futuros USDT-M) / maker 2 bps; spread gate 8 bps;
snapshot de GRVT (sequence "0") siempre descartado.
