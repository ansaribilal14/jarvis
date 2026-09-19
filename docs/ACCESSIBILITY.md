# Accessibility

## Service
`JarvisAccessibilityService` (flagDefault|IncludeNotImportantViews|ReportViewIds|
RetrieveInteractiveWindows, canPerformGestures, canTakeScreenshot, canRequestTouchExplorationMode).

## Precision touch capture (skill recorder, API 34+)
- While a recording is active, the service registers a `TouchInteractionController` for the
  default display and raises `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` (the capability is declared in
  the service config). The controller receives every touch DOWN with exact screen coordinates and
  the service immediately calls `requestDelegating()`, which passes the interaction through to
  the app untouched - observation without takeover.
- `AccessibilityServiceInfo.motionEventSources` is deliberately NOT used: per the framework docs
  that API consumes touchscreen events (they never reach apps).
- Interposition exists ONLY while recording: stopping the recording, unbinding the service, or
  any error in the delegation path lowers the flag first, restoring normal touch.
- Raw DOWNs are fused with app click events by `RawTapMerger` (1.5 s / 48 px window): merged
  steps carry semantic labels + raw coordinates; unclaimed DOWNs commit as coordinate TAPs;
  scroll events cancel their fling's DOWN; late labels replace coordinate-only steps.
- On Android < 14 the recorder is event-only (clicks, text, scrolls, app switches).

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
- Gesture fallback: dispatchGesture tap/swipe/long-press (verified after dispatch) —
  tap accepts explicit x/y coordinates as the last resort for surfaces the tree cannot
  describe; `double_tap` is a single two-stroke gesture.
- Typing taps the focused field first and retries if the editor was not focused.
- Global: back / home / recents / notification shade.
- Copy: ACTION_SELECT_ALL + ACTION_COPY on nodes; paste via ACTION_PASTE.

## Screenshot + OCR (fallback only)
API 30+: AccessibilityService.takeScreenshot (no MediaProjection consent loop). OCR via
ML Kit on-device recognizer when the a11y tree is empty/sparse. Images never leave the device.

## Honesty rules
- "Send" is never trusted blindly: taps report VERIFIED only when a window/content change
  was observed, else UNVERIFIED.
- Text entry verifies by re-reading the field.

## Precision touch capture (v2.0)

v1.9 used `AccessibilityServiceInfo.motionEventSources` (consumes the user's touches) and v1.10
used a `TouchInteractionController` gated on `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` (depends on
touch-exploration semantics most ROMs do not deliver). Both are removed in v2.0.

The precision layer now reads the raw kernel touchscreen stream (`getevent -t`) through Shizuku -
the same technique AutoX implements with root, root-free via the shell identity. Consequences:

- Works on every supported Android version, in every app, including games and canvas views that
  never emit accessibility events.
- The stream is observational: no touch is consumed, intercepted or delayed; the accessibility
  service itself does not request touch exploration any more.
- Device probing (`getevent -pl`) finds the touchscreen (the ABS_MT_POSITION_X/Y device with the
  widest X range); raw units are scaled to screen pixels using the device's ABS min/max and the
  current display size (the scrcpy ScreenMetrics approach).
- The a11y event layer (TYPE_VIEW_CLICKED/LONG_CLICKED/TEXT_CHANGED/SCROLLED/WINDOW_STATE_CHANGED)
  stays on as a fallback that no flag can silence, and `RawTapMerger` fuses both layers into
  semantic steps with real coordinates.
