# clj-llm — a GPT-style transformer in pure Clojure

A complete decoder-only transformer language model — character tokenizer,
multi-head causal self-attention, MLP with GELU, layer norms, manual
backpropagation, AdamW with warmup + cosine decay, top-k sampling — written
from scratch in Clojure, running on plain Java `float[]` arrays.

No ML dependencies. No BLAS. Just tight primitive loops the JVM JIT
compiles to vectorized machine code. Trains on a CPU in minutes.

Built and verified on **Clojure 1.13.0-alpha9** (latest pre-release) / Java 21.

## Architecture

| Component | Choice |
|---|---|
| Tokenizer | char-level (65 tokens for tinyshakespeare) |
| Objective | next-token cross-entropy (mean over tokens) |
| Context | 128 tokens |
| Model | 4 layers, d=128, 4 heads (dh=32), dff=512, GELU, pre-LN |
| Head | tied embeddings (logits = final-LN output · wteᵀ) |
| Init | GPT-2 style: N(0, 0.02), residual projections ÷√(2·layers) |
| Optimizer | AdamW (decoupled wd on matrices only), global-norm clip 1.0 |
| Schedule | linear warmup → cosine decay to 10% of peak lr |
| Parallelism | gradient shards, ~one per CPU core (`pmap` over batches) |

Default model: **818,048 parameters** (~3.3 MB in float32).

## Quick start

```bash
# correctness suite: attention causality, exact gradient check, learning test
clojure -M:verify

# train (≈550 ms/step on 16 cores; default 400 steps ≈ 4 min)
clojure -M:run train --steps 600

# generate
clojure -M:run generate --prompt "JULIET:" --tokens 300 --temperature 0.8 --topk 40

# interactive chat with streaming + tok/s stats (commands: /temp /topk /len /seed /bench /reset)
clojure -M:run chat

# arithmetic model: synthetic Q/A corpus + short-context training, then chat with it
clojure -M:run datagen --n 30000 --max 99
clojure -M:run train --data data/arith.txt --steps 1500 --ctx 32 --batch 32 \
        --d 64 --layers 2 --heads 2 --dff 256 --out data/arith-model.bin
clojure -M:run chat --model data/arith-model.bin     # then type: 1+1
```

## Layout

```
src/llm/
  tensor.clj    float[] kernels: matmul (3 variants), layernorm, softmax,
                GELU, gradient clipping, head split/merge — all with
                forward + backward
  nn.clj        transformer forward pass and manual backward pass for one
                sample, with a full activation cache
  model.clj     config, parameter shapes, GPT-2-style init
  train.clj     batching, per-core gradient shards, AdamW, LR schedule,
                checkpoints (Java serialization)
  sample.clj    autoregressive generation (temperature, top-k)
  verify.clj    self-verification: causality, numeric gradient check,
                learning smoke test
  core.clj      CLI
dev/
  ref_compare.clj  independent double-precision reference implementation;
                   compares forward AND every gradient on identical weights
```

## Correctness

The backward pass is verified three ways:

1. **Kernel unit tests** — every backward kernel (layernorm, GELU, softmax,
   the three matmul adjoints) against central differences in isolation.
2. **Full gradient check** — all 1,296 parameters of a tiny model,
   analytic vs numeric, atol+rtol criterion: **0 failures**.
3. **Double-precision reference** — an independent forward+backward
   implementation (`dev/ref_compare.clj`); agreement to float32 rounding
   on loss (2e-8) and every gradient.

## Performance notes

- All math is `float[]` + `dotimes` loops; the JIT auto-vectorizes the
  classic `c[i,j] += aik*b[k,j]` inner loop.
- Parallelism is data parallel: each core computes forward+backward for a
  shard of the batch into private gradient buffers, then gradients are
  summed — mutation is confined inside a step, values at the boundaries.
- Reflection warnings are kept at zero (`*warn-on-reflection*`), primitive
  hints rebound locally where the compiler's 4-primitive-arg limit forces it.

## Known limits

- float32 accumulation in matmul (fine at this scale; GPT-2 trains in bf16).
- Sampling re-runs the full forward per token (no KV cache) — fine for
  demo-scale generation.
- Char-level vocabulary; BPE is the natural next step.
