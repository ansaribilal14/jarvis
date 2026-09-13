# Privacy

## Defaults
- LOCAL ONLY mode: ON. No remote LLM, no cloud OCR/STT/TTS, no telemetry, no analytics,
  no crash reporting, no accounts.
- Network usage while LOCAL ONLY: only model downloads you explicitly start.
- Screen content, notifications, location and calendar data are processed in-process and
  stored (when memory is on) in the app's private database.

## What this build contains
No Firebase, no ad SDKs, no trackers, no analytics of any kind. The dependency list is
short and auditable in app/build.gradle.kts.

## Optional remote modes
Two opt-in paths, both disabled by default:

1. **API mode (NVIDIA NIM)** - slide-out drawer, paste key, flip on. While ON, decision
   prompts (including the observed screen block) are sent to `integrate.api.nvidia.com`.
   The UI always shows "API mode · <model>" while it is active.
2. **Manual remote provider** - Settings with explicit base URL + key.

Neither is ever used implicitly. When LOCAL ONLY is re-enabled, remote calls are
hard-blocked at the provider level (not just hidden in UI), and API failures fall back
to local/rules instead of retrying in the background.

## Data removal
Memory tab: delete facts, purge history. Uninstalling removes everything (no external
storage writes except nothing; models live in app-private storage).
