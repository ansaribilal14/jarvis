# Local AI

## Runtime
llama.cpp **b4458** compiled for arm64-v8a via NDK + CMake (FetchContent, SHA-256 pinned).
JNI boundary (`llama_jni.cpp`): load/unload, single completion call with cancel flag,
UTF-8 byte arrays across the boundary (no modified-UTF8 surprises).

- Sampling: top_p 0.92 -> temp 0.25 -> dist(1312). Greedy-ish for reliable JSON.
- Context: 2048 default (user-configurable to 4096); head-trim guard if a prompt is oversized.
- Threads: performance-core aware by default — the app counts big cores from sysfs
  cpufreq data (>=85% of max frequency, clamped 1–6, fallback 4) when the user leaves
  inference threads on auto; manual override in Settings.
- CPU only (see ARCHITECTURE.md decision note).

## Stop sequences (native)
`nativeComplete` accepts an array of stop strings. The decode loop re-scans a small tail
window after every token, truncates output at the earliest match and breaks. This is what
ends prompt-echo generations in seconds instead of burning the full token budget (each
wasted round used to cost 2–4 minutes on a small model); the marker is trimmed from the
returned text. Wired through `LlmProvider.generate(..., stopSequences)` into both
providers (OpenAI `stop` param on the remote path).

## Grammar-constrained decoding (native)
`nativeComplete` accepts an optional GBNF grammar string. When set, the sampler chain starts with
`llama_sampler_init_grammar` so every sampled token must keep the output inside the grammar - the
planner JSON contract becomes unbreakable (see docs/AGENT_ENGINE.md). A grammar that fails to
compile degrades to pass-through (no crash, unconstrained output). Kotlin side:
`DecisionGrammar` builds the grammar from the live tool registry per decide/plan call; the remote
provider ignores the parameter entirely.

## Progress callback (optional by design)
The JNI layer reports prompt-eval progress per 64-token chunk and throttled decode
progress (token count + partial UTF-8 bytes) to an optional listener. `GetMethodID` is
followed by `ExceptionCheck`/`ExceptionClear`: if R8 or a future refactor makes the
callback unresolvable, the listener is simply disabled — **a finished generation can
never be discarded over a progress callback again** (the v1.5.0 release-build bug).

## Prompt formatting
Deterministic chat templates per model (CHATML / LLAMA3 / PLAIN) - no jinja runtime needed.
System prompt includes the JSON action contract, the LIVE tool catalog, screen data block,
injection warning when suspicious, DEVICE NOW line, and user facts.

**Compact mode** (sub-1.2B catalog models and imports <~900 MB): short system prompt with
one worked example, names+required-args catalog, 22-element/24-char screen block,
130-token cap. Small models get small prompts (see MODEL_GUIDE.md).

## Provider abstraction
`LlmProvider` interface:
- `LlamaCppProvider` (primary, on-device),
- `RemoteOpenAiProvider` (NVIDIA NIM API mode: `https://integrate.api.nvidia.com/v1/chat/completions`,
  OpenAI-compatible, 120s read timeout; also accepts a manual base URL + key in Settings),
- deterministic rule engine (always-available fallback rung).

The agent loop is provider-agnostic. API mode (v1.5.0) flips routing to REMOTE-first from
the slide-out drawer; failures fail closed to local/rules, and LOCAL ONLY still hard-blocks
remote at the provider level.

## Fallback ladder
preferred model -> lighter model -> smallest model -> deterministic rules. Model failures at
runtime fall back to rules for that decision and are logged honestly (the fallback message
reports the true model state, not a generic "offline").
