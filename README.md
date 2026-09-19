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

## What's new in v2.0.0

- **The recorder, rebuilt on a mechanism that cannot lie to you.** Tap coordinates now come from
  the raw touchscreen stream read through Shizuku (the root-free AutoX technique - observes every
  kernel contact in every app and consumes nothing), the accessibility-event layer stays on as a
  fallback that nothing can silence, and a floating red **REC bubble with a live step counter**
  proves capture works at a glance from any app.
- **Skills can now trigger themselves**: app open/close, daily time (exact alarms), notification
  content (with `{{title}}`/`{{text}}` placeholders), battery low - with cooldowns, boot re-arm
  and a recent-triggers log. MacroDroid/Easer-class automation on top of your recorded skills.
- **Material 3 Expressive UI + new agent-graph logo**, dynamic color on Android 12+.
- Optional **Shizuku** (free, open-source) unlocks tap-by-tap precision everywhere; without it the
  app-event layer still records buttons, typing, scrolls and app switches.

## What's new in v1.10.0

- **The recorder fix that actually fixes it.** v1.9's raw-touch mechanism turned out to consume
  touches and silence the fallback - on Android 14+ it could record nothing at all. v1.10 replaces
  it with precision touch capture: every tap's exact coordinates are recorded in every app while
  your touches still reach the apps untouched, fused with app events into clean semantic steps.
  The REC card now shows live capture status so you always know it is recording.

## What's new in v1.9.0

- **The skill recorder now records EVERY single tap - like Tasker.** Raw touchscreen capture
  (Android 14+) records taps, long-presses and swipes by real coordinates in every app, even ones
  that never emit accessibility click events; typing and app switches still come from the tree.
  Recordings survive the process being killed in the background.
- **MobileAgent-style task loop.** The agent decides with a tiny flat action contract
  (`{"type":"tap","x":540,"y":148}`) over a compact element list with `center=(x,y)` - the same
  approach that makes the MobileAgent demo project punch far above its weight - while keeping
  JARVIS's verification, confirmations and 45 device tools behind a one-key escape hatch.
- **API mode now speaks to any provider.** OpenRouter (free models), DeepSeek, NVIDIA NIM,
  Ollama on your LAN, or any custom OpenAI-compatible endpoint - one preset tap each.

## What's new in v1.8.0

- **Grammar-constrained decoding.** On the local route, the planner's JSON output contract is now
  enforced by the llama.cpp sampler itself (GBNF): the model can only emit tokens that keep the
  output inside the contract - valid JSON, real tool names, no prose, no echoed screen. The whole
  v1.6.0 small-model failure class (echo, markdown fences, invented tools) becomes syntactically
  impossible; the salvage pipeline stays as a second net.
- **Gesture-completion callbacks.** Tap/double-tap/long-press/swipe now await the accessibility
  service's real completion callback (with a timeout) instead of fire-and-forget plus fixed delays -
  replays and agent actions verify actual strokes, not assumptions.
- **Agent runtime state machine.** Every status change passes an explicit transition matrix;
  unexpected transitions log loudly (fail-open) so UI-state bugs surface instead of hiding.
- **Test coverage for the grounding core.** New JVM suites for UiResolve, RiskClassifier, the state
  machine and the grammar generator.
- Full history: [CHANGELOG.md](CHANGELOG.md).

## What's new in v1.7.0

- **Skill recorder.** Tap "Record a skill", use your phone normally, stop - your taps,
  typing, scrolling and app switches become a replayable skill. Run it any time with
  one tap: deterministic replay with per-step verification and honest outcomes.
- **/grill-me.** Before saving, JARVIS interviews you with targeted questions about the
  recording (assumptions, per-run variation, failure behavior, guardrails) and writes
  the full skill draft for you to review, edit and iterate before it saves.
- **Skill editor.** Every step editable before saving - relabel targets, adjust
  coordinates, insert waits/scrolls, delete steps. Skills live on-device as JSON.
- **Expert-review hardening.** An independent review produced 8 fixes shipped here:
  benchmark-during-task SIGSEGV race, head-trim deleting the output contract, the rule
  engine swallowing compound goals into one giant open_app argument, a task-slot race,
  unsafe model-import filenames, remote-timeout stacking, and CI now building the
  minified release variant on every push.
- Full history: [CHANGELOG.md](CHANGELOG.md).

## What JARVIS can actually do

| Capability | How it works |
|---|---|
| Natural-language phone control | On-device llama.cpp planner picks ONE registered tool per step from 45 real tools |
| Plan-first compound goals | Multi-step requests get an explicit ≤6-step plan; every step is still grounded against a fresh observation |
| Screen understanding | Accessibility tree → compact element list with `center=(x,y)` per line; the agent answers flat actions (`tap x/y`, `type_text`, `scroll`, ...) executed as verified gestures |
| Phone automation | tap / double-tap / type / scroll / back / home / open app / launch intents — app-agnostic, works on any app |
| Contacts & messaging | call / SMS / WhatsApp / Telegram / email via contact resolver with nickname support — messages are drafts, human presses send |
| Media, nav & system | YouTube search, Google Maps navigation, alarms, timers, media play/pause, screenshots, Do-Not-Disturb |
| Device controls | brightness (verified), volume, flashlight, Wi-Fi & Bluetooth (panel-assisted on Android 10+ — honest about platform limits) |
| API mode | Optional NVIDIA NIM hosted models via in-app drawer; fails closed to local/rules |
| Skill recorder | Record your actions once, replay any time - semantic re-match + coordinate fallback, per-step verification |
| /grill-me | Built-in requirements interview that turns a recording into a reviewed, editable skill draft |
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
