# Voice

## Input
Android `SpeechRecognizer` with `EXTRA_PREFER_OFFLINE = true`. `isOnDeviceRecognitionAvailable()`
is surfaced in Jarvis Doctor. Partial results stream into the home orb view; final results
dispatch the task (source=VOICE). Errors are honest: no-match / timeout / permission /
network messages.

## Output
Android `TextToSpeech` (default local engine). JARVIS speaks concise statuses: task start,
final result (truncated). Never narrates internal actions.

## Wake word
Deliberately not implemented in v1: continuous mic hot-word models drain battery and are
device-inconsistent. Push-to-talk (mic button, Quick Settings tile) is the guaranteed
low-power path per the platform reality rules.
