# Per-recording transcript language implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one-off language selection to a recording without changing Settings.

**Architecture:** Reuse `TranscriptionProfile.copy`, the existing queues, and
profile-specific transcript storage. Expose the existing local WorkManager flow to
the detail screen so both local and cloud one-off jobs refresh the UI.

**Tech Stack:** Kotlin, Jetpack Compose, WorkManager, JVM tests, adb emulator QA.

---

### Task 1: Auto-detect default

**Files:**
- Modify: `app/src/test/java/cz/majkey/prepis/TranscriptionSettingsTest.kt`
- Modify: `app/src/main/java/cz/majkey/prepis/TranscriptionSettings.kt`

- [ ] Change the default test to expect `LOCAL_WHISPER_SMALL` and `AUTO`.
- [ ] Run `./gradlew testDebugUnitTest` and confirm the test fails on `CZECH`.
- [ ] Change `TranscriptionProfile.DEFAULT` to:

```kotlin
val DEFAULT = TranscriptionProfile(
    TranscriptionModel.LOCAL_WHISPER_SMALL,
    TranscriptionLanguage.AUTO,
)
```

- [ ] Run `./gradlew testDebugUnitTest` and confirm all tests pass.

### Task 2: One-off language flow

**Files:**
- Modify: `app/src/main/java/cz/majkey/prepis/MainActivity.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] Expose the existing local work flow as `localWorkInfos` and use it in `rows`.
- [ ] Pass `localWorkInfos` into `TranscriptScreen`.
- [ ] Add **Transcribe in another language…** to the detail menu.
- [ ] Show an `AlertDialog` with supported languages except the current language.
- [ ] Queue `selectedProfile.copy(language = language)` through `onTranscribe`.
- [ ] Observe both work lists in `LaunchedEffect` so local completion reloads sources.
- [ ] Run `./gradlew testDebugUnitTest lintDebug assembleDebug` and confirm success.

### Task 3: Emulator verification

**Files:**
- No production files.

- [ ] Install `app-debug.apk` only with `adb -s emulator-5570 install -r`.
- [ ] Open a recording, then open its `⋮` menu.
- [ ] Select **Transcribe in another language…**, then select English.
- [ ] Confirm the detail screen shows progress and the English transcript source.
- [ ] Open Settings and confirm the default remains Auto-detect.
- [ ] Confirm `adb -s emulator-5570 logcat -d -b crash` is empty.
