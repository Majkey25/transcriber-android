# Spec: Transcriber model and language settings

## Objective

Turn the Czech-only Android app into a general `Transcriber` while preserving the
existing package ID, folder permission, API keys, and transcripts. New recordings
use one model and language profile selected in Settings. The default is Local Smart
with Auto-detect, so fresh installs work without a provider key. Cloud models show a
clear setup-required state until their API key is configured.

## Tech stack

Keep Kotlin, Jetpack Compose, WorkManager, SAF, Android Keystore, MediaCodec, and
sherpa-onnx. Add no dependency. Provider model IDs and language codes are fixed
allowlists backed by current official API documentation.

## Commands

- Build/test/lint: `.\gradlew.bat testDebugUnitTest lintDebug assembleDebug`
- Emulator install: `.\gradlew.bat installDebug`
- Diff check: `git diff --check`

## Project structure

- `Recording.kt`: recordings and transcript files.
- `TranscriptionSettings.kt`: models, languages, selected profile, preferences.
- `AudioTranscriber.kt`: offline Whisper execution.
- `TranscriptionWorker.kt`: local WorkManager queue.
- `CloudClients.kt`: bounded HTTPS provider requests.
- `CloudTranscription.kt`: cloud WorkManager queue.
- `AdvancedSettings.kt`: model, language, and encrypted API-key settings.
- `MainActivity.kt`: list/detail state and Compose screens.

## Code style

Use explicit enums and immutable profiles instead of user-entered endpoint or model
strings:

```kotlin
data class TranscriptionProfile(
    val model: TranscriptionModel,
    val language: TranscriptionLanguage,
)
```

## Behavior

- Models: local Whisper Small INT8; Groq Whisper Large V3 and Turbo; Gemini 3.7
  and 3.6 Flash; OpenAI GPT Transcribe, GPT-4o Transcribe, GPT-4o Mini Transcribe,
  and Whisper-1;
  xAI Speech to Text.
- Languages: auto-detect, Czech, English, Slovak, German, Polish, Ukrainian,
  Russian, French, Spanish, Italian, Portuguese, Dutch, and Hungarian.
- Each model/language result has a separate private UTF-8 transcript file.
- Existing local and provider transcript files remain readable.
- Automatic work uses the selected profile. Cloud work starts only when that
  provider has a Keystore-encrypted API key.
- Settings has one model selector, one language selector, and API-key controls for
  each cloud provider.
- M4A folder scanning remains unchanged. Audio chunking above provider upload
  limits, playback, and speaker diarization remain outside this change.

## Testing strategy

- JVM tests cover allowlists, profile persistence IDs, request fields, language
  hints, transcript formatting, and legacy transcript mapping.
- Existing recording and PCM tests remain green.
- Emulator QA covers Settings selectors, missing-key state, encrypted-key entry,
  local multi-language execution, source selection, rotation, process restart,
  and crash logs.
- A real cloud quality run requires a valid user API key. Without one, verify the
  exact request path through deterministic tests and a real unauthorized response.

## Boundaries

- Always: hardcoded HTTPS hosts, bounded uploads/responses, no keys in logs or work
  data, explicit emulator serial.
- Ask first: package ID migration, backend/proxy service, paid API use.
- Never: commit API keys, silently upload with another provider, touch a physical
  phone, delete existing transcripts.

## Success criteria

- App and repository are named `Transcriber` / `transcriber-android`.
- Czech is the default language, not the UI language.
- The selected model/language controls automatic and manual transcription.
- Local Smart with Auto-detect is the fresh-install default.
- Groq Large V3 remains available through its documented upload path.
- Old transcripts and Android app data survive the upgrade.
- Unit, lint, build, and emulator gates report no regression.

## Open questions

None. The user delegated product choices and requested execution without another
approval round.
