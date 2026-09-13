# JARVIS

**A local-first AI agent that lives on your phone and operates it for you.**

JARVIS is not a chatbot with buttons. It is a genuine agent loop running on-device: it
**understands** your request, **observes** the current screen state through Android
Accessibility, **plans** the required actions, **acts** with semantic UI handles,
**verifies** what actually happened, **adapts** when something fails, and **reports
honestly** — by default without requiring any paid AI service, a PC, a server or Termux.
(An optional hosted API mode exists for speed; local-first remains the default.)

```
USER REQUEST
    ↓
UNDERSTAND → [PLAN] → OBSERVE → DECIDE → ACT → OBSERVE → VERIFY
     │            ↑ compound goals get       ↘  FAILED?  ↙
     │               an explicit plan           DIAGNOSE → REPLAN
     └─ simple notification reads skip AI entirely (DIRECT fast path)
    ↓
HONEST RESULT (completed / partial / failed / blocked / could-not-verify)
```

## Install (release APK)

1. Download `jarvis-release.apk` from [Releases](https://github.com/ansaribilal14/jarvis/releases) (also delivered via Telegram).
2. Verify the `SHA-256` checksum file if you want to be thorough.
3. Install (allow "install unknown apps" for your browser/file manager when asked).
4. Open JARVIS → complete onboarding (5 steps) → download the recommended local model →
   enable Accessibility → start using it.

**Requirements:** Android 10+ (minSdk 29), arm64-v8a device. The APK itself is small
(~21 MB); model files are downloaded separately inside the app.

## What's new in v1.6.1

- **Small models finally execute reliably.** Native stop sequences end prompt-echo
  output in seconds, a salvage parsing pipeline accepts the messy JSON small models
  actually emit (key aliases, tool-name aliases, truncated-output repair), compact
  prompts fit sub-1.2B models, and a rule-engine rescue takes over after repeated
  unparseable rounds instead of burning minutes per retry.
- **Restart resilience.** The active model is reloaded automatically on app start, and
  fallback messages state the true model state instead of a hardcoded "no local model
  downloaded yet".
- **API mode (v1.5.0).** Slide out the drawer, paste an [NVIDIA NIM](https://build.nvidia.com)
  key, flip API mode on — tasks run on hosted models instantly, with automatic fallback
  to local/rules. Local stays the default and LOCAL ONLY still hard-blocks remote.
- **Faster local inference (v1.5.0).** Threads are auto-tuned to performance cores, and
  two fast models joined the catalog: SmolLM2-360M (386 MB) and Llama-3.2-1B (807 MB).
- Full history: [CHANGELOG.md](CHANGELOG.md).

## What JARVIS can actually do

| Capability | How it works |
|---|---|
| Natural-language phone control | On-device llama.cpp planner picks ONE registered tool per step from 45 real tools |
| Plan-first compound goals | Multi-step requests get an explicit ≤6-step plan; every step is still grounded against a fresh observation |
| Screen understanding | Accessibility tree → structured element list with semantic handles AND `@(x,y)` coordinates (coordinate tap as last resort) |
| Phone automation | tap / double-tap / type / scroll / back / home / open app / launch intents — app-agnostic, works on any app |
| Contacts & messaging | call / SMS / WhatsApp / Telegram / email via contact resolver with nickname support — messages are drafts, human presses send |
| Media, nav & system | YouTube search, Google Maps navigation, alarms, timers, media play/pause, screenshots, Do-Not-Disturb |
| Device controls | brightness (verified), volume, flashlight, Wi-Fi & Bluetooth (panel-assisted on Android 10+ — honest about platform limits) |
| API mode | Optional NVIDIA NIM hosted models via in-app drawer; fails closed to local/rules |
| Live progress | Streaming generation state, LIVE MODEL OUTPUT card, ACTIVITY feed, notification progress, Telegram `/status` |
| Voice | push-to-talk STT (on-device recognizer requested) + local TTS status speech |
| Memory | local facts, task history, conversation — inspectable, editable, deletable, fully disableable |
| Routines | WorkManager-scheduled daily instructions, LOW-risk-only policy |
| Notifications | NotificationListener ring buffer with local classification; OTP-like texts never stored; "read my notifications" runs with zero AI |
| Quick access | Quick Settings tile + draggable overlay bubble |
| Safety | risk classification, mandatory confirmations, prompt-injection defense, password/OTP field policy, action budgets (incl. unlimited), global STOP |
| Diagnostics | Jarvis Doctor health report with fixes for every degraded capability |

## The honest limits (by design)

JARVIS never fakes capability. If something is not verifiable it says **UNVERIFIED** or
**COULD_NOT_VERIFY** — never a bare "Done." Known platform realities:

- Android 10+ blocks silent Wi-Fi/Bluetooth toggling for third-party apps → JARVIS opens
  the official panel and verifies the resulting state.
- Android does not allow force-stopping other apps → `close_app` navigates home instead.
- Password/OTP fields are never typed into, copied from, or stored.
- Screen content is untrusted data: injection-style text is detected and treated as data,
  never as instructions.

## Documentation

| Doc | Topic |
|---|---|
| [CHANGELOG.md](CHANGELOG.md) | Full version history |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules, agent loop, inference boundary |
| [AGENT_ENGINE.md](docs/AGENT_ENGINE.md) | Planner / executor / verifier / replanner |
| [LOCAL_AI.md](docs/LOCAL_AI.md) | llama.cpp integration, templates, sampling |
| [MODEL_GUIDE.md](docs/MODEL_GUIDE.md) | Catalog, checksums, recommendations, benchmarking |
| [ACCESSIBILITY.md](docs/ACCESSIBILITY.md) | Observation, semantic actions, gestures |
| [MEMORY.md](docs/MEMORY.md) | Tiers, retention, redaction |
| [VOICE.md](docs/VOICE.md) | STT/TTS |
| [VISION.md](docs/VISION.md) | OCR fallback |
| [SECURITY.md](docs/SECURITY.md) | Risk system, injection defense |
| [PRIVACY.md](docs/PRIVACY.md) | Local-first guarantees |
| [MCP.md](docs/MCP.md) | Extension path |
| [DEVICE_COMPATIBILITY.md](docs/DEVICE_COMPATIBILITY.md) | Classes, fallbacks |
| [TESTING.md](docs/TESTING.md) | Test matrix, scenarios |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Fixes |
| [RELEASE.md](docs/RELEASE.md) | Build/sign/ship pipeline |

## Building

```bash
./gradlew assembleDebug          # debug APK
./gradlew testDebugUnitTest      # unit tests
```

CI (`.github/workflows/build.yml`) builds on every push; `release.yml` produces a signed
release APK on every GitHub Release and delivers it via Telegram.

## License

MIT for the project code — see [LICENSE](LICENSE). Third-party components and model
licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
