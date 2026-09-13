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

## API mode (NVIDIA NIM)
Opt-in via the slide-out drawer: paste key -> Test & save (a real verification call) ->
flip API mode on. Facts about the data flow, stated plainly:

- The key is stored in EncryptedSharedPreferences (AES256-GCM, Keystore master key) and
  is sent ONLY to the endpoint you chose (`https://integrate.api.nvidia.com` by default).
- While API mode is ON, decision prompts (which include the observed screen block) are
  processed by the hosted model. This is the same trade-off as any cloud assistant -
  which is exactly why it is opt-in, labeled in the UI ("API mode · <model>"), and
  reversible with one switch.
- Failure is fail-closed: API errors/timeouts drop the decision to local, then rules.
  Turning LOCAL ONLY back on hard-blocks remote at the provider level, not just in UI.

## Secrets
Optional secrets = NVIDIA NIM / remote provider API key and the Telegram bot token, both
stored via EncryptedSharedPreferences (AES256-GCM, Keystore master key). GitHub release
signing uses a dedicated keystore delivered as an encrypted Actions secret - never
committed.
