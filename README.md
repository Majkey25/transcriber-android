# Transcriber

A small Android app that turns Samsung Voice Recorder M4A files into readable
transcripts. The UI is English. The speech language and transcription model are
selected independently in Settings.

## How it works

1. Choose the recordings folder once with the Android system picker.
2. Select a model and language in Settings.
3. Add the selected provider's API key, unless using the local model.
4. New recordings run sequentially and appear newest first.
5. Open a recording to read, copy, or compare model/language versions.

The default is Local Smart with automatic language detection. The first local job
downloads about 741 MB of verified model files; later jobs work offline without an
API key. Cloud models remain optional in Settings. The app enforces provider-specific
upload limits; API quotas and charges depend on your provider account.

## Models

| Provider | Models | Notes |
| --- | --- | --- |
| Groq | Whisper Large V3, Large V3 Turbo | Quotas and charges depend on your API account |
| Google | Gemini 3.7 Flash, Gemini 3.6 Flash | Multimodal transcription with prompt-based formatting |
| OpenAI | GPT Transcribe, GPT-4o Transcribe, GPT-4o Mini Transcribe, Whisper-1 | Paid API |
| xAI | Speech to Text | Paid API; Czech text formatting is supported |
| Local | Smart routing: Omnilingual 300M for Czech/French, Whisper Small for other languages | Private/offline after about 741 MB |

Supported language settings are auto-detect, Czech, English, Slovak, German,
Polish, Ukrainian, Russian, French, Spanish, Italian, Portuguese, Dutch, and
Hungarian. xAI choices are limited to the language hints documented by xAI.

Provider references:

- [Groq Speech to Text](https://console.groq.com/docs/speech-to-text)
- [Gemini audio understanding](https://ai.google.dev/gemini-api/docs/audio)
- [OpenAI file transcription](https://developers.openai.com/api/docs/guides/speech-to-text)
- [xAI Speech to Text](https://docs.x.ai/developers/model-capabilities/audio/speech-to-text)

## Privacy and storage

[Privacy policy, terms and data deletion](https://majkey25.github.io/transcriber-android/)
are also available from the folder picker screen and Settings.
Version 0.3.1 requires explicit automatic cloud-upload consent, blocks older queued
jobs without consent, and keeps single-recording approval separate.

- Local transcription does not upload recordings. Initial model downloads contact Hugging Face.
- Cloud audio goes only to the model selected by the user.
- API keys are encrypted at rest with Android Keystore and never enter source
  code, logs, WorkManager input, backups, or prompts.
- A native mobile app cannot make a user-supplied provider key impossible to
  extract on a compromised device. Use limited personal keys and rotate them if
  the device is lost.
- Transcripts are private UTF-8 files excluded from backup. Each model/language
  profile has its own result, and legacy 0.1.0 transcripts remain readable.
- The app has no account, analytics, advertising, or custom backend.

## Stack

- Kotlin + Jetpack Compose + Material 3
- Android Storage Access Framework
- WorkManager for durable sequential local and cloud queues
- Android MediaExtractor/MediaCodec for M4A
- [sherpa-onnx 1.13.4](https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.4)
- Pinned, SHA-256-verified Whisper Small multilingual INT8 model

The Android package remains `cz.majkey.prepis` so upgrades keep the selected
folder, encrypted keys, downloaded model, and transcripts.

See the [measured accuracy benchmark](docs/ACCURACY.md) for Czech fixture results
and the 98% target status.

## Build

Requires JDK 17 and Android SDK 36.

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Current limits

- Direct `.m4a` files only; subfolders are not scanned.
- Cloud files above the provider-safe direct-upload limit are rejected instead of
  being split into multiple billable requests.
- The local model is less accurate than the recommended cloud model.
- No audio player or speaker diarization UI.
- Speech recognition is probabilistic. Verify important transcripts against the
  recording.
- Native builds include `arm64-v8a` and `x86_64`.

Source code is MIT licensed. Provider services, sherpa-onnx, and the model use
their own terms.
