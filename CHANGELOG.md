# Changelog

## 0.3.0 - 2026-08-24

### Added

- Select a one-off transcript language from a recording's detail menu.

### Changed

- Defaulted fresh installs to Local Smart with automatic language detection.
- Routed detected Czech and French to Omnilingual 300M; other languages keep Whisper Small.

## 0.2.1 - 2026-08-22

### Changed

- Defaulted fresh installs to Local Whisper Small with Czech and no API key.
- Kept every cloud model available as an optional Settings choice.

### Fixed

- Kept the legacy Groq transcript mapping independent from the default profile.

## 0.2.0 - 2026-08-22

### Added

- Select the transcription model and spoken language in **Settings**.
- Use Groq, Gemini, OpenAI, xAI, or the local Whisper Small model.
- Store and compare separate transcripts for each model and language.
- Use OpenAI GPT Transcribe for high-accuracy recorded-audio transcription.

### Changed

- Renamed the app to Transcriber and changed all user interface text to English.
- Kept the Android package name so upgrades retain existing app data.
- Encrypted provider API keys with Android Keystore.
- Processed local and cloud jobs in separate sequential WorkManager queues.

### Fixed

- Stopped model and language changes from queuing intermediate selections.
- Loaded saved cloud credentials before the cold-start recording scan.
- Kept existing transcripts available when another model fails.
- Preserved legacy 0.1.0 transcript files.

## 0.1.0 - 2026-08-20

- Added folder selection, M4A discovery, local Czech transcription, and transcript reading.
