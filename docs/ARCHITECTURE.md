# Architecture

## Module map
```
ui/            Compose screens (onboarding, home, models, history, memory, routines, doctor, settings)
service/       AgentForegroundService (task + STOP), OverlayService (bubble), JarvisTileService
accessibility/ JarvisAccessibilityService - observation + action backbone
core/agent/    AgentEngine (ReAct loop), ConfirmationManager, Verifier
core/planner/  Planner (JSON contract + validation), DeterministicPlanner (fallback)
core/tools/    ToolRegistry + 30 tool implementations, each with spec/risk/verification
core/safety/   RiskClassifier, InjectionGuard, PasswordPolicy
core/model/    LlamaBridge (JNI), LlamaCppProvider, RemoteOpenAiProvider, ModelManager,
               ModelCatalog, DeviceProfiler, PromptTemplates
core/routing/  ModelRouter (local -> remote-optional -> rules)
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
- **Single-step ReAct loop** instead of full up-front plans: every action is grounded against a fresh observation; stale-state bugs become impossible by construction. The plan timeline the user sees is the actual executed history.
- **llama.cpp pinned b4458**: verified C API era (model-based tokenize, sampler chains), hash-pinned in CMake. Newer tags can be adopted by changing one URL + hash.
- **CPU-only inference** on Android: GPU paths (Vulkan/OpenCL) in llama.cpp require shader toolchains that make CI builds fragile and behavior device-inconsistent; Q4_K_M 0.5B-3B models give usable CPU speeds. GPU acceleration is the top roadmap item.
- **Stateless completions**: KV cache cleared per call; the agent layer owns context (compact screen blocks, bounded history) - predictable memory, no session corruption.
