# Per-recording transcript language design

## Goal

Let the user transcribe one recording in a different language without changing
the saved model or language in **Settings**. Fresh installs use Local Smart with
automatic language detection.

## Behavior

- The recording detail `⋮` menu has **Transcribe in another language…**.
- The action opens a language list supported by the current Settings model.
- Selecting a language copies the current profile with only its language changed.
- The app queues that profile for the open recording. It never calls
  `saveProfile()`.
- The new transcript uses the existing model-language filename and appears in the
  detail source selector after completion.
- Automatic work for other recordings continues to use the saved Settings profile.

## Runtime updates

The detail screen observes both local and cloud WorkManager flows. This lets a
one-off Local Whisper job show progress and refresh the transcript when it ends.
The existing cloud flow keeps the same behavior.

## Error handling

The language list contains only combinations accepted by the current model. A
cloud model requires its configured provider key. Existing transcript text stays
visible while another profile runs or fails.

## Verification

- JVM tests prove that the default profile is Local Whisper Small + Auto-detect.
- Existing profile and transcript filename tests stay green.
- Emulator QA opens a recording menu, selects English for one recording, observes
  the new local job, and confirms that Settings remains Auto-detect.
