# Arquitectura — contrato del sistema

## Principio rector

**Cada frontera, una responsabilidad. La verdad, una sola fuente.**
Cinco capas; las dependencias apuntan en una sola dirección
(brain → desk → cinta → recorder). Nada al revés.

```
CAPA 0  RECORDER (recorder.py + scripts/run/recorder.sh)
        ÚNICO dueño de los WS de venues (GRVT, Binance, OKX, Bybit,
        Coinbase, Kraken: perps + spot). Solo graba: cintas binarias
        append-only. No conoce estrategia. Nunca muere (supervisor).

CAPA 1  CINTAS (live/tape/) — la memoria cruda del mercado
        Binario fijo: 12 B por print (sec u32 + px f64), 20 B por quote.
        Rotación diaria. Conversión nocturna a Parquet (convert_tape.py)
        para entrenamiento (5-10× más compacto, lectura columnar).

CAPA 2  DESK (server.py + scripts/run/desk.sh)
        • relé: UN fan-out del feed a UI, cerebro y ejecutores
          (live/relay.py) — nadie más abre un WS de venue
        • pasarela de ejecución: /order/{venue} firma con el SDK oficial
          (grvt-pysdk, alpaca-py) o llena con el ejecutor virtual ULTRA
          (live/executors.py: latencia, libro por volatilidad, impacto,
          fees, parciales — long y short)
        • fuente de verdad: /position/{venue} consulta el venue real
        • memoria de auditoría: SQLite WAL (velas, órdenes con contexto)

CAPA 3  BRAIN (trade.live-clojure + scripts/run/brain.sh)
        • EL ÚNICO QUE DECIDE, para TODAS las venues (regla 1): la
          señal es SIEMPRE signals/mr-zscore (kaocha-tested + test de
          frontera que rompe el build si aparece decisión en Python)
        • datos del PROPIO venue por relé (nunca cruzados); barras 1s
          (scalping) o 1m (swing) según venue; params por sección del
          CAMPEÓN: live/champion.edn {:scalp ... :swing ...}
        • riesgo en una capa: band-executor, throttle 2 s, spread gate,
          reconciliación 10 s (el venue SIEMPRE gana), watchdog,
          intención persistida antes de la orden

CAPA 4  UI (index.html, sin dependencias externas)
        paneles dinámicos por venue (reales, virtuales y de datos);
        long/short visibles; solo pinta.

CAPA 5  APRENDIZAJE EN CALIENTE (trade.learn-hot + scripts/run/learner)
        • corpus inmediato: histórico real (20 criptos × 1 año 1m;
          BTC/ETH 1s; OKX perp 1s) — nunca parte de cero
        • Monte Carlo continuo: bootstrap por bloques + amplificación
          de vol sobre cintas frescas (escenarios ultrarrealistas)
        • grid de la familia scalping con estabilidad de vecinos;
          candidato :both (long/short) se evalúa cada ciclo y SOLO se
          promueve si gana fold-verify N/N (hoy no pasa: 2/6 vs 3/6)
        • campeón → live/champion.edn → el cerebro lo recarga en caliente
```

## Cross-validación entre venues (el juez y el jurado)

La señal se entrena y fold-verifica sobre datos REALES de las venues
líquidas (Binance/OKX/Bybit: miles de prints/s — estadística de
verdad). Se ejecuta en GRVT testnet, cuyo matching es real: cada fill
mide slippage/costes reales que realimentan los supuestos del
fold-verify. Si una mecánica solo gana en el venue donde se entrenó,
fold-verify en los otros la mata antes de tocar dinero.

## Decisión de formatos (regla: el formato lo decide el CONSUMIDOR)

| Dato | Consumidor | Formato |
|---|---|---|
| Cintas de prints/quotes (millones/día) | lectores binarios/ML | **binario fijo** 12-20 B/registro |
| Órdenes + eventos de auditoría | humanos + multi-lenguaje | JSONL legible |
| Config del campeón / estado cerebro | solo Clojure | **EDN** |
| Dataset de entrenamiento nocturno | learners/ML | **Parquet** (zstd) |
| WS hacia la UI | navegador | JSON (frontera) |

## Decisión: ¿desk en Clojure? → Python, y por qué

La firma de órdenes vive en los SDK oficiales (grvt-pysdk: EIP-712;
alpaca-py) y son Python. Un desk Clojure seguiría llamando a Python en
cada orden — movería la fontanería sin eliminar el segundo proceso.
**Regla práctica: decide/mide/verifica → Clojure; firma/pinta → Python.**

## Robustez (cada regla nació de un fallo medido)

- Intención persistida ANTES de la orden (crash ⇒ orden pendiente, nunca doble)
- Watchdog de silencio (90 s) + supervisor con lockfile flock (una instancia)
- Handshake WS con límite duro (orTimeout 15 s): un deref bloqueante se cuelga en silencio
- Flow control del cliente WS: demanda ALTA (4096) re-armada por mensaje —
  pedir 1 a 1 ahoga al cliente con el burst inicial de history
- Buffer de fragmentos: LEER antes de vaciar (vaciar antes de leer dejó
  al cerebro sordo con el watchdog engañado)
- Presupuesto de errores en la reconciliación: venue inalcanzable = nil ⇒
  CONSERVAR la posición interna; jamás "reconciliar" hacia 0 un venue vivo
- Solo lo que decide va en Clojure; test de frontera en kaocha lo obliga
- Reconciliación contra el venue cada 10 s; el venue SIEMPRE gana
- Snapshot de GRVT (sequence_number "0") descartado siempre — inyecta
  volumen pasado como presente (patrón de f-trader, medido)

## Puerta de entrada de cualquier cambio

1. kaocha (`clojure -M:test`) — causalidad, costes, contratos
2. fold-verify — Sharpe Y maxDD vs B&H en N/N pliegues, sin futuro
3. robustez (`dev/robustness.clj`) — bootstrap por bloques + jitter ±20 %
4. Solo lo que sobrevive las tres entra al sistema; lo demás se elimina.
