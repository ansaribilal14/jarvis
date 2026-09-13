# Release

## Pipeline
1. Push to main -> build.yml: assembleDebug + unit tests.
2. Create a GitHub Release (tag vX.Y.Z) -> release.yml:
   - decodes the signing keystore from Actions secrets,
   - `assembleRelease` (R8 minified, resource-shrunk, signed),
   - uploads `jarvis-release.apk` + `.sha256` to the release,
   - sends the APK to Telegram with the release link.

## Signing
Keystore lives ONLY as an encrypted Actions secret (KEYSTORE_BASE64/PASSWORD/KEY_ALIAS/
KEY_PASSWORD). Local builds without env vars fall back to the debug key (documented).
Never commit keystores or tokens.

## Version bumps
versionCode/versionName in app/build.gradle.kts. Tag and release name must match.

## Post-release
Verify: release asset present, sha256 matches, Telegram delivery received, install on a
real device, run the TESTING.md scenario list.

## Version history (shipped)
| version | code | theme |
|---|---|---|
| v1.6.1 | 10 | model auto-reload, state-aware fallbacks (v1.6.0 changes folded in; tag skipped) |
| v1.5.0 | 8 | Test-model JNI fix, perf-core threads, fast models, NVIDIA NIM API mode |
| v1.4.0 | 7 | plan-first compound goals, contacts/comms, media/nav/system tools |
| v1.3.2 | 6 | zero-AI notification reads, Test model button |
| v1.3.1 | 5 | unload-under-decode hang fix, LLM deadlines |
| v1.3.0 | 4 | grounded execution, anti-hallucination, unlimited budget |
| v1.2.0 | 3 | live progress in UI/notification/Telegram |
| v1.1.0 | 2 | activation rebuild, .gguf import, Telegram remote |
| v1.0.0 | 1 | initial release |

Full details: [CHANGELOG.md](../CHANGELOG.md).
