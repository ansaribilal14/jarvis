# Accessibility

## Service
`JarvisAccessibilityService` (flagDefault|IncludeNotImportantViews|ReportViewIds|
RetrieveInteractiveWindows|RetrieveInteractiveWindows, canPerformGestures, canTakeScreenshot).

## Observation
- Tree walk bounded at 320 nodes / depth 48; only visible+meaningful nodes emitted.
- Elements: role (button/edittext/text/image/checkbox/toggle/dropdown/web/list/other),
  text, contentDescription, viewId, bounds, clickable/editable/scrollable/selected/password.
- Password nodes are flagged and their text is never extracted.
- Compact representation for the planner: `[12] role=button text="Send"`.

## Actions
- Semantic first: resolve element by identity re-match against a fresh observation, then
  ACTION_CLICK / ACTION_SET_TEXT / ACTION_SCROLL_*.
- Gesture fallback: dispatchGesture tap/swipe/long-press (verified after dispatch).
- Global: back / home / recents / notification shade.
- Copy: ACTION_SELECT_ALL + ACTION_COPY on nodes; paste via ACTION_PASTE.

## Screenshot + OCR (fallback only)
API 30+: AccessibilityService.takeScreenshot (no MediaProjection consent loop). OCR via
ML Kit on-device recognizer when the a11y tree is empty/sparse. Images never leave the device.

## Honesty rules
- "Send" is never trusted blindly: taps report VERIFIED only when a window/content change
  was observed, else UNVERIFIED.
- Text entry verifies by re-reading the field.
