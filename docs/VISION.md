# Vision

Priority order (all local):
1. **Accessibility tree** - primary, structured, reliable.
2. **OCR** - ML Kit on-device text recognition, used when the tree is empty/sparse
   (PDF viewers, some games, canvas UIs). Output merged as text elements with bounds.
3. **Screenshot** - AccessibilityService.takeScreenshot (API 30+), downscaled before OCR.
4. **Local VLM** - not bundled in v1. The abstraction (vision rung in the observer chain)
   is present; a gguf multimodal model can be added behind OcrEngine without touching the
   agent loop. Not faked: until then the UI never claims visual understanding.

No screenshot or OCR result ever leaves the device (LOCAL ONLY default).
