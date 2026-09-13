# Model Guide

## Catalog (real files, real checksums)
| id | model | quant | size | RAM need | device class | template |
|---|---|---|---|---|---|---|
| smollm2-360m | SmolLM2-360M-Instruct | Q8_0 | 386 MB | ~0.8 GB | BASIC+ | ChatML |
| qwen25-05b | Qwen2.5-0.5B-Instruct | Q4_K_M | 469 MB | ~1.0 GB | BASIC+ | ChatML |
| llama32-1b | Llama-3.2-1B-Instruct | Q4_K_M | 807 MB | ~1.7 GB | BASIC+ | Llama3 |
| smollm2-17b | SmolLM2-1.7B-Instruct | Q4_K_M | 1007 MB | ~2.2 GB | STANDARD+ | ChatML |
| qwen25-15b | Qwen2.5-1.5B-Instruct | Q4_K_M | 1066 MB | ~2.4 GB | STANDARD+ | ChatML |
| llama32-3b | Llama-3.2-3B-Instruct | Q4_K_M | 1926 MB | ~3.6 GB | POWER+ | Llama3 |
| qwen25-3b | Qwen2.5-3B-Instruct | Q4_K_M | 2007 MB | ~3.8 GB | POWER+ | ChatML |

**Speed picks:** `smollm2-360m` is the fastest of the catalog (quick prefill, highest
tokens/sec — great for simple device actions on any phone); `llama32-1b` is the sweet
spot with noticeably stronger instruction following and reliable JSON at still-high speed.

SHA-256 values are pinned from the HuggingFace Hub API and verified after every download.
A checksum-mismatched file is deleted, never loaded.

## Recommendation logic
`DeviceProfiler` classifies the device (BASIC <4GB / STANDARD 4-6 / POWER 6-8 / HIGH-END 8+)
using total RAM + cores; `ModelCatalog.recommendFor()` picks the strongest model whose
RAM need fits 45% of total RAM. Never auto-downloads anything.

## Test model button
Every model card (recommended / all / imported) has a **Test model** button next to the
ready chip. It runs one real 48-token generation and reports elapsed time, token count
and tokens/sec — shown accent-colored on success, red with the real error on failure.
No more guessing whether a model works; works in release builds too (the JNI progress
lookup is ProGuard-protected and the native callback is optional by design).

## Compact mode (small models)
Sub-1.2B models (by `params`, or imports under ~900 MB) automatically run in compact
prompt mode: a shorter system prompt with one worked example, a names+required-args tool
catalog, a tightened screen block (22 elements, 24-char texts) and a 130-token output
cap. Small models get small prompts so they spend their budget on the action instead of
echoing the screen.

## Auto-reload after restart
The active model id is persisted; on app start `ModelManager.autoReloadActive()` restores
it (catalog or imported, checksum-verified, guarded while a task runs). A reboot or
process death no longer silently drops JARVIS to rule mode.

## Download manager
Resumable (HTTP Range), pause/resume/cancel, storage pre-check, streaming SHA-256 verify,
GGUF magic check on import, delete. Progress with speed shown in the Models tab.

## Performance threads
With `inferenceThreads=0` (default) the app detects performance cores by reading per-core
max frequencies from sysfs (big cores = >=85% of the fastest), clamps 1–6 and falls back
to 4 — llama.cpp runs on the cores that are actually fast instead of all cores minus two.

## Benchmarking
Real measurement only: the Test model button (load + 48-token generation timed on-device,
reported as tokens/sec). No fake numbers anywhere in the UI.

## Importing
Models tab -> "Import a .gguf file" (SAF). Imported files are magic-checked; checksums are
not pre-known, so they are verified on first load and reported honestly. Import size also
drives compact mode eligibility (see above).
