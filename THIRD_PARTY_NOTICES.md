# Third-Party Notices

## Code components
| Component | License | Source |
|---|---|---|
| llama.cpp (b4458, fetched at build time, hash-pinned) | MIT | https://github.com/ggml-org/llama.cpp |
| Jetpack Compose / AndroidX (core, activity, lifecycle, navigation) | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support |
| Room | Apache-2.0 | androidx.room |
| WorkManager | Apache-2.0 | androidx.work |
| DataStore | Apache-2.0 | androidx.datastore |
| Security Crypto (EncryptedSharedPreferences) | Apache-2.0 | androidx.security |
| kotlinx-coroutines, kotlinx-serialization | Apache-2.0 | https://github.com/Kotlin |
| ML Kit Text Recognition (bundled, on-device) | Apache-2.0 (Google APIs SDK ToS apply) | https://developers.google.com/ml-kit |

## Model licenses (downloaded separately, never bundled)
| Model | License | Source |
|---|---|---|
| Qwen2.5-0.5B/1.5B/3B-Instruct GGUF | Qwen Research / Apache-2.0 per repo | Qwen on HuggingFace |
| SmolLM2-360M / 1.7B-Instruct GGUF | Apache-2.0 | HuggingFaceTB on HuggingFace |
| Llama-3.2-1B / 3B-Instruct GGUF (bartowski quants) | Llama 3.2 Community License | Meta / bartowski on HuggingFace |

Users accept the respective model licenses when downloading them in-app.

## Removed components (v2.3)
The v2.0-v2.1 privileged capture stack (Shizuku api/provider 13.1.5, io.github.vvb2060.ndk:boringssl,
ADB pairing adaptation from wuyr/jdwp-injector-for-android) powered the touch-capture mechanisms
removed in v2.3 (see docs/SKILLS_V3.md) and is no longer part of the app; the notices above are
kept for the historical releases that shipped it.

## Design references studied (no code copied)
Google ARTEMIS, awesome-local-ai-android, shadergradient, liquid-logo,
liquid-glass-js, react-three-fiber - used as architecture/design inspiration only.
v2.0 research set: OpenTasker, Easer, AutoX (+2 forks), argus, MobileAgent - mechanisms credited
in CHANGELOG 2.0.0 (Shizuku getevent capture = AutoX's root technique; trigger engine shape =
OpenTasker/Easer; `{{title}}`/`{{text}}` dynamics = Easer DynamicsLink; capability-bus discipline
= argus). No source code was copied from any of them.
v2.3 research set: MacroDroid's macro model (trigger/action/constraint builder, no touch
recording - the design's foundation), AutoX's node-click/gesture-latch/wait-for-element
patterns (public-API click mechanics), OpenTasker's honest-action doctrine, Tasker+AutoInput's
single-element capture precedent. Ideas only - no code copied.
