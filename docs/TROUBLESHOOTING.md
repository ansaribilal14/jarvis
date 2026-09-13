# Troubleshooting

| Symptom | Fix |
|---|---|
| "Accessibility service not connected" | Settings -> Accessibility -> JARVIS -> enable. Some vendors kill it: also disable battery optimization. |
| Agent stops at "Waiting for confirmation" | Confirm dialog may be behind another app; check the heads-up notification with Confirm/Deny. |
| Model download fails / checksum mismatch | Free storage, retry; corrupted files are auto-deleted. Prefer Wi-Fi. |
| Slow inference | Use a smaller model (`smollm2-360m` is the fastest, `llama32-1b` the sweet spot); keep threads on auto (performance-core detection); reduce context in Settings; close heavy apps; check thermal status in Doctor. |
| Task sits at "Step 0" for minutes, model echoes the screen | Fixed in v1.6.0 (native stop sequences + compact mode + salvage parsing). Update the app; if you are on the latest, prefer `llama32-1b` over smaller models or turn on API mode. |
| "Test model" fails with a JNI error after ~3s | Fixed in v1.5.0 (ProGuard keep + optional native callback). Update the app - do not debug your device. |
| Task stuck at "Waking the model" forever | Fixed in v1.3.1+ (no unload-under-decode, hard 240s deadline, honest rule fallback). Update the app; STOP still always works. |
| App says "no local model downloaded yet" although a model is active | Fixed in v1.6.1 (auto-reload of the active model on app start + state-aware fallback messages). Update the app. |
| API mode: tasks still run locally | Check the drawer: key must pass "Test & save", the API mode switch ON, and the status line shows "API mode · <model>". If the API fails mid-task, JARVIS fails closed to local/rules by design. LOCAL ONLY (Settings) overrides API mode. |
| Contact not found / wrong contact suggested | JARVIS resolves exact -> partial -> nickname (dad, mom, bhai...); ambiguous matches are listed instead of guessed - pick one in the confirmation. |
| Voice: "No speech detected" | Google app must have mic permission; try typing as fallback. |
| Brightness asks for permission once | Grant "Modify system settings" in the screen JARVIS opens; then repeat the request. |
| Wi-Fi/Bluetooth not toggling | Android 10+/13+ blocks silent toggles by design - JARVIS opens the official panel; toggle once there. |
| Overlay bubble missing | Grant "Display over other apps", then re-enable the toggle in Settings. |
| App killed during long tasks | Disable battery optimization for JARVIS (Doctor shows this check). |
| WhatsApp/Telegram message "sent itself" | It cannot: messages are prepared as drafts and the human presses send. If a draft was left, that is the designed behavior. |
| Skill replay fails at a step | The screen changed since recording. Run Edit on the skill, fix the failing step's label/coordinates, or re-record. A step that fails twice stops the skill honestly instead of guessing. |
| Skill does not record what I did | v1.10 splits capture into two layers that never silence each other: (1) precision touch capture on Android 14+ records the exact coordinates of EVERY tap in every app (the service briefly interposes the touch pipeline and delegates each touch straight back - your taps behave normally), fused with app click events into semantic steps; (2) app-event capture (buttons, typing, scrolls, app switches) works on all Android versions. The REC card shows the live mode - if it says "Precision capture ON - N touches seen", every tap is being recorded; if it says "App-event capture", raw touch is unavailable on this device and taps in apps that hide from accessibility may be missed (add them as steps in the editor instead). If nothing moves at all, check JARVIS is enabled in Settings → Accessibility (the REC card warns when it is off). JARVIS's own actions and the system shade are never recorded. |
