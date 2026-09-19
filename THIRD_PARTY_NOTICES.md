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

## Shizuku (v2.0 - precision touch capture bridge)
| Component | License | Source |
|---|---|---|
| dev.rikka.shizuku:api / :provider 13.1.5 | Apache-2.0 | github.com/RikkaApps/Shizuku-api |
Used to read the raw touchscreen stream (`getevent -t`) under the shell identity (root-free),
the technique AutoX implements with root. Manifest provider + API permission declared; no Shizuku
code is copied - consumed as Maven artifacts.

## Built-in privileged shell (v2.1 - the no-other-app Shizuku replacement)
| Component | License | Source |
|---|---|---|
| io.github.vvb2060.ndk:boringssl (prefab, native only) | Apache-2.0 / OpenSSL-style | github.com/vvb2060/boringssl-android |
| ADB pairing / protocol client adaptation | Apache-2.0 | github.com/wuyr/jdwp-injector-for-android (AdbClient.kt, AdbWirelessPairing.kt, AdbWirelessPortResolver.kt - adapted, keys/certs replaced with per-install generated identity) |
The AOSP wireless-debugging pairing protocol implementation (SPAKE2 + HKDF + AES-GCM PeerInfo,
TLS 1.3 adbd connection) is adapted from the wuyr project under Apache-2.0; the hardcoded demo
keypair/certificate were replaced by a per-install generated RSA identity (see
`core/adb/AdbKeyStore.kt`). SPAKE2/HKDF primitives come from BoringSSL via the vvb2060 prefab.

## Design references studied (no code copied)
Google ARTEMIS, awesome-local-ai-android, shadergradient, liquid-logo,
liquid-glass-js, react-three-fiber - used as architecture/design inspiration only.
v2.0 research set: OpenTasker, Easer, AutoX (+2 forks), argus, MobileAgent - mechanisms credited
in CHANGELOG 2.0.0 (Shizuku getevent capture = AutoX's root technique; trigger engine shape =
OpenTasker/Easer; `{{title}}`/`{{text}}` dynamics = Easer DynamicsLink; capability-bus discipline
= argus). No source code was copied from any of them.
