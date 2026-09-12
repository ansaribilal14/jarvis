# JARVIS

**A local-first AI agent that lives on your phone and operates it for you.**

JARVIS is not a chatbot with buttons and not a wrapper around a cloud API. It is a genuine
agent loop running on-device: it **understands** your request, **observes** the current
screen state through Android Accessibility, **plans** the required actions, **acts** with
semantic UI handles, **verifies** what actually happened, **adapts** when something fails,
and **reports honestly** — all without requiring a paid AI service, a PC, a server or Termux.

```
USER REQUEST
    ↓
UNDERSTAND → OBSERVE → PLAN → ACT → OBSERVE → VERIFY
                                 ↘  FAILED?  ↙
                                  DIAGNOSE → REPLAN
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
(~25-35 MB); model files are downloaded separately inside the app.

## What's new in v1.1.0

- **Model activation, fixed and rebuilt.** The Activate button now shows live feedback:
  progress while the model loads into memory, an ACTIVE chip the moment it is ready, and a
  clear reason if activation fails (out of RAM, corrupted file, missing file). Checksum
  verification no longer runs on the UI thread, double-taps are guarded, and a finished
  download auto-activates the model — one tap from download to ready.
- **Import your own .gguf models.** Imported files are listed and activatable.
- **Telegram remote control** (adapted from the [MobileAgent](https://github.com/Bilal140202/MobileAgent)
  demo): create a bot with @BotFather, paste the token in Settings → Telegram remote control,
  message the bot once to bind your chat, then run tasks from anywhere with plain text,
  `/status` and `/stop`. The token is stored in the encrypted vault and only your bound chat
  can issue commands.
- Onboarding now explains the Android 13+ "Allow restricted settings" accessibility quirk
  and auto-advances when the model is live.

## What JARVIS can actually do

| Capability | How it works |
|---|---|
| Natural-language phone control | On-device llama.cpp planner picks ONE registered tool per step from 30+ real tools |
| Screen understanding | Accessibility tree → compact structured element list (semantic handles, not blind coordinates) |
| Phone automation | tap / type / scroll / back / home / open app / launch intents — app-agnostic, works on any app |
| Device controls | brightness (verified), volume, flashlight, Wi-Fi & Bluetooth (panel-assisted on Android 10+ — honest about platform limits) |
| Voice | push-to-talk STT (on-device recognizer requested) + local TTS status speech |
| Memory | local facts, task history, conversation — inspectable, editable, deletable, fully disableable |
| Routines | WorkManager-scheduled daily instructions, LOW-risk-only policy |
| Notifications | NotificationListener ring buffer with local classification; OTP-like texts never stored |
| Quick access | Quick Settings tile + draggable overlay bubble |
| Safety | risk classification, mandatory confirmations, prompt-injection defense, password/OTP field policy, action budgets, global STOP |
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
