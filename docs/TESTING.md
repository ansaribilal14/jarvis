# Testing

## Automated (CI: build.yml)
- `testDebugUnitTest`: planner JSON extraction (fences, prose, nested braces) **and the
  salvage pipeline** (8 dedicated cases: `nextAction`/`arguments` key aliases, JSON
  embedded in echoed noise, truncated-output repair, tool-name aliases, bare-string
  args), injection guard patterns, model recommender ladder (incl. `smollm2-360m` for
  BASIC and `llama32-1b` for 4 GB), template rendering, catalog integrity.
- `assembleDebug` compiles the full app + native llama.cpp build - the real integration gate.
- Lint runs with abortOnError=false (advisory), failures fail the build.

## Manual device matrix (checklist used for releases)
- fresh install / onboarding / model download+verify / inference test
- accessibility on/off mid-task; notification listener on/off
- offline mode: airplane mode + local model -> full planning + device actions work
- interruption: STOP at random stages (repeat 10x); manual screen interaction during ACTING
- failure injection: missing target, wrong app, slow app, permission-denied paths
- false-success: taps that change nothing must report UNVERIFIED/FAILED, never success
- duplicates: calendar double-create guarded; messaging sends confirmed once
- reboot: settings/model/memory/routines survive; no corrupted state
- low battery saver: routines + FGS behavior

## Known-good scenarios
"Open Chrome and search X" / "Set brightness to 30" / "Turn wifi off" / "Read my
notifications" (zero-AI direct path) / "Find file invoice.pdf and share it" / "Open
Settings" / "Message mom on WhatsApp saying I'll be late" (draft, human sends) / "Set an
alarm for 7am" / "Navigate home" / compound: "Open YouTube and search lo-fi beats" /
restart mid-task: active model auto-reloads, task re-runs / API mode: same tasks on a
NIM model with airplane-mode fallback to local.
