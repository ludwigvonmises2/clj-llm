# Arquitectura — contrato del sistema

## Principio rector

**Cada frontera, una responsabilidad. La verdad, una sola fuente.**
Cinco capas; las dependencias apuntan en una sola dirección
(brain → desk → cinta → recorder). Nada al revés.

```
CAPA 0  RECORDER (recorder.py + scripts/run/recorder.sh)
        ÚNICO dueño de los WS de venues. Solo graba: cintas binarias
        append-only. No conoce estrategia. Nunca muere (supervisor).

CAPA 1  CINTAS (tape/) — la memoria cruda del mercado
        Binario fijo: 12 B por print (sec u32 + px f64), 20 B por quote.
        Rotación diaria. Conversión nocturna a Parquet (convert_tape.py)
        para entrenamiento (5-10× más compacto, lectura columnar).

CAPA 2  DESK (server.py + scripts/run/desk.sh)
        • relé cinta/feeds → UI y cerebro (el cerebro JAMÁS toca el venue)
        • pasarela de ejecución: /order/{venue} firma con el SDK oficial
        • fuente de verdad: /position/{venue} consulta el venue real
        • memoria de auditoría: SQLite WAL (velas, órdenes con contexto)
        • registro VENUES: añadir exchange = adaptador + 1 línea

CAPA 3  BRAIN (trade.live-clojure + scripts/run/brain.sh)
        • UNA señal para todos los venues: signals/mr-zscore (kaocha)
        • datos del PROPIO venue por relé del desk (nunca cruzados)
        • params del CAMPEÓN: live/champion.edn (EDN — formato nativo
          Clojure para estado Clojure-interno)
        • riesgo en una capa: band-executor, throttle 2 s, spread gate,
          presupuesto de errores + flatten, reconciliación 10 s,
          watchdog de silencio, intención persistida antes de la orden

CAPA 4  UI (index.html, sin dependencias externas)
        paneles por venue, métricas del servidor, flatten; solo pinta.

CAPA 5  APRENDIZAJE EN CALIENTE (trade.learn-hot + scripts/run/learner)
        • corpus inmediato: histórico real (20 criptos × 1 año 1m;
          BTC/ETH 1s) — nunca parte de cero
        • Monte Carlo continuo: bootstrap por bloques + amplificación
          de vol sobre cintas frescas (escenarios ultrarrealistas)
        • grid de la familia scalping con estabilidad de vecinos
          (anti-sobreajuste: gana la config cuyo vecindario también gana)
        • campeón → live/champion.edn → cerebro y motores lo recargan
```

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
- Watchdog de silencio (90 s) + wrapper con bucle de re-arranque
- Presupuesto de errores → flatten + parada (nunca operar roto)
- Supervisores con lockfile (una sola instancia por pieza)
- Reconciliación contra el venue cada 10 s; el venue SIEMPRE gana
- Snapshot de GRVT (sequence_number "0") descartado siempre — inyecta
  volumen pasado como presente (patrón de f-trader, medido)

## Puerta de entrada de cualquier cambio

1. kaocha (`clojure -M:test`) — causalidad, costes, contratos
2. fold-verify — Sharpe Y maxDD vs B&H en N/N pliegues, sin futuro
3. robustez (`dev/robustness.clj`) — bootstrap por bloques + jitter ±20 %
4. Solo lo que sobrevive las tres entra al sistema; lo demás se elimina.
