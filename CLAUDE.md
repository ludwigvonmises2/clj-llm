# clj-llm

Un transformer GPT completo (tokenizador char-level, multi-head causal
attention, backpropagation manual, AdamW, muestreo top-k) en Clojure puro
sobre `float[]` de Java. Sin dependencias de ML.

## Flujo de desarrollo (OBLIGATORIO)

**Usar siempre clojure-mcp / nREPL persistente** para evaluar Clojure — nunca
`clojure -M -e` en bucle (cada llamada arranca una JVM nueva y recompila todo).

- Servidor MCP: `clojure -T:mcp` (alias global en `~/.clojure/deps.edn`,
  bhauman/clojure-mcp, nREPL en el puerto 7888).
- nREPL directo del proyecto: `clojure -M:nrepl` en background.
- Evaluar código con la skill `clojure-eval` (usa clj-nrepl-eval contra el
  puerto 7888).
- **Para editar ficheros `.clj`/`.edn`, preferir las tools paren-aware de
  clojure-mcp** (`clojure-edit`, `clojure-rewrite-edit`, `paren-tool`):
  preservan el balance de paréntesis por estructura, en vez de ediciones de
  texto plano propensas a desequilibrar forms (los seds con paréntesis ya
  nos costaron varios ciclos de reparación).

## Comandos

```bash
clojure -M:verify                # batería de verificación (causalidad, gradcheck, aprendizaje)
clojure -M:run train             # entrena sobre data/tinyshakespeare.txt -> data/model.bin
clojure -M:run generate --prompt "JULIET:" --tokens 300
clojure -M dev/ref_compare.clj   # compara forward/backward contra referencia en doble precisión
```

## Reglas del proyecto

- Código SIEMPRE en inglés (nombres, docstrings, comentarios).
- Los kernels numéricos viven en `llm.tensor` sobre `float[]` con bucles
  primitivos; `*warn-on-reflection*` debe seguir a cero avisos.
- `areduce` abarca el array COMPLETO — para reducciones por fila usar `loop`
  acotado a `n` (ya hubo un bug por esto; no repetirlo).
- Clojure no permite hints primitivos (`long`/`double`) en funciones de >4
  argumentos: re-vincular dentro con `(let [^long k k] ...)`.
- Los hints de retorno de vars de otros namespaces no se propagan de forma
  fiable: re-vincular con `^floats` en el punto de uso.
- Todo cambio en el backward debe pasar `clojure -M:verify` (el gradcheck
  numérico elemento a elemento es la autoridad).
