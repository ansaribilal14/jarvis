# Agent Engine

## The loop (actual code path)
0. **DIRECT fast path** - strict whole-utterance regex (read/show/check/list/see + "notifications", <=48 chars) runs `read_notifications` directly: no router, no LLM, 10s timeout, route label `DIRECT`. Requests with extra clauses use the full loop below.
0b. **SKILL route** - saved skills replay deterministically (route label `SKILL`): per-step fresh observation, semantic re-match then recorded coordinates, one retry, fingerprint verification, typing-class steps confirm via the same risk ladder, a step failing twice aborts honestly. Recorded via the in-app skill recorder; refined via /grill-me.
1. **PLAN** (compound goals only) - `Planner.isCompoundGoal` heuristics detect multi-step requests ("open X, then Y, and Z"). One plan generation produces an explicit JSON plan (max 6 steps, invalid steps dropped). Steps are queued and threaded into every subsequent decision as the plan note; executing out of order reports "Adapted away from plan step" honestly.
2. **OBSERVE** - JarvisAccessibilityService.observe() walks the accessibility tree (bounded: 320 nodes/48 depth) into ScreenObservation with stable element indices, `center=(x,y)` coordinates and short viewIds. InjectionGuard scans it. A DEVICE NOW line (clock, battery, charging, thermal) is added.
3. **DECIDE** - ModelRouter picks LOCAL (llama.cpp) / REMOTE (OpenRouter / DeepSeek / NVIDIA NIM / Ollama / custom when API mode is on) / RULES (deterministic regex planner). Planner builds a strict system prompt around the **flat action contract (v1.9)**: `\{"type":"tap","x":540,"y":148\}` / `type_text` / `scroll` / `button` / `open_app` / `double_tap` / `wait` / `done`, with device tools reachable as `\{"type":"tool","name":"...","args":{...}\}` - the tiny schema that lets even 0.3B models answer cleanly. The full 45-tool catalog stays one key away instead of drowning the prompt. Generation carries stop sequences so prompt-echo ends early. **Compact mode** (sub-1.2B models, or imports <900 MB): short prompt + 1-shot example, names+required-args catalog, 22-element screen block, 130-token cap. **On LOCAL, generation is grammar-constrained** (v1.8): a GBNF built from the live registry makes the output contract unbreakable at the sampler level - see below.
4. **SALVAGE** - before anything is rejected, every JSON candidate goes through the repair pipeline: all balanced objects extracted (not just the first), key aliases (`nextAction`/`next_action`/`tool_call`/`function`/`step`; `arguments`/`parameters`/`params`), ~60-entry tool-name alias map (`click`->`tap`, `yt_search`->`youtube_search`, `open`->`open_app`, ...), bare-string args bound to the single required param, regex salvage of flat JSON fragments in echoed noise, and truncated-output repair (open strings/braces closed, dangling commas stripped).
5. **VALIDATE** - the salvaged decision is validated against the registry (tool exists, required args present, canonical registry name carried on PlannedAction). Hallucinated tools are rejected.
6. **CONFIRM** - RiskClassifier escalates risk by context (typing in a messaging app = MEDIUM; any interactive action in a finance app = HIGH). ConfirmationManager shows WHAT/TARGET/DETAILS/WHY via dialog + heads-up notification with Confirm/Deny actions.
7. **ACT** - Executor runs the tool with a 30s timeout. Tools re-resolve semantic targets against a FRESH observation (stale indices are re-matched by identity; coordinate tap is the last resort).
8. **VERIFY** - Verifier maps tool results to VERIFIED / UNVERIFIED / FAILED / COULD_NOT_VERIFY and produces an `after` note that is fed into the next decision. The planner can never declare success by itself.
9. **ADAPT** - failures feed back as observation + verdict; the model (or rules) replans. Guards: repeat detection (3 identical tool+args -> honest stop), consecutive failures >=5 -> give-up, unparseable local outputs >=2 -> **rule rescue** (DeterministicPlanner takes the step), LLM call deadlines (240s LOCAL / 90s REMOTE) -> cancel + honest fallback.

## Budgets & control
- Action budget: default 12, user-configurable (6/12/20 or **0 = unlimited** - UI shows "Step N · unlimited"). 30s tool timeout, 120s confirmation timeout (decline = safe stop).
- Global STOP cancels the coroutine, native generation (cancel flag in JNI), pending confirmations, and the foreground service.
- User-override detection: touch events during ACTING/VERIFYING force a fresh observation before the next action.
- Gestures report REAL completion (v1.8): `tapAt`/`doubleTapAt`/`longPressAt`/`swipe`/`scrollScreen` await the accessibility service's `GestureResultCallback` (4s timeout guard) instead of fire-and-forget plus fixed delays.

## Grammar-constrained decoding (v1.8, LOCAL route)
`DecisionGrammar.decisionGrammar()`/`planGrammar()` build a GBNF from the live tool registry and hand it to the native sampler (`llama_sampler_init_grammar`, added FIRST in the chain so it masks logits before top_p/temp/dist). Consequences:
- The output can only be the contracted JSON shapes (nested `action` form, compact flat `tool/args` form, or the final `response` form, with optional `thought`).
- `tool` can only be a registered tool name (hallucinated tools are syntactically impossible).
- Arg keys are pulled toward real spec params; a generic string key stays allowed so an underspecified arg can never dead-end the decode.
- Prose, markdown fences and echoed screen blocks cannot be emitted - the v1.6.0 small-model failure class is closed at the sampler level. The salvage pipeline remains as the second net (grammar built null = unconstrained on any surprise).
- Syntax only, not probabilities: a good model output is unchanged; a sloppy one is repaired token-by-token. Remote/API mode is unaffected.

## Flat action contract (v1.9)
The decide stage speaks the MobileAgent-style flat schema - screen lines carry `center=(x,y)` and the model answers one flat JSON object:
`tap` / `long_press` / `double_tap` (by `x`,`y` or `text`), `type_text`, `scroll` (`up|down|left|right`), `button` (`back|home|recents`), `open_app`, `wait`, `tool` (registered device tools by name+args), `done` (final summary). Consequences:
- Taps execute as verified coordinate gestures - they work in every app (games, canvases, WebView), not only where `performAction` succeeds.
- `type_text` prefers the FOCUSED field (tap first, then type) exactly like the demo MobileAgent project; custom-editor fallback (tap-then-set) remains.
- The parser tries the flat schema first, then the legacy nested contract, then the full salvage pipeline - big remote models and old prompts keep working.
- The GBNF grammar covers the flat shapes, so the local route can only emit contracted actions.

## Runtime state machine (v1.8)
`AgentStateMachine` defines the legal status transitions (IDLE/THINKING/ACTING/VERIFYING/WAITING_CONFIRMATION/COMPLETED/FAILED/STOPPED). Every engine status commit is validated: unexpected transitions are logged loudly ("state-machine: X -> Y") and still applied - fail-open, so a live task can never wedge on a matrix edge case, but UI-state bugs surface in logcat instead of hiding. Terminal states cannot flow into VERIFYING/WAITING_CONFIRMATION; they re-enter work only via a new task start.

## Live progress (glass box)
- Every generation streams state: phase (reading context x/y / writing), token counts, tok/s, elapsed - shown as Step x/N + elapsed chips, a LIVE MODEL OUTPUT card (last ~220 chars) and a 60-entry ACTIVITY feed in the UI, mirrored at 1 Hz into the foreground notification and Telegram `/status`.
- Slow-start warning after 90s with 0 output tokens (fires during prefill too).
- Exactly one synchronized writer commits engine state (no lost-update races).

## Honest outcomes
Task status is COMPLETED / PARTIAL / FAILED / STOPPED / BLOCKED. Responses may say "could not verify". The engine never reports success from a tap alone. When the rule engine takes over, the fallback message states the true model state (loaded-but-timed-out / set-but-reloading / file-present-but-inactive / no model) - never a generic "offline" claim.
