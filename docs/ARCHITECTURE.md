# Architecture

## Module map
```
ui/            Compose screens (onboarding, home, models, history, memory, routines, doctor, settings)
               + ApiDrawer (slide-out API mode panel, wired via ApiDrawerBus)
service/       AgentForegroundService (task + STOP), OverlayService (bubble), JarvisTileService
accessibility/ JarvisAccessibilityService - observation + action backbone
core/agent/    AgentEngine (plan-first ReAct loop), ConfirmationManager, Verifier
core/planner/  Planner (JSON contract + salvage pipeline + compact prompts),
               DeterministicPlanner (fallback + rule rescue)
core/tools/    ToolRegistry + 45 tool implementations (incl. CommTools, MediaNavTools),
               each with spec/risk/verification
core/safety/   RiskClassifier, InjectionGuard, PasswordPolicy
core/model/    LlamaBridge (JNI), LlamaCppProvider, RemoteOpenAiProvider, ModelManager,
               ModelCatalog, ModelRecommender, DeviceProfiler, PromptTemplates
core/routing/  ModelRouter (local -> remote-first in API mode -> rules)
core/remote/   TelegramRemote (long-poll remote control)
core/memory/   MemoryStore (Room-backed)
core/voice/    SpeechInput (STT), VoiceOutput (TTS)
core/vision/   OcrEngine (ML Kit, fallback only)
core/routines/ RoutineManager + RoutineWorker
data/          Room database, DataStore settings, EncryptedSharedPreferences vault
cpp/           llama_jni.cpp + CMake (FetchContent pins llama.cpp b4458)
```

## Layers (native inference boundary)
UI -> AgentEngine -> Planner -> ModelRouter -> LlmProvider -> LlamaBridge (JNI) -> llama.cpp -> CPU

## Design decisions
- **Manual DI** (AppContainer) instead of Hilt: 12 singletons in a single-process app; Hilt's build-time cost and failure modes were not justified. Documented decision, revisit if the surface grows.
- **Plan-first, grounded-execution hybrid**: compound goals get an explicit up-front plan (≤6 steps) for direction, but every step is still decided and grounded against a fresh observation — the plan guides, the screen decides. Single-step requests skip planning entirely, and simple notification reads skip AI entirely (DIRECT fast path).
- **Salvage over rejection**: small local models emit imperfect JSON. Rather than failing a step over key names or truncation, the parser repairs and aliases before validating; only truly unusable output falls to the rule engine (after 2 tries, not 3 wasted minutes).
- **llama.cpp pinned b4458**: verified C API era (model-based tokenize, sampler chains), hash-pinned in CMake. Newer tags can be adopted by changing one URL + hash.
- **CPU-only inference** on Android: GPU paths (Vulkan/OpenCL) in llama.cpp require shader toolchains that make CI builds fragile and behavior device-inconsistent; Q4_K_M 0.5B-3B models give usable CPU speeds. Threads are auto-tuned to performance cores. GPU acceleration is the top roadmap item.
- **API mode is fail-closed**: NVIDIA NIM / remote providers are opt-in, off by default; any remote failure drops the decision to local then rules — never the other way round. LOCAL ONLY hard-blocks remote at the provider level.
- **Stateless completions**: KV cache cleared per call; the agent layer owns context (compact screen blocks, bounded history) - predictable memory, no session corruption.
