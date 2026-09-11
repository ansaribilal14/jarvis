# Agent Engine

## The loop (actual code path)
1. **OBSERVE** - JarvisAccessibilityService.observe() walks the accessibility tree (bounded: 320 nodes/48 depth) into ScreenObservation with stable element indices. InjectionGuard scans it.
2. **DECIDE** - ModelRouter picks LOCAL (llama.cpp) / REMOTE (optional, blocked in LOCAL ONLY) / RULES (deterministic regex planner). Planner builds a strict system prompt: JSON-only output contract, tool catalog from the live registry, screen block as DATA, injection warnings, user facts.
3. **VALIDATE** - parseDecision() extracts balanced JSON (fence/prose tolerant), validates tool existence and required args against the registry. Hallucinated tools are rejected.
4. **CONFIRM** - RiskClassifier escalates risk by context (typing in a messaging app = MEDIUM; any interactive action in a finance app = HIGH). ConfirmationManager shows WHAT/TARGET/DETAILS/WHY via dialog + heads-up notification with Confirm/Deny actions.
5. **ACT** - Executor runs the tool with a 30s timeout. Tools re-resolve semantic targets against a FRESH observation (stale indices are re-matched by identity).
6. **VERIFY** - Verifier maps tool results to VERIFIED / UNVERIFIED / FAILED / COULD_NOT_VERIFY. The planner can never declare success by itself.
7. **ADAPT** - failures feed back as observation + verdict; the model (or rules) replans. Bounded: max actions per task (default 12, user-configurable), per-step timeouts, no blind retries.

## Budgets & control
- Action budget, 30s tool timeout, 120s confirmation timeout (decline = safe stop).
- Global STOP cancels the coroutine, native generation (cancel flag in JNI), pending confirmations, and the foreground service.
- User-override detection: touch events during ACTING/VERIFYING force a fresh observation before the next action.

## Honest outcomes
Task status is COMPLETED / PARTIAL / FAILED / STOPPED / BLOCKED. Responses may say "could not verify". The engine never reports success from a tap alone.
