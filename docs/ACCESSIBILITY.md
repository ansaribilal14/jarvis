# Accessibility

## Service
`JarvisAccessibilityService` (flagDefault|IncludeNotImportantViews|ReportViewIds|
RetrieveInteractiveWindows, canPerformGestures, canTakeScreenshot).

There is NO touch-interception capability declared and none is used: JARVIS never
consumes, filters or rewrites the user's touches.

## Skills v3 - how targeting actually works (v2.3+, see docs/SKILLS_V3.md)

v1.9 (`motionEventSources`), v1.10 (`TouchInteractionController`), v2.0 (`getevent`
via Shizuku) and v2.1 (in-app ADB pairing + own shell server) all tried to observe
raw touches; every mechanism failed on a real device and all of them are REMOVED.
There is no privileged capture anymore - and no skill feature needs one:

- **Pick on screen (primary)**: `AccessibilityService.takeScreenshot()` (API 30+,
  capability already declared) captures the screen; the user taps the exact element
  on the screenshot inside JARVIS; `describeNodeAtPoint(x, y)` walks ALL retrievable
  windows (popups/split-screen included) to prefill the element's text/desc/viewId
  when one is available. The stored target uses FRACTIONAL coordinates (0..1 of
  screen), so replay is immune to rotation/DPI/device changes. POINT-mode targets
  need no accessibility nodes at all - they work in games and canvas apps.
- **Quick record (secondary)**: the recorder turns what apps voluntarily REPORT into
  actions - TYPE_VIEW_CLICKED / LONG_CLICKED / TEXT_CHANGED / SCROLLED and window
  transitions (app switches). Every capture is confirmed live in the REC bubble;
  taps the app did not report are counted and surfaced ("this app isn't reporting
  taps - use Pick on screen"); password fields are skipped, never recorded.
- **Runner guarantees**: wait-for-element polling (60 ms / 8 s) before acting,
  node-first `performAction`, awaited-`dispatchGesture` fallback, per-action
  verification against the screen fingerprint, an app guard (an action pinned to
  an app refuses to act in another), one retry, then an honest stop. Every run
  appends `✓/✗ what - why` lines to the skill's persisted run log.

## Observation
- Tree walk bounded at 320 nodes / depth 48; only visible+meaningful nodes emitted.
- Elements: role (button/edittext/text/image/checkbox/toggle/dropdown/web/list/other),
  text, contentDescription, viewId, bounds, clickable/editable/scrollable/selected/password.
- Password nodes are flagged and their text is never extracted.
- Compact representation for the planner: `[12] role=button text="Send"` — and every
  element carries its `center=(x,y)` tap point plus a short viewId, so the model
  can act even on undescribed surfaces. A `fingerprint()` supports change detection
  between rounds.

## Actions
- Semantic first: resolve element by identity re-match against a fresh observation, then
  ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_*.
- Gesture fallback: dispatchGesture tap/swipe/long-press with a completion latch
  (awaited via `GestureResultCallback`) — tap accepts explicit x/y coordinates as the
  last resort for surfaces the tree cannot describe; `double_tap` is a single
  two-stroke gesture.
- Typing taps the focused field first and retries if the editor was not focused.
- Global: back / home / recents / notification shade.
- Copy: ACTION_SELECT_ALL + ACTION_COPY on nodes; paste via ACTION_PASTE.

## Screenshot + OCR (fallback only)
API 30+: AccessibilityService.takeScreenshot (no MediaProjection consent loop) - also
the basis of "Pick on screen". OCR via ML Kit on-device recognizer when the a11y tree
is empty/sparse. Images never leave the device.

## Honesty rules
- "Send" is never trusted blindly: taps report VERIFIED only when a window/content change
  was observed, else UNVERIFIED - and the run log says which.
- The recorder never claims a capture it cannot prove: unreportable taps are counted,
  warnings are shown in the REC bubble and on the Skills screen.
- Recording hard-gates on the service being connected; starting without it is impossible.
