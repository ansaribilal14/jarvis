# Security

## Risk system
LOW (open app, scroll, read screen) / MEDIUM (type text - escalated to MEDIUM in messaging
apps; settings changes; sharing) / HIGH (any interactive action detected in finance apps;
never auto-approvable). HIGH always requires explicit confirmation; MEDIUM requires it
unless the user enables auto-approve; routines are hard-limited to LOW-risk tools.

## Confirmation UX
Dialog + heads-up notification with Confirm/Deny actions showing WHAT / TARGET / DETAILS
(exact args) / WHY. Timeouts to a safe decline.

## Prompt injection defense
Screen text is DATA: delimited `<screen>` blocks + explicit system rules + InjectionGuard
pattern detection (ignore-instructions, identity override, prompt phishing, credential
exfil patterns). While a screen is flagged, medium/high actions still confirm and the
planner is explicitly warned. Tool outputs are data, never instructions.

## Credential protection
Password/OTP fields: never typed into, never copied, never stored. Memory redaction filters
(passwords/pins/otps/card-like numbers). No secret is ever logged.

## Platform boundaries respected
No lock screen or protected-settings automation; silent Wi-Fi/BT toggles replaced with
official panels + verification; QUERY_ALL_PACKAGES avoided (launcher-intent queries).

## Secrets
Only optional secret = remote provider API key, stored via EncryptedSharedPreferences
(AES256-GCM, Keystore master key). GitHub release signing uses a dedicated keystore
delivered as an encrypted Actions secret - never committed.
