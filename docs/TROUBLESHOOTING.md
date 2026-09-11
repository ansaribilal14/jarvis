# Troubleshooting

| Symptom | Fix |
|---|---|
| "Accessibility service not connected" | Settings -> Accessibility -> JARVIS -> enable. Some vendors kill it: also disable battery optimization. |
| Agent stops at "Waiting for confirmation" | Confirm dialog may be behind another app; check the heads-up notification with Confirm/Deny. |
| Model download fails / checksum mismatch | Free storage, retry; corrupted files are auto-deleted. Prefer Wi-Fi. |
| Slow inference | Use a smaller model; reduce context in Settings; close heavy apps; check thermal status in Doctor. |
| Voice: "No speech detected" | Google app must have mic permission; try typing as fallback. |
| Brightness asks for permission once | Grant "Modify system settings" in the screen JARVIS opens; then repeat the request. |
| Wi-Fi/Bluetooth not toggling | Android 10+/13+ blocks silent toggles by design - JARVIS opens the official panel; toggle once there. |
| Overlay bubble missing | Grant "Display over other apps", then re-enable the toggle in Settings. |
| App killed during long tasks | Disable battery optimization for JARVIS (Doctor shows this check). |
