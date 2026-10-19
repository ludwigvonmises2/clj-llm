# Arquitectura del sistema de trading — contrato

## Principio rector

**Cada frontera tiene una sola responsabilidad, y la verdad tiene una
sola fuente.** Cuatro capas, cero solapamiento:

```
        UI (estática + WebSocket)         solo pinta; cero cálculo
              ▲            │flatten
   eventos venue-tagged    │
              │            ▼
        DESK (aiohttp, 1 proceso Python)
        • broadcast WS · memoria SQLite (WAL)
        • PASARELA: /order/{venue} firma con el SDK del venue
        • VERDAD: /position/{venue} consulta el venue real
        • registro VENUES: {id, módulo, cerebro}
              ▲ órdenes + consultas        ▲ feeds del PROPIO venue
        BRAIN (trade.live-clojure)
        • UNA señal para todos los venues: signals/mr-zscore (kaocha)
        • UN feed por venue — su propia microestructura, nunca cruzada:
            GRVT   → v1.trade + v1.mini.s + v1.book.s (L2)
            Alpaca → CryptoTrades / velas 1m propias
            Binance-v → trades de producción (su mercado real)
        • UNA capa de riesgo: band-executor, throttle 2s,
          spread gate 8 bps, presupuesto de errores + flatten,
          reconciliación contra el venue cada 10 s
```

## Reglas derivadas (cada una nació de un fallo medido)

1. **Un cerebro decide para todos los venues** — la comparación entre
   venues se convierte en experimento controlado: misma señal, distinta
   microestructura → lo que difiere es ejecución pura. (Hoy: los
   motores Python duplicaban la lógica — deriva de paridad garantizada.)

2. **La señal vive en Clojure; la firma vive en Python.** La decisión es
   `signals/mr-zscore` compilada — la misma función que los tests de
   causalidad y los pliegues validaron. EIP-712 es detalle del broker:
   la pasarela firma con los SDK oficiales, nunca a mano.

3. **El venue es la fuente de verdad.** Posición sembrada al arranque
   desde `/position/{venue}`, reconciliada cada 10 s (6 req/min, margen
   holgado bajo los límites). Divergencia detectada = el venue gana y se
   registra.

4. **Datos del venue donde se ejecuta.** El scalping opera la
   microestructura contra la que se llena — decidir sobre Binance y
   ejecutar en GRVT es pagar el spread de un libro que no viste.

5. **Robustez en una capa**: intención persistida ANTES de la orden (un
   crash deja una orden pendiente, nunca una doble), gap mínimo 2 s
   entre órdenes, presupuesto de errores que aplana y para, watchdog de
   silencio (90 s → reconnect), supervisor que relanza el brain.

6. **Lo que pierde la medición se elimina** — fold-verify (Sharpe Y
   maxDD vs B&H en N/N pliegues, sin futuro) es la puerta de entrada de
   cualquier parámetro o mecánica; `dev/robustness.clj` (bootstrap por
   bloques + jitter ±20%) es la puerta de la robustez.

## Estado de la consolidación

| Pieza | Estado |
|---|---|
| Desk pasarela + UI + SQLite | ✅ operativo (3 venues visibles) |
| Brain Clojure GRVT-nativo (L2 + spread gate) | ✅ escrito, compila |
| Feed Alpaca en el brain | ⏳ pendiente (motor Python mientras tanto) |
| Feed Binance-v en el brain | ⏳ pendiente (motor Python mientras tanto) |
| Robustez Monte Carlo | ⏳ re-corrida en curso |
| Supervisor systemd (desk + brain) | ⏳ pendiente |

La transición es gradual y honesta: mientras un feed no vive en el
brain, su venue lo opera el motor Python con `DipEngine` parametrizado —
documentado aquí como deuda, no como silencio.

## Decisión: ¿desk en Clojure o en Python? → Python, y por qué

La pregunta se responde con una restricción física, no con preferencia:
**la firma de órdenes vive en los SDK oficiales, y esos son Python**
(grvt-pysdk para el EIP-712 de GRVT, alpaca-py para Alpaca). Un desk en
Clojure seguiría necesitando el proceso Python en cada orden — movería
la fontanería sin eliminar el segundo proceso, añadiendo un salto HTTP
en el camino caliente.

La maximización de Clojure ocurre donde paga: **todo lo que decide y
mide ya es Clojure compilado** — señal (signals/mr-zscore), riesgo
(band-executor, throttle), métricas (mark-to-market, Sharpe, maxDD),
verificación (fold-verify, kaocha). La consolidación pendiente (feeds
de Alpaca y Binance-v dentro del brain) reduce Python a firmante sin
estado: sin estrategia, sin riesgo, sin memoria de decisión.

Regla práctica del repo: **una nueva capacidad va a Clojure si decide,
mide o verifica; va a Python si firma o pinta.**
