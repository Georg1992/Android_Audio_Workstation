# Android Audio Workstation

An Android app for small audio projects: create a project, add tracks, record to WAV, import WAV files, and play back **one track at a time** with optional looping and per-track gain. The data model and UI already support multiple tracks per project; **multi-track simultaneous playback through a runtime mix bus is planned**, not shipped yet.

## Current behavior

- **Projects** — Library of projects; each project defines sample rate (and related audio config) for new recordings and import validation.
- **Tracks** — Ordered list per project: reorder (drag subsystem + persistence), rename, linear gain (0–100%), mono/stereo capture mode per track, per-track loop flag.
- **Navigation** — Jetpack Compose + Navigation: main menu, create project, project editor (with optional “quick record” entry), library, community, and devices screens. Project detail uses a dedicated route so each open project gets a fresh `ProjectViewModel` lifecycle; hub routes use single-top where appropriate.
- **Recording** — One track records at a time. Capture uses **Oboe** (float input) and is written as **16-bit PCM WAV**. `ProjectRecordingCoordinator` allocates a pending track optimistically before slower native/database work completes, with rollback on failure.
- **Import** — WAV import via system picker; content is validated and normalized to the project’s on-disk expectations.
- **Playback** — **Exactly one native playback stream at a time**: disk-backed PCM flows through a **ring buffer** into an Oboe stereo output (`AudioEngine` / `render()`). Playback gain is applied in native code from Kotlin (linear 0…1). **No full-file decode on each play**; reopen/reuse behavior is handled in the engine when the same file is played again.
- **Session layout** — `ProjectTransportController` coordinates transport; `RecordingSessionController` and `PlaybackSessionController` own recording vs playback session rules (start/stop, polling completion, resource hygiene) on top of `AudioController` / `NativeAudioController`.
- **Persistence** — Room stores projects and tracks; audio files live under app-controlled project directories. Schema version **8** (KSP); optional sync-oriented columns exist for future use.
- **UI / theme** — Material 3 + custom app palette (`AppColors`, shared surfaces/scaffolds). In-app language selection with DataStore-backed locale.
- **Tests** — JVM unit tests (including Robolectric where used) and an instrumented Room test; GitHub Actions runs `assembleDebug` and `testDebugUnitTest`.

## Architecture (short)

| Layer | Notes |
|--------|--------|
| **UI** | Jetpack Compose, Material 3, Navigation Compose. |
| **App logic** | ViewModels, coroutines / `StateFlow`, Hilt DI. |
| **Audio (Kotlin)** | `AudioController`, `NativeAudioController`, `NativeEngine` (JNI). |
| **Audio (native)** | C++17 engine: `AudioEngine`, `LocalWavSource`, `RingBuffer`, `OboeOutput`; JNI in `JNI_Bridge.cpp`. **Oboe 1.10.0** is pulled via CMake `FetchContent`. |
| **Data** | Room (KSP), DAOs, repositories. |

## Requirements

- **Android Studio** with Android SDK **API 26+** (app `minSdk` 26), **compileSdk** aligned with the module (currently **36**), **targetSdk** **35**.
- **NDK** **25.2.9519653** (matches `app/build.gradle` and CI).
- **CMake** **3.22.1**.
- **JDK** **17** (AGP **9.0.1** / Kotlin **2.3.x** toolchain as in root `build.gradle`).
- `local.properties` with `sdk.dir` (Android Studio creates this on first open).

## Build

On macOS / Linux:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

On Windows (PowerShell or `cmd`):

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :app:testDebugUnitTest
```

Release builds use R8 minification and resource shrinking; configure release signing via Gradle properties when you ship (see comments in `app/build.gradle`).

**Optional native tests:** RingBuffer GoogleTest targets are gated by `-DBUILD_RINGBUFFER_GTESTS=ON` in the module’s CMake arguments (off by default for faster dev builds).

## CI

`.github/workflows/android-ci.yml` installs JDK 17, Android SDK (API 35 build-tools), CMake, and the pinned NDK, then runs `assembleDebug` and `testDebugUnitTest`, and uploads the debug APK as an artifact.

## Repository

[github.com/Georg1992/Android_Audio_Workstation](https://github.com/Georg1992/Android_Audio_Workstation)

## Roadmap (not in this tree yet)

- **Runtime MixBus** — Selected multi-track playback with per-track gain, bus-level soft saturation / limiting, and preservation of meaningful user faders (see design discussions in project notes). The current engine path remains **single `IAudioSource` + one ring** until that work lands.

## Extra docs

- `docs/DRAG_REORDER_READINESS.md` — drag/reorder subsystem review and integration notes.

---

Proprietary; all rights reserved unless you state otherwise in a separate license file.
