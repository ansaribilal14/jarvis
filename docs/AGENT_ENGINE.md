# Agent Engine

## The loop (actual code path)
0. **DIRECT fast path** - strict whole-utterance regex (read/show/check/list/see + "notifications", <=48 chars) runs `read_notifications` directly: no router, no LLM, 10s timeout, route label `DIRECT`. Requests with extra clauses use the full loop below.
1. **PLAN** (compound goals only) - `Planner.isCompoundGoal` heuristics detect multi-step requests ("open X, then Y, and Z"). One plan generation produces an explicit JSON plan (max 6 steps, invalid steps dropped). Steps are queued and threaded into every subsequent decision as the plan note; executing out of order reports "Adapted away from plan step" honestly.
2. **OBSERVE** - JarvisAccessibilityService.observe() walks the accessibility tree (bounded: 320 nodes/48 depth) into ScreenObservation with stable element indices, `@(centerX,centerY)` coordinates and short viewIds. InjectionGuard scans it. A DEVICE NOW line (clock, battery, charging, thermal) is added.
3. **DECIDE** - ModelRouter picks LOCAL (llama.cpp) / REMOTE (NVIDIA NIM when API mode is on) / RULES (deterministic regex planner). Planner builds a strict system prompt: JSON-only output contract, tool catalog from the live registry, screen block as DATA, injection warnings, user facts, plan note. Generation carries stop sequences so prompt-echo ends early. **Compact mode** (sub-1.2B models, or imports <900 MB): short prompt + 1-shot example, names+required-args catalog, 22-element screen block, 130-token cap.
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

## Live progress (glass box)
- Every generation streams state: phase (reading context x/y / writing), token counts, tok/s, elapsed - shown as Step x/N + elapsed chips, a LIVE MODEL OUTPUT card (last ~220 chars) and a 60-entry ACTIVITY feed in the UI, mirrored at 1 Hz into the foreground notification and Telegram `/status`.
- Slow-start warning after 90s with 0 output tokens (fires during prefill too).
- Exactly one synchronized writer commits engine state (no lost-update races).

## Honest outcomes
Task status is COMPLETED / PARTIAL / FAILED / STOPPED / BLOCKED. Responses may say "could not verify". The engine never reports success from a tap alone. When the rule engine takes over, the fallback message states the true model state (loaded-but-timed-out / set-but-reloading / file-present-but-inactive / no model) - never a generic "offline" claim.
