# Changelog

All notable changes to JARVIS. Versions are tagged on GitHub Releases; the release APK
is attached to each release and delivered via Telegram.

## [2.1.0] - Built-in privileged setup (no other app) + live recording visualization + explicit record start

- **Shizuku setup is now INSIDE the app - no second app needed.** JARVIS pairs with the phone's
  OWN Wireless debugging (Android 11+) from within the Skills screen and starts its own
  shell-identity server (`core/adb/SelfHostServer`) via `app_process` - running as uid 2000,
  the same identity the Shizuku server uses, but launched by JARVIS itself through an in-app
  ADB connection:
  - `AdbKeyStore`: per-install RSA-2048 keypair + hand-rolled DER self-signed X509 (no
    BouncyCastle), persisted app-privately; the Android public-key line rides the pairing
    PeerInfo, so adbd permanently trusts JARVIS after one pairing.
  - `AdbPairing`: the AOSP pairing handshake - TLS keying material export ("adb-label"),
    SPAKE2 over the 6-digit pairing code (BoringSSL via the `io.github.vvb2060.ndk:boringssl`
    prefab, JNI in `spake2_jni.cpp`), AES-128-GCM PeerInfo exchange. Adapted from
    wuyr/jdwp-injector-for-android (Apache-2.0).
  - `AdbClient`: CNXN -> STLS -> TLS 1.3 with the generated client identity, `exec:` services
    for one-shot commands (adapted from wuyr, Apache-2.0).
  - `SelfHostShell`: mDNS (NSD) auto-discovery of both wireless-debugging ports (user types
    only the pairing code, manual port fallback included), server lifecycle (kill stale ->
    start with a random memory-only token -> token handshake over 127.0.0.1), exec/stream
    data plane. The token is never persisted; the socket is loopback-only.
  - The external Shizuku app stays as an opt-in alternative (collapsed row) - both transports
    feed the same `PrivilegedShell` facade (`core/shizuku/PrivShell.kt`), so the recorder never
    depends on a concrete one.
- **Recording visualization ("show the touches and drag")**: the same decoded touch stream now
  drives a live ink overlay (`service/RecordingInk.kt`) - a glowing fingertip dot, red ink
  trails for every drag, burst-and-fade for every tap, drawn edge-to-edge over any app while
  recording. The overlay is NOT_TOUCHABLE (passthrough, zero interference) and only renders
  what the precision stream sees. `TouchStreamAnalyzer` gained `Move` primitives (6px
  emission threshold, re-armed per contact, first-finger only) with unit tests.
- **Explicit record start**: "Record a skill" now asks WHERE to start - Home screen or any
  launchable app from a dropdown (`RecordStartDialog`). On Start: recorder arms first, JARVIS
  navigates home / launches the app, and an auto-fading instruction card ("Recording
  everything - every tap and swipe is recorded and drawn as red ink") explains the state.
  HomeScreen's recorder chip routes through the same dialog.
- **Honest status everywhere**: the REC bubble and Skills screen now say WHICH transport is
  live ("PRECISION (built-in shell)" vs "PRECISION (Shizuku)") and the ink visualization is
  mentioned in the recording status.
- **Tests**: Move-primitive emission (threshold, re-arm, second-finger silence) and the
  self-signed certificate builder (parses through CertificateFactory, signature verifies,
  v3 + long validity, PKCS8 round-trip).

## [2.0.0] - The overhaul: root-free Tasker-grade recording, trigger engine, floating console, Material 3 Expressive

*Research base: OpenTasker, Easer, AutoX (3 forks), argus and the MobileAgent demo were cloned and
studied line-by-line; the mechanisms below credit them explicitly.*

- **Skill recorder v3 - "records nothing" is structurally impossible now.** v1.9/v1.10 tried to get
  tap coordinates from framework touch-interception APIs (`motionEventSources`, then a
  TouchInteractionController gated on `FLAG_REQUEST_TOUCH_EXPLORATION_MODE`); the first consumes
  touches, the second depends on touch-exploration semantics that most ROMs never deliver without
  hijacking the tap. Both mechanisms are **deleted**. The precision layer is now the same technique
  AutoX uses with root, made root-free through **Shizuku** (the argus capability bus): a
  `getevent -t` stream read under the shell identity observes every kernel touchscreen contact
  with exact coordinates, in every app (games, canvases, WebView), while consuming nothing - the
  phone feels 100% identical while recording. The accessibility-event layer (clicks, typing,
  scrolls, app switches) stays on as a never-silenced fallback, and the pure-JVM `RawTapMerger`
  still fuses both into semantic steps.
- **Shizuku bridge** (`core/shizuku/`): honest 4-state machine (not installed / not running / not
  authorized / ready), one-tap setup card on the Skills screen (Get Shizuku -> Connect), argv-array
  commands only. Touch devices are probed via `getevent -pl`; raw digitizer units are scaled to
  screen pixels with the scrcpy ScreenMetrics approach.
- **Self-diagnosing REC bubble** (AutoX-style floating console): while recording, a draggable
  always-on-top pill shows a LIVE step counter over any app - every captured tap increments it, so
  "is it recording?" is answered at a glance from any app; tapping it expands the honest
  capture-layer status and a Stop button.
- **Trigger engine (Easer/OpenTasker grade)**: skills can now fire themselves - APP_OPEN /
  APP_CLOSE (rides the accessibility window stream), TIME (exact `AlarmManager.setExactAndAllowWhileIdle`,
  honest inexact fallback without the permission), NOTIFICATION (the existing
  NotificationListenerService, with `{{title}}`/`{{text}}` dynamics substituted into typed steps,
  the Easer DynamicsLink pattern), and BATTERY_LOW (system broadcast). Cooldowns, agent-busy
  guard, boot/update re-arm (`BootReceiver`), and a RECENT TRIGGERS run log on the Skills screen
  (OpenTasker's RunLog attribution, mini).
- **Skill model extended, backward compatible**: optional `trigger` on `SkillDefinition`
  (`@Serializable` default null - old JSON files load untouched); full trigger editor in the
  skill draft screen.
- **Material 3 Expressive UI**: expanded tonal scheme (primary container / tertiary "plasma
  violet" / surface container tiers), dynamic color on Android 12+ (Material You) with the JARVIS
  brand as fallback, expressive shape geometry (18-32dp large radii), tightened emphasis
  typography ladder. The whole app inherits via `MaterialTheme.shapes` + scheme with zero
  call-site churn.
- **New logo**: the agent graph - a glowing core with an attention arc and three orbital nodes
  (observe / decide / act) on a deep-space gradient, adaptive + monochrome (themed icons) layers.
- **Tests**: 6 new JVM cases for the touch-stream codec (line parsing, device probe, tap
  classification, unit->pixel scaling, multi-slot first-finger policy, flush, swipe movement).
- CI/release artifacts unchanged (signed APK + SHA-256 + Telegram delivery).

## [1.10.0] - Recorder actually fixed: precision touch capture (the v1.9 mechanism was broken)

- **Honest root cause of "records nothing".** v1.9's raw-touch layer used
  `AccessibilityServiceInfo.motionEventSources` - but per the Android framework docs that API
  **consumes** touchscreen events instead of observing them ("MotionEvents from sources in
  getMotionEventSources() are not sent to the rest of the system"), and a runtime
  `setServiceInfo` update never took effect on real devices. Worse, the recorder flagged raw
  capture as "on" optimistically and that flag **suppressed the working event fallback** - so on
  an Android 14+ device both layers ended up recording nothing. This release removes that
  mechanism entirely and never lets one layer silence the other.
- **Precision touch capture (Android 14+), the safe way.** While recording, the service registers
  a `TouchInteractionController` (the TalkBack-grade touch pipeline) and on every touch DOWN
  records the exact screen coordinates, then IMMEDIATELY delegates the interaction back to the
  system - the user's tap reaches the app completely untouched. Result: every single tap is
  captured with real coordinates in EVERY app - games, canvases, WebView, launchers - exactly
  like a macro recorder. The touch-exploration capability is declared in the service config;
  interposition is active ONLY while a recording is running and is reverted first on stop, on
  unbind, on any error.
- **Fusion instead of suppression.** A pure-JVM `RawTapMerger` fuses the layers: a raw DOWN plus
  a matching click/long-click event within 1.5 s / 48 px becomes ONE semantic step (labels +
  raw coordinates); an unclaimed raw DOWN commits as a coordinate TAP; a scroll event cancels its
  fling's DOWN so no bogus tap is recorded; a late label event replaces the coordinate-only step.
- **Guaranteed baseline layer.** App-event capture (TYPE_VIEW_CLICKED / LONG_CLICKED / TEXT /
  SCROLL / APP_OPEN) now works on every device and is never disabled by an unverified flag.
- **Honest UI status.** The REC card now shows the live capture mode - "Precision capture ON -
  N touches seen (every tap, in every app)" vs "App-event capture ..." - so "is it recording?"
  is always answerable at a glance; a warning appears when the accessibility service is off.
- Tests: 8 new JVM cases covering merge, window commit, late replace, scroll-cancel, rapid
  double taps and flush.

## [1.9.0] - Tasker-grade skill recorder, MobileAgent-style flat action loop, any-provider API mode

- **Skill recorder rebuilt: records EVERY tap (Tasker-grade).** The old recorder depended on
  TYPE_VIEW_CLICKED accessibility events, which many apps and ROMs simply never emit - so on real
  devices the recorder often captured nothing. Now, on Android 14+, the accessibility service
  observes the raw touchscreen (`motionEventSources` + `onMotionEvent`): every physical down/move/up
  is classified into TAP / LONG_PRESS / SCROLL with real screen coordinates, in every app, including
  games, canvases and WebView surfaces that hide from accessibility. A light tree lookup then
  enriches each raw step with the element's labels so replay stays semantic-first with coordinates
  as ground truth. Typing still comes from TYPE_VIEW_TEXT_CHANGED (raw touch cannot read text),
  password fields stay masked, app switches come from window transitions.
- **Recordings survive process death.** The recording session is persisted (active flag + a JSONL
  step file); if an aggressive ROM kills JARVIS while you are recording in another app, the service
  resumes the session on rebind - and pressing "Stop" after such a kill still delivers the captured
  steps instead of silently discarding them. Step cap raised 60 -> 120.
- **MobileAgent-style flat action loop.** The decide stage now speaks the same tiny contract that
  makes the demo MobileAgent project so effective: the screen lists interactive elements with
  `center=(x,y)`, and the model answers ONE flat JSON action -
  `{"type":"tap","x":540,"y":148}`, `type_text`, `scroll`, `button` (back/home/recents),
  `open_app`, `double_tap`, `wait`, `done` - with device tools (wifi, alarms, calls, ...) reachable
  through `{"type":"tool","name":"...","args":{...}}`. The prompt shrinks to the bone (compact tool
  catalog instead of 45 full tool specs), taps run as verified coordinate gestures, and
  `type_text` prefers the FOCUSED field exactly like MobileAgent. The legacy nested contract and
  the whole salvage pipeline still parse - nothing regressed for big models.
- **Any-provider API mode (OpenRouter / DeepSeek / NIM / Ollama / custom).** The API-mode sidebar
  now has provider presets: OpenRouter (free `:free` models - the same free-cloud-brain route the
  demo app uses), DeepSeek, NVIDIA NIM, Ollama on your LAN, or any custom /v1 endpoint. OpenRouter
  requests send the recommended identification headers. The provider is generic under the hood;
  presets just fill endpoint + model picks + key hints.
- **New tools + richer replay.** `press_recents` and `press_notifications` join the registry; the
  scroll tool and skill replay now understand left/right swipes (pagers, galleries, stories).
- **Flat-contract test coverage.** New JVM suite for the flat parser (all 10 action shapes,
  scroll directions, button mapping, unknown-tool honesty, legacy fallback) and the raw-touch
  stroke classifier (tap/long-press/scroll/multi-pointer-ignore).

## [1.8.0] - grammar-constrained decoding, gesture completion, state machine

- **GBNF grammar-constrained decoding (local route).** The planner's decide stage and the
  plan-first stage now generate a GBNF grammar from the live tool registry and hand it to the
  llama.cpp sampler (`llama_sampler_init_grammar`, added first in the chain). The model can only
  emit tokens that keep the output inside the JSON contract: valid JSON, tool names restricted to
  registered tools, arg keys pulled toward real spec params (a generic key stays allowed so nothing
  dead-ends), final-response form included. Prose, markdown fences, echoed screen blocks and
  hallucinated tools become syntactically impossible - the v1.6.0 small-model failure class is
  closed at the sampler level, with the salvage pipeline kept as the second net. Remote/API mode is
  unaffected (the grammar is a native-runtime feature).
- **Gesture-completion callbacks.** `tapAt` / `doubleTapAt` / `longPressAt` / `swipe` / `scrollScreen`
  now suspend until the accessibility service reports the gesture actually completed
  (`GestureResultCallback`, 4s timeout guard), instead of returning "dispatch accepted" and guessing
  with fixed delays. Skill replay and agent verification now measure real strokes.
- **Agent runtime state machine.** New `AgentStateMachine` defines the legal status transitions;
  every engine status commit is validated (fail-open: unexpected transitions log loudly and still
  apply, so a live task can never wedge on a matrix edge case).
- **Grounding-core test coverage.** New JVM suites: UiResolve (resolution priority ladder),
  RiskClassifier (escalation + confirmation policy), AgentStateMachine (matrix invariants),
  DecisionGrammar (rule completeness, contract shapes, balanced syntax, size sanity).

## [1.7.0] - skill recorder, /grill-me, expert-review hardening

- **Skill recorder.** Tap "Record a skill", use your phone normally, stop - your taps,
  typing, scrolling and app switches are captured as replayable steps (60-step cap,
  password fields masked, JARVIS's own actions never recorded). An ongoing notification
  shows the live step count with a Stop action.
- **One-tap replay.** Saved skills run deterministically with the full safety net:
  per-step fresh observation, semantic re-match (resource id → label → description →
  recorded coordinates as last resort), one retry, fingerprint verification, honest
  partial/failed outcomes - and the same risk ladder as agent actions (typing confirms
  first, finance apps always confirm).
- **/grill-me.** Before a recording becomes a skill, JARVIS interviews you - up to 5
  targeted, step-by-step questions (which app it assumes, what varies per run, failure
  behavior, what must never happen without asking) - then writes the full skill draft:
  name, description, guardrail notes, refined steps. You review/edit/iterate before
  saving. Works on the local model, API mode, or a deterministic question ladder with
  zero AI.
- **Skill editor.** Every step editable: retype the match label, move coordinates,
  insert WAIT/SCROLL steps, reorder, delete. Nothing persists until you save.
- **Expert-review hardening** (independent code review vs. 2025 app-agent practice):
  - Benchmark during a task could run two native generations on one context (SIGSEGV
    class) - benchmark now holds the same single-flight guard as generate.
  - Oversized prompts trimmed from the HEAD, deleting the output contract first
    (the v1.6.0 echo symptom on tiny contexts) - trimming now keeps head + tail and
    cuts the middle, with a native log line.
  - Rule engine turned "open chrome and set an alarm" into open_app("chrome and set
    an alarm") - now anchored + compound-guarded.
  - Task-slot race (Telegram + voice + routine) is now a CAS - the loser is refused,
    never silently dropped.
  - Imported .gguf filenames are sanitized (no path escapes), never overwrite, and a
    storage precheck prevents truncated junk files.
  - Remote read timeout (75s) now sits below the engine deadline (90s) so timeouts
    cancel instead of stacking.
  - CI now builds the minified release variant on every push - the R8/JNI bug class
    fails before a release publishes, not after.

## [1.6.1] - model state honesty + restart resilience

- **Auto-reload after process death.** The last active model is now restored on app start
  (catalog or imported, checksum-verified). Previously every app restart silently dropped
  JARVIS back to rule mode even though the model file was still on disk.
- **State-aware fallback messages.** The rule-engine fallback no longer hardcodes
  "no local model downloaded yet" — it reports the true state: loaded-but-timed-out,
  set-but-reloading, file-present-but-inactive, or genuinely no model, with the cause.
- v1.6.0's changes ship inside this release (the v1.6.0 tag was skipped).

## [1.6.0] - small-model automation reliability

- **Native stop sequences.** `nativeComplete` accepts stop strings; the decode loop
  re-scans a small tail window after every token, truncates at the earliest match and
  trims the marker. Prompt-echo outputs now end in seconds instead of burning the whole
  token budget (each wasted round used to cost 2–4 minutes on a small model).
- **Salvage parsing pipeline.** Every JSON candidate is tried: key aliases
  (`nextAction`/`next_action`/`tool_call`/`function`/`step`, `arguments`/`parameters`),
  ~60-entry tool-name alias map (`click`→`tap`, `yt_search`→`youtube_search`, …),
  bare-string args bound to the single required param, regex salvage of flat JSON
  fragments in echoed noise, and auto-repair of truncated output (open strings/braces
  closed, dangling commas stripped).
- **Compact mode for sub-1.2B models.** Short system prompt with a 1-shot example,
  names+required-args tool catalog, and a tightened screen block (22 elements, 24-char
  texts). Keeps tiny models inside their tiny output budgets.
- **Rule-engine rescue.** After 2 consecutive unparseable local outputs the deterministic
  planner takes the step instead of burning a third multi-minute LLM round.
- 8 new planner JSON tests modeled on real failing outputs from a 0.5B model.

## [1.5.0] - release-build fixes, faster inference, NVIDIA NIM API mode

- **"Test model" fixed in release builds.** Root cause: R8 minified the JNI progress
  listener so `GetMethodID("onProgress")` failed after decode — every generation was
  discarded. ProGuard now keeps the model layer and the `onProgress(IIII[B)V` signature,
  and the native callback is optional (`ExceptionCheck` after lookup), so a lookup miss
  can never discard a finished generation again.
- **Automatic performance-core threading.** On `inferenceThreads=0` the app reads
  per-core max frequencies, counts big cores (≥85% of the fastest), and uses that for
  llama.cpp threads (clamped 1–6, fallback 4) instead of blindly using all cores − 2.
- **Two new fast catalog models:** SmolLM2-360M-Instruct Q8_0 (386 MB, ultra-fast for
  BASIC devices) and Llama-3.2-1B-Instruct Q4_K_M (807 MB, the sweet spot for 4 GB RAM).
- **API mode sidebar (NVIDIA NIM).** Slide-out drawer: paste an API key, "Test & save",
  flip API mode on — tasks run on the chosen hosted NIM model immediately, with graceful
  fallback to local/rules if the API fails. Key stored in EncryptedSharedPreferences.

## [1.4.0] - plan-first compound goals, contacts, media & system actions

- **Plan-first execution.** Compound goals ("open X, then find Y, and send Z") get an
  explicit ≤6-step plan generated up front; each step is still executed grounded against
  a fresh observation, and deviations are reported honestly ("Adapted away from plan").
- **Contacts & communication tools** (human presses send on drafts): `call_contact`,
  `send_sms`, `whatsapp_message`, `telegram_message`, `send_email` — with a local contact
  resolver (exact → LIKE → word-start → nickname map incl. dad/mom/bhai/papa; ambiguous
  matches list candidates instead of guessing).
- **Media, navigation & system tools:** `open_url`, `youtube_search`, `maps_navigate`,
  `set_alarm`, `set_timer`, `media_play_pause`, `take_screenshot` (API 30+ global
  action), `toggle_dnd` (policy-access aware).
- **Device awareness.** Every decision prompt now carries a DEVICE NOW line (clock,
  battery %, charging, thermal) so the model stops proposing impossible actions.
- Contacts permission added to onboarding.

## [1.3.2] - instant notification reads + Test model button

- **Direct fast path.** "Read my notifications" (and close variants) is matched
  strictly and executed with zero AI — no router, no LLM, 10s timeout, route label
  DIRECT. Tasks with extra clauses still use the full agent loop.
- **Test model button** on every model card: runs one real 48-token generation and
  reports elapsed, tokens and tok/s, success or failure — no more guessing whether a
  model actually works.
- Loading or unloading a model while a task is generating is refused (the last
  use-after-free hole of this class, closed).

## [1.3.1] - the "stuck at Waking the model" fix

- **Use-after-free eliminated.** The idle watchdog unloaded the model mid-decode
  (freeing it under a live native call), freezing tasks forever. `unload()` now refuses
  while generating; `lastUsed` is refreshed on every generation; loads are refused while
  inference is in flight.
- **Hard deadlines on every LLM call** (240s local / 90s remote): on timeout the
  generation is cancelled and the task honestly falls back to the rule engine — a task
  can never hang forever.
- Slow-start warning now fires during long prefill too, and the first progress
  callback arrives immediately after tokenize ("Reading context 0/N").

## [1.3.0] - grounded task execution + anti-hallucination + unlimited actions

- **Screen grounding.** Every observed element carries `@(centerX,centerY)` coordinates
  and a short viewId; `tap` gained a coordinate fallback for undescribed surfaces;
  `double_tap` added; typing taps the focused field first and retries.
- **Execution discipline.** Prompt rule blocks (GROUNDING / EXECUTION DISCIPLINE /
  SAFETY), `after` notes from the verifier fed into the next decision, repeat guard
  (3× identical action → honest stop), consecutive-failure give-ups, prose-only model
  outputs rejected and retried with a corrective warning.
- **Unlimited mode.** Action budget 0 = unlimited (UI shows "Step N · unlimited");
  default budgets unchanged.

## [1.2.0] - live progress everywhere

- The agent became a glass box: streaming generation state (phase, prompt progress,
  token count, tok/s), a LIVE MODEL OUTPUT card, a 60-entry ACTIVITY feed, step x/N and
  elapsed chips in the app, progress in the foreground notification, and inference
  detail in Telegram `/status`. One honest writer per state commit (lost-update race
  removed); a 1 Hz heartbeat folds generation state into the "Thinking" detail.

## [1.1.0] - model activation rebuilt + Telegram remote control

- Activate button fixed: live loading feedback, reactive ACTIVE chip, real failure
  reasons, checksum off the UI thread, double-tap guarded, download auto-activates.
- Import your own .gguf files (SAF); imported models are listed and activatable.
- Telegram remote control: bind your chat once, then run tasks, `/status` and `/stop`
  from anywhere. Token in the encrypted vault; only the bound chat can command.

## [1.0.0] - initial release

- Local-first agent loop (understand → observe → plan/decide → act → verify → adapt),
  on-device llama.cpp (arm64-v8a), 30+ registered tools, risk classification with
  confirmations, prompt-injection defense, password/OTP policy, local memory (Room),
  voice in/out, OCR fallback, routines, diagnostics (Jarvis Doctor), Quick Settings
  tile + overlay bubble, GitHub Actions CI with signed release delivery via Telegram.
