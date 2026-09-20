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
| API mode: "Remote error 404/410" mentioning the model is gone/end-of-life | The provider retired that model (NVIDIA removed the whole llama-3.1/3.3 NIM family on 2026-08-26). v2.2 presets only contain verified-live slugs - update the app, or pick another model in the drawer (tap "Fetch live model list" to see what the provider serves right now). |
| API drawer: switching providers lost my key | Fixed in v2.2: every provider has its own encrypted key slot (✓ on the chip = saved). The old single-slot key is still honored for the provider it belonged to; re-save it once to migrate it into the new slot. |
| Discord test message never arrives | Check: bot token saved (Settings → Discord notifications), channel picked via "Fetch my channels" (or a numeric channel ID pasted), and the bot was actually invited to the server (OAuth URL generator, "bot" scope). Discord push is one-way - inbound commands are Telegram-only by design. |
| Contact not found / wrong contact suggested | JARVIS resolves exact -> partial -> nickname (dad, mom, bhai...); ambiguous matches are listed instead of guessed - pick one in the confirmation. |
| Voice: "No speech detected" | Google app must have mic permission; try typing as fallback. |
| Brightness asks for permission once | Grant "Modify system settings" in the screen JARVIS opens; then repeat the request. |
| Wi-Fi/Bluetooth not toggling | Android 10+/13+ blocks silent toggles by design - JARVIS opens the official panel; toggle once there. |
| Overlay bubble missing | Grant "Display over other apps", then re-enable the toggle in Settings. |
| App killed during long tasks | Disable battery optimization for JARVIS (Doctor shows this check). |
| WhatsApp/Telegram message "sent itself" | It cannot: messages are prepared as drafts and the human presses send. If a draft was left, that is the designed behavior. |
| Skill replay fails at a step | Read the run log on the skill card (`Last run log ▼`) - it names the exact action and why it failed. Usually the screen changed: Edit the skill, fix that action's label or re-pick its target ("Pick on screen"), or add a Wait/Scroll before it. An action that fails twice stops the skill honestly instead of guessing. |
| "Could not find X (waited 8s)" | The action is node-targeted and its label/id is not on the screen within 8 s. Edit the action: check the label spelling (exact-or-contains), or switch the target to "Pick on screen" (point targeting), or add an Open-app action before it - the app guard refuses to tap when the wrong app is in front. |
| Quick record captures nothing | (1) JARVIS must be enabled in Settings → Accessibility (Start is disabled otherwise). (2) Some apps (games, canvas/Flutter apps) never report taps to accessibility - the REC bubble and the Skills card say so explicitly ("not reporting taps"). Build those with the builder's "Pick on screen" instead. (3) JARVIS's own actions and the system shade are never recorded. |
| My old (pre-2.3) skill disappeared | It did not - it loads and migrates automatically. Open Skills; if something misbehaves, Edit it and re-save (that converts it to the v3 format permanently). |
