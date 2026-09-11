# Model Guide

## Catalog (real files, real checksums)
| id | model | quant | size | RAM need | device class | template |
|---|---|---|---|---|---|---|
| qwen25-05b | Qwen2.5-0.5B-Instruct | Q4_K_M | 469 MB | ~1.0 GB | BASIC+ | ChatML |
| smollm2-17b | SmolLM2-1.7B-Instruct | Q4_K_M | 1007 MB | ~2.2 GB | STANDARD+ | ChatML |
| qwen25-15b | Qwen2.5-1.5B-Instruct | Q4_K_M | 1066 MB | ~2.4 GB | STANDARD+ | ChatML |
| llama32-3b | Llama-3.2-3B-Instruct | Q4_K_M | 1926 MB | ~3.6 GB | POWER+ | Llama3 |
| qwen25-3b | Qwen2.5-3B-Instruct | Q4_K_M | 2007 MB | ~3.8 GB | POWER+ | ChatML |

SHA-256 values are pinned from the HuggingFace Hub API and verified after every download.
A checksum-mismatched file is deleted, never loaded.

## Recommendation logic
`DeviceProfiler` classifies the device (BASIC <4GB / STANDARD 4-6 / POWER 6-8 / HIGH-END 8+)
using total RAM + cores; `ModelCatalog.recommendFor()` picks the strongest model whose
RAM need fits 45% of total RAM. Never auto-downloads anything.

## Download manager
Resumable (HTTP Range), pause/resume/cancel, storage pre-check, streaming SHA-256 verify,
GGUF magic check on import, delete. Progress with speed shown in the Models tab.

## Benchmarking
Real measurement only: load + 64-token generation timed on-device, reported as tokens/sec.
No fake numbers anywhere in the UI.

## Importing
Models tab -> "Import a .gguf file" (SAF). Imported files are magic-checked; checksums are
not pre-known, so they are verified on first load and reported honestly.
