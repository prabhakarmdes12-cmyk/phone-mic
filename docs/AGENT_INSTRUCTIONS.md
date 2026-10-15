# PhoneMic — Arena & Autonomous Coding Agent Instructions

This guide provides direct, unambiguous, step-by-step instructions for an autonomous coding agent (e.g. Chatbot Arena / Coding Arena, Devin, Claude Engineer, GitHub Copilot Workspace, OpenHands) to build, test, and verify the **PhoneMic** real-time wireless microphone app.

---

## 1. Agent Mission Objective

Transform the current repository from architectural scaffolding into a **fully working, buildable, deployable Android APK (`assembleDebug`)** that captures live microphone audio, applies real-time DSP, and streams to connected Bluetooth / external speakers with low latency.

- **GitHub Repository:** `https://github.com/prabhakarmdes12-cmyk/phone-mic.git`
- **Package Name:** `com.chiti.phonemic`
- **Min SDK:** 26 (Android 8.0) | **Target SDK:** 34 (Android 14)

---

## 2. Technical Strategy: Native Engine + Kotlin Fallback

To ensure the build runs reliably across both real hardware devices and virtual emulators (where AAudio/Oboe low-latency exclusive streams might encounter virtualization limitations):
1. **Primary Engine:** Native C++ (Oboe / AAudio) for real hardware sub-buffer performance.
2. **Fallback Engine:** Kotlin `AudioRecord` $\rightarrow$ `AudioTrack` with direct PCM frame buffering, ensuring instant fallback if native audio initialization fails or runs in an emulator without hardware AAudio support.

---

## 3. Step-by-Step Task Checklist for the Agent

### Step 1: Environment & Gradle Verification
- Ensure Android SDK (API 34), NDK (26.x or newer), and CMake (3.22.1+) are available in the build environment.
- Run:
  ```bash
  ./gradlew --version
  ```
- If the Gradle wrapper script is missing in your sandbox, generate or run it using your environment's gradle installation (`gradle wrapper --gradle-version 8.6`).

### Step 2: Native Audio Build & Prefab Verification
- In `app/build.gradle.kts`, `buildFeatures.prefab = true` is enabled to automatically resolve Google Oboe C++ headers via Maven dependency `com.google.oboe:oboe:1.8.0`.
- Verify that `app/src/main/cpp/CMakeLists.txt` finds Oboe:
  ```cmake
  find_package(oboe REQUIRED CONFIG)
  target_link_libraries(phonemic oboe::oboe ${log-lib})
  ```
- Run a dry compile:
  ```bash
  ./gradlew :app:externalNativeBuildDebug
  ```

### Step 3: Implement Pure Kotlin Fallback Engine (`JavaAudioEngine.kt`)
In `app/src/main/java/com/chiti/phonemic/audio/`:
- Create `JavaAudioEngine.kt` implementing `AudioEngine` using standard Android `AudioRecord` (Input source: `VOICE_COMMUNICATION`) and `AudioTrack` (Stream type: `STREAM_MUSIC`, Performance mode: `LOW_LATENCY`).
- In `MainActivity.kt`, attempt `NativeAudioEngine.start()`; if it returns `false` or throws, fall back gracefully to `JavaAudioEngine.start()`. This guarantees the app never fails silently.

### Step 4: Dynamic Permissions & Audio Routing
- Verify runtime permission requesting in `MainActivity.kt`:
  - `Manifest.permission.RECORD_AUDIO` (Must be granted before starting audio stream)
  - `Manifest.permission.BLUETOOTH_CONNECT` (Android 12+)
  - `Manifest.permission.POST_NOTIFICATIONS` (Android 13+)
- Ensure `BluetoothAudioRouter` correctly observes `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP` and updates `connectedOutput`.

### Step 5: Build & Test the APK
- Execute the complete debug build:
  ```bash
  ./gradlew assembleDebug
  ```
- Verify output artifact exists:
  `app/build/outputs/apk/debug/app-debug.apk`

---

## 4. Acceptance Criteria

The build is considered **WORKING** when:
1. `./gradlew assembleDebug` completes with **BUILD SUCCESSFUL** (exit code 0).
2. The APK installs cleanly via `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Tapping the central **[MIC ON]** button prompts for audio permission, starts the Foreground Service, and routes mic audio through the connected audio output.
4. The live VU meter moves dynamically in response to speaking into the microphone.
5. Moving the **Input Gain** slider adjusts the volume level without audio clipping.
6. Selecting a voice preset (*Natural*, *Clear Speech*, *Loud Speech*) alters filter parameters cleanly without clicks or pops.
7. Tapping **[MIC OFF]** ceases all audio capture immediately and updates the foreground notification.
