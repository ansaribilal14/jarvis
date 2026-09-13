# Changelog

All notable changes to JARVIS. Versions are tagged on GitHub Releases; the release APK
is attached to each release and delivered via Telegram.

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
