# Memory

## Tiers (Room / SQLite, local only)
- **Tasks**: goal, source, status, action count, summary, timestamps.
- **Steps**: per-action record (tool, redacted args, status, observation, verification).
- **Facts**: user-approved long-term facts (key/value/source).
- **Chat**: conversation ring with retention cap (250, pruned to 200).
- **Routines**: schedules with day masks and last-run stamps.

## Redaction (before anything is stored)
- `password|passwd|pin|otp : value` -> `[REDACTED]`
- 13-19 digit sequences -> `[NUMBER-REDACTED]`
- OTP-pattern notifications are never cached at all.
- Screen contents are never persisted as long-term memory.

## Controls
Memory tab: inspect facts, add/edit/delete, clear all; master switch disables all writes;
history purge; per-task delete. Memory disabled = engine skips all Room writes.
