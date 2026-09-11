# Local AI

## Runtime
llama.cpp **b4458** compiled for arm64-v8a via NDK + CMake (FetchContent, SHA-256 pinned).
JNI boundary (`llama_jni.cpp`): load/unload, single completion call with cancel flag,
UTF-8 byte arrays across the boundary (no modified-UTF8 surprises).

- Sampling: top_p 0.92 -> temp 0.25 -> dist(1312). Greedy-ish for reliable JSON.
- Context: 2048 default (user-configurable to 4096); head-trim guard if a prompt is oversized.
- Threads: `hardware_concurrency - 2`, min 2 (user-configurable).
- CPU only (see ARCHITECTURE.md decision note).

## Prompt formatting
Deterministic chat templates per model (CHATML / LLAMA3 / PLAIN) - no jinja runtime needed.
System prompt includes the JSON action contract, the LIVE tool catalog, screen data block,
injection warning when suspicious, and user facts.

## Provider abstraction
`LlmProvider` interface: `LlamaCppProvider` (primary), `RemoteOpenAiProvider` (optional,
off by default, hard-blocked in LOCAL ONLY mode), deterministic rule engine (always
available fallback rung). The agent loop is provider-agnostic.

## Fallback ladder
preferred model -> lighter model -> smallest model -> deterministic rules. Model failures at
runtime fall back to rules for that decision and are logged.
