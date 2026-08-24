# Local model routing implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route Czech and French local transcription to Omnilingual 300M while
keeping Whisper Small for other languages.

**Architecture:** Extend the existing verified model store with one pinned model.
Whisper performs Auto language detection. The transcriber releases it before an
optional Omnilingual second pass, so both recognizers never share memory.

**Tech Stack:** Kotlin, sherpa-onnx 1.13.4, WorkManager, JVM tests, adb emulator QA.

---

### Task 1: Routing policy

**Files:**
- Modify: `app/src/test/java/cz/majkey/prepis/TranscriptionSettingsTest.kt`
- Modify: `app/src/main/java/cz/majkey/prepis/AudioTranscriber.kt`

- [ ] Add failing tests for Czech/French routing, majority detected language, and
  CTC chunk formatting.
- [ ] Run `./gradlew testDebugUnitTest` and confirm the new symbols are missing.
- [ ] Add `useOmnilingual`, `selectDetectedLanguage`, and `formatCtcPart`.
- [ ] Run `./gradlew testDebugUnitTest` and confirm all tests pass.

### Task 2: Verified model store

**Files:**
- Modify: `app/src/main/java/cz/majkey/prepis/TranscriptionWorker.kt`
- Modify: `app/src/main/java/cz/majkey/prepis/AudioTranscriber.kt`

- [ ] Return both model directories from `ModelStore.ensureInstalled()`.
- [ ] Add the pinned Omnilingual file sizes, hashes, and HTTPS URLs.
- [ ] Keep separate `.ready` files for each engine.
- [ ] Configure `OfflineOmnilingualAsrCtcModelConfig` with four CPU threads.
- [ ] Run `./gradlew testDebugUnitTest lintDebug assembleDebug` and confirm success.

### Task 3: Emulator model routing

**Files:**
- No production files.

- [ ] Install the debug APK only on `emulator-5570`.
- [ ] Remove only generated `local-whisper-small-auto` transcript variants.
- [ ] Run Czech Auto-detect and confirm Omnilingual completes without OOM.
- [ ] Run English Auto-detect and confirm Whisper remains selected internally.
- [ ] Capture `dumpsys meminfo`, UI state, and crash logs.
