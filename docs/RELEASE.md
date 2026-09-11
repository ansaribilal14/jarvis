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
