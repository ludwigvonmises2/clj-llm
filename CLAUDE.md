# clj-llm — sistema de trading mecánico en Clojure

Transformer GPT y LLM genérico ELIMINADOS (código obsoleto). Este repo es
ahora un sistema de trading mecánico profesional: señales causales
(solo datos pasados), backtester sin look-ahead, métricas profesionales,
verificación walk-forward por pliegues y robustez Monte Carlo.

## Flujo de desarrollo (OBLIGATORIO)

- nREPL persistente: `clojure -M:nrepl` (puerto 7888) o `clojure -T:mcp`
  (bhauman/clojure-mcp). Nunca `clojure -M -e` en bucle.
- Para editar `.clj`/`.edn` usar las tools paren-aware de clojure-mcp;
  los seds con paréntesis desequilibran forms.
- El formateador automático reflowa ficheros: tras un sed, verificar el
  balance con awk (gsub de paréntesis) antes de correr.

## Arquitectura (src/trade, sin dependencias)

| ns | papel |
|----|-------|
| `trade.data` | carga OHLCV (Binance klines, Alpaca ajustado) + indicadores |
| `trade.signals` | exposiciones causales: `sma-crossover`, `triple-trend`, `mr-zscore`, `intraday-ensemble` |
| `trade.backtest` | motor event-driven: exposición de i gana el retorno de i+1, costes por rotación |
| `trade.metrics` | Sharpe/Sortino/CAGR/maxDD/Calmar/CVaR/skew/kurtosis, `summarize-pro` |
| `trade.portfolio` | alineación por calendario UTC, agregación, paneles, `fold-verify` |
| `trade.core` | CLI de backtest |

## Verificación (el estándar del proyecto)

- **fold-verify**: dividir en pliegues; en cada pliegue las señales se
  recomputan solo con el prefijo (sin futuro) y se exige ganar a B&H en
  Sharpe Y maxDD. "Gana siempre" = N/N pliegues.
- **dev/robustness.clj**: bootstrap por bloques (P(Sharpe>0)) y jitter
  de parámetros ±20% (no sobreajuste).
- **Alineación por calendario**: los streams combinados viven en la
  intersección de días UTC de todos los activos — nunca por índice.
- Costes: taker 5 bps (futuros USDT-M), maker 2 bps; sensibilidad 10 bps.

## Reglas

- Código SIEMPRE en inglés.
- `*warn-on-reflection*` a cero avisos; kernels primitivos.
- `areduce` abarca el array COMPLETO (reducciones por fila = loop acotado).
- Sin hints primitivos en >4 args; re-vincular con `(let [^long k k] ...)`.
- Ninguna mejora entra al sistema sin pasar fold-verify; lo que pierde se
  elimina, no se comenta.

## Datos y comandos

```bash
scripts/dl_klines.sh SYMBOL INTERVAL START_MS OUT.json   # paginado Binance
clojure -M:backtest --assets BTCUSDT,ETHUSDT --interval 1d
clojure -M dev/combined_portfolio.clj     # cartera diaria + pliegues
clojure -M dev/intraday.clj               # sistema intradía + ensemble
clojure -M dev/robustness.clj             # bootstrap + jitter
clojure -M dev/xsmomentum.clj             # momentum cross-seccional
clojure -M dev/individual_report.clj      # por activo vs B&H
```

Datos en `data/market/`: 20 criptos 1d, 1m (BTC/ETH 30 días, resto 5.5),
1h (3 años), 1s (BTC/ETH 3000 barras), 11 stocks/ETFs Alpaca ajustados.
