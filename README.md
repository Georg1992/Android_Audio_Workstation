# Android Audio Workstation

An Android app for organizing small audio projects: create a project, add tracks, record to WAV, import WAV files, and play back one track at a time with optional looping. The goal is a lean mobile workstation that can grow toward multi-track mixing and collaboration; the UI and data layer already carry multiple tracks per project.

## Current behavior

- **Projects** — Library of projects; each project has a sample rate and bit depth used for new recordings and import validation.
- **Tracks** — Ordered list per project; reorder, rename, gain, mono/stereo recording mode, loop flag.
- **Recording** — One track records at a time; audio is captured via Oboe (float input) and written as 16-bit PCM WAV.
- **Import** — WAV import through the system picker; files are normalized to the project’s format on disk.
- **Playback** — Exactly one selected track plays at a time. The native engine streams PCM from disk through a ring buffer into an Oboe output stream (no full-file decode on each play). Same file replay reuses the open source when possible.
- **Persistence** — Room stores projects and tracks; audio files live under app-controlled project directories.

## Architecture (short)

- **UI** — Jetpack Compose, Material 3, Navigation Compose.
- **App logic** — ViewModels, Kotlin coroutines / `StateFlow`, Hilt for DI.
- **Audio** — `AudioController` / `NativeEngine` JNI into a C++17 engine using **Oboe** for input and output (not OpenSL ES).
- **Data** — Room (KSP), migrations through schema version 8; schema includes reserved columns for future sync (`remoteUrl`, `contentHash`, `syncStatus`, etc.) — not used by the app yet.

## Requirements

- Android Studio with Android SDK **API 26+**, **NDK** 25.2.x, **CMake** 3.22.1, **JDK 17**.
- `local.properties` with `sdk.dir` pointing at your SDK (Android Studio creates this when you open the project).

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Release builds use R8 minification; configure release signing in Gradle properties when you ship.

## Repository

[github.com/Georg1992/Android_Audio_Workstation](https://github.com/Georg1992/Android_Audio_Workstation)

---

Proprietary; all rights reserved unless you state otherwise in a separate license file.
