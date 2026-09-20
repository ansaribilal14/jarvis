# Skills v3 — the MacroDroid-grounded rebuild (v2.3.0)

This document records the research and the architecture decision behind the v2.3.0
rebuild of the skill system. It exists so the reasoning is auditable: four shipped
versions (v1.9, v1.10, v2.0, v2.1) tried four different touch-capture mechanisms and
every one failed on a real device. v2.3 removes the failed foundation instead of
adding a fifth mechanism on top of it.

## 1. What the research actually showed

### MacroDroid (the standard we were asked to match)
- **MacroDroid has no touch recorder.** A macro is built from three lists:
  Trigger(s) → Action(s) → Constraint(s). Nothing in the product observes your
  taps. That is precisely why it "always works": it never promises capture.
- Its UI-interaction action identifies elements by text / view-id / point and
  clicks via the AccessibilityService — and even that is documented to fail in
  apps that hide their controls. MacroDroid survives because capture is never
  load-bearing.
- Community evidence: even dedicated "touch recorder" apps (Clickmate et al.)
  need AccessibilityService with touch-exploration, root, or ADB — and each has
  public reports of recording nothing on some device or app.

### AutoX.js (clicks work everywhere, root-free — for 1.8k stars' worth of users)
- `performAction(ACTION_CLICK)` on nodes found by selectors
  (`automator/.../UiObject.kt:176`, `UiSelector.java:290`).
- Coordinate gestures via `dispatchGesture` + a blocking result latch — the call
  returns only when the gesture really finished
  (`GlobalActionAutomator.kt:202-214`).
- "Wait for element" = 50 ms poll loop with a deadline (`untilFind()` /
  `findOne(timeout)`, `UiSelector.java:163-206`).
- Multi-window scanning via `service.getWindows()` so clicks work in popups and
  split screen (`AccessibilityBridge.java:69-89`).
- The ONLY part of AutoX that needs root is its touch **recorder**
  (`getevent` via `su`, `InputEventObserver.java:110`) — and AutoX users accept
  that recorder is flaky. Clicks themselves need nothing privileged.

### OpenTasker
- Deliberately ships **no UI automation at all**: "No accessibility-service
  automation. No UI scraping and no synthetic taps... every action can honestly
  declare what it will do" (README). Its reliability reputation comes from
  write-then-read-back verification, cooldowns, and typed failures.

### Tasker + AutoInput "Easy Setup" (the closest thing to reliable capture)
- AutoInput captures ONE element per action, not a session — and still has
  public failure reports ("acts like I pressed something else on the screen").

## 2. Root cause of the four failures (post-mortem)

Every version tried to do the one thing Android does not grant unprivileged
apps: observe touches in other apps.

| Version | Mechanism | Why it failed |
|---|---|---|
| v1.9 | `onMotionEvent` via motionEventSources | The API CONSUMES touches; in practice it silently did nothing. The optimistic gate then suppressed the event fallback → "records nothing". |
| v1.10 | TouchInteractionController delegate-on-down | Requires touch-exploration semantics that most ROMs never deliver; TIC is the TalkBack model, not an observer model. |
| v2.0 | `getevent -t` via Shizuku | Works in principle but the user must install + run + authorize Shizuku; if it is not running the recording silently degrades to event capture in apps that emit nothing. Privilege became load-bearing again. |
| v2.1 | In-app ADB pairing (SPAKE2/TLS to own adbd) | Android 11+ only, wireless debugging dies on reboot, pairing friction, connect races at record time — same silent degradation. |

Additional replay-side defects compounded the capture problem: the first
app-open was never recorded, the package guard in `resolve()` was dead code
(`if (inSameApp) obs.elements else obs.elements`), scroll direction was always
"fwd", inter-step pacing was ignored, and password fields replayed the literal
string `[PROTECTED]`.

**Conclusion: session touch-recording cannot be the foundation. The foundation
that works everywhere, on every device, without privileges is: declarative
actions + deliberate single-element targeting + honest verification.**

## 3. The v3 architecture

### 3.1 Data model
```
SkillDefinition {
  id, name, description, notes
  actions: List<SkillAction>        // the program
  trigger: SkillTrigger?            // unchanged trigger engine
  source: BUILT | RECORDED | GRILLED
  lastRunOk: Boolean?, lastRunLog: List<String>
  // legacy: steps: List<SkillStep>? — parsed for pre-2.3 files, migrated on load
}

SkillAction {
  id, type                          // LAUNCH_APP, UI_CLICK, UI_LONG_PRESS, UI_TEXT,
                                    // SCROLL, BACK, HOME, RECENTS, WAIT, NOTIFY
  appPackage?                       // LAUNCH_APP target
  target: ElementTarget?            // UI_* target
  input?, dir?, amount, waitMs?, message?, note?
}

ElementTarget {
  mode: NODE | POINT | MIXED
  text?, desc?, viewId?, className? // node descriptors
  fx?, fy?                          // FRACTION of screen (0..1) — rotation/DPI safe
  pxX?, pxY?                        // legacy absolute pixels (pre-2.3)
  pkg?                              // app guard
}
```

### 3.2 Execution guarantees (per action, in order)
1. **App guard** — if `target.pkg` is set and the foreground app differs, the
   action fails honestly: "Open <app> first — add an 'Open app' action before
   it." (Fixes the dead package guard.)
2. **Wait-for-element** — NODE/MIXED targets poll the accessibility tree every
   60 ms for up to 8 s (AutoX `untilFind` pattern) before giving up.
3. **Node-first act** — `performAction(ACTION_CLICK / ACTION_SET_TEXT)` on the
   resolved node; gesture only if the node action fails.
4. **Gesture fallback with completion latch** — `dispatchGesture` awaited via
   the service callback (tap/long-press/swipe at resolved bounds or fractional
   coordinates).
5. **Verify** — tap-class actions check the screen fingerprint changed;
   unverified actions are reported as such, never as silent success.
6. **Run log** — every action appends a `✓/✗ + what + why` line to the skill's
   persisted `lastRunLog`; the Skills screen shows the last run's log.

### 3.3 Targeting (the part that replaces recording)
- **Pick on screen (primary, works in every app):** the builder hands off to a
  notification ("Open the screen, then tap Pick"); the user navigates to the
  target screen and taps Pick → `AccessibilityService.takeScreenshot()` (API
  30+, already a granted capability) → the screenshot opens in JARVIS → the
  user taps the exact spot → JARVIS probes the accessibility windows for a node
  containing that point to prefill text/id/desc → the target stores FRACTIONAL
  coordinates, so rotation and DPI changes cannot break replay. Pure public
  APIs; works even in games and canvas apps (POINT mode needs no a11y nodes).
- **Live capture (secondary):** the event recorder (v2.3 rewrite) still turns
  button taps, typing, scrolls and app switches into actions for linear flows —
  but every event is confirmed in the REC bubble the moment it lands
  ("✓ 'Send'"), un-reportable taps are counted and surfaced ("this app isn't
  reporting taps — use Pick on screen"), the first app-open IS recorded, scroll
  direction comes from the event, password fields are skipped (never
  `[PROTECTED]`), and recording hard-gates on the accessibility service being
  connected.
- **Manual entry:** text / id / description typed by hand, MacroDroid-style.

### 3.4 Deleted (the rescrap)
- `core/shizuku/` — ShizukuBridge/ShizukuShell/JarvisShellUserService/PrivShell/
  TouchStreamRecorder/TouchStreamDecoder (+ AIDL)
- `core/adb/` — SelfHostShell/SelfHostServer/AdbPairing/AdbClient/AdbKeyStore/
  Spake2 (+ spake2_jni.cpp, BoringSSL prefab, SPAKE2 CMake target)
- `RecordingInk` (precision-only visualization), `SelfHostSetupDialog`,
  `SelfSignedCertBuilder` + its test
- RawTapMerger (raw+event fusion — no raw stream exists anymore)
- Shizuku provider + permission from the manifest; Shizuku/BoringSSL deps from
  Gradle; all related ProGuard keeps

### 3.5 Compatibility
- Pre-2.3 skill files parse (legacy `steps` are read) and are migrated to
  actions on load/save: TAP→UI_CLICK, LONG_PRESS→UI_LONG_PRESS, TEXT→UI_TEXT,
  SCROLL (fwd→down, back→up), APP_OPEN→LAUNCH_APP, WAIT, BACK, HOME.
- `/grill-me` continues to produce skills; its output is normalized at save.
- Trigger engine, RiskClassifier confirmations, AgentForegroundService, and the
  whole safety net are unchanged.

## 4. Test plan (JVM)
- Migration: every legacy step type → correct action (round-trip).
- Element target: describe(), fraction→pixel math, resolve ladder over fake
  elements (viewId → text exact → contains → desc → point-in-bounds).
- Store: legacy JSON file loads + normalizes; new files round-trip actions.
- Resolver regression: package-guard and label-match cases carried over from
  the old UiResolveTest.

## 5. Explicitly out of scope for v2.3 (honesty list)
- Capturing touches in apps that report nothing (structurally impossible
  without privileges — Pick-on-screen is the answer there).
- Constraints (MacroDroid pillar) beyond the app guard — planned for v2.4.
- Reordering by drag — the builder uses ▲/▼ buttons.
