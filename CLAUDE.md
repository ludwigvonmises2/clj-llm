# clj-llm — sistema de trading mecánico en Clojure

Sistema de trading mecánico de scalping con aprendizaje en caliente.
LEER `ARCHITECTURE.md` PRIMERO: es el contrato de las 5 capas
(recorder → cintas → desk → brain → learner) y de las decisiones
(formatos, un cerebro para todos los venues, el venue como fuente de
verdad).

## Comandos

```bash
clojure -M:test                          # kaocha: causalidad, costes, paridad
clojure -M:backtest --assets BTCUSDT     # backtest diario vs buy&hold
clojure -M -m trade.learn-hot --1s data/market/BTCUSDT_1s.json
                                         # entrenar grid scalping → champion.edn
scripts/run/desk.sh                      # desk (pasarela + UI) — puerto 7777
scripts/run/brain.sh                     # cerebro live (trade.live-clojure)
scripts/run/recorder.sh                  # grabador de cintas (capa 0)
scripts/run/learner.sh                   # aprendizaje en caliente (30 min)
curl localhost:7777/api/health           # ¿todo corre? edades por pieza
```

## Reglas del proyecto

1. **Todo lo que decide, mide o verifica va en Clojure** — la señal es
   SIEMPRE `signals/mr-zscore` (kaocha-tested); jamás duplicar la señal
   en otro lenguaje (la copia Python del validador fue contaminación).
2. **El venue es la fuente de verdad**: posición sembrada al arranque,
   reconciliada cada 10 s; la UI muestra lo que el venue dice.
3. **Sin futuro por construcción**: señal de la barra i gana el retorno
   de i+1; fold-verify (Sharpe Y maxDD vs B&H, N/N pliegues) es la puerta
   de entrada de cualquier mecánica o parámetro. Robustez = bootstrap +
   jitter ±20 % (`dev/robustness.clj`).
4. **Cada venue opera SU microestructura** — datos del venue propio,
   nunca cruzados; snapshot de GRVT (sequence_number "0") siempre
   descartado.
5. **Firma en la pasarela**: el cerebro decide, `server.py` firma con el
   SDK oficial del venue (grvt-pysdk, alpaca-py). Cero criptografía a mano.
6. **Un feed, un dueño**: `feeds.py` en el desk es el único suscriptor de
   cada venue WS; cerebro y UI reciben relé.
7. **Formatos**: cintas = binario (12-20 B/registro) → Parquet nocturno;
   estado interno Clojure = EDN (`champion.edn`); frontera UI = JSON.
8. **Solo 1 instancia por pieza** — supervisores con lockfile
   (`scripts/run/*.sh`). Nunca lanzar procesos a mano con setsid.
9. Código SIEMPRE en inglés; `*warn-on-reflection*` a cero; kernels
   primitivos; clj-kondo sin errores.

## Verificación (obligatoria antes de tocar src/)

```bash
clojure -M:test          # 12 tests / 389+ aserciones: causalidad (cambiar
                         # el futuro no cambia señales pasadas), costes,
                         # paridad live/backtest, contratos fold-verify
clj-kondo --lint src dev # 0 errores
```

## Costes de referencia (nuestros supuestos medidos)

- taker 5 bps (futuros USDT-M) / maker 2 bps; spread gate 8 bps
- GRVT: snapshot v1.trade (sequence "0") SIEMPRE descartado
- Alpaca paper crypto: feed fino — velas 1m propias del venue
