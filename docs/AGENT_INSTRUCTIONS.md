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
  - `Manifest.permission.CHANGE_WIFI_MULTICAST_STATE` (For LAN-1 Walkie-Talkie)
- Ensure `BluetoothAudioRouter` correctly observes `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP` and updates `connectedOutput`.

### Step 5: Android 14 FGS Contract & Notification Drawable Safety
- In `MicForegroundService.kt`:
  - Call `startForeground()` synchronously inside `onStartCommand()`. Never call `stopSelf()` without `startForeground()` having succeeded first.
  - Dynamically dispatch `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` for `RECEIVER_MIXER` and `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE` for `STANDALONE`, `TRANSMITTER`, and `WALKIE_TALKIE`.
  - Use standard platform drawables (`android.R.drawable.ic_btn_speak_now`, `ic_media_pause`, `ic_menu_close_clear_cancel`) to prevent SystemUI vector crashes (`BadForegroundServiceNotificationException`).

### Step 6: Multi-Device Network Streaming Engine
- Verify network streaming components in `com.chiti.phonemic.network.*`:
  - `UdpAudioStreamer`: 480-sample PCM16 datagrams with sequence ordering.
  - `MultiChannelMixer`: 8-channel summing mixer with per-channel adaptive jitter buffers, gain trim (0–200%), and mute.
  - `AudioServiceDiscovery`: Android NSD (mDNS) registration and discovery.
  - Walkie-Talkie LAN-1: Multicast `239.255.42.99:50005` with `WifiManager.MulticastLock` and hardware volume key PTT (`KEYCODE_VOLUME_DOWN` / `KEYCODE_VOLUME_UP`).

### Step 7: Build & Test the APK
- Execute the complete debug build and test suite:
  ```bash
  ./gradlew assembleDebug testDebugUnitTest lintDebug
  ```
- Verify output artifact exists:
  `app/build/outputs/apk/debug/app-debug.apk`

---

## 4. Acceptance Criteria

The build is considered **WORKING** when:
1. `./gradlew assembleDebug testDebugUnitTest` completes with **BUILD SUCCESSFUL** (exit code 0).
2. The APK installs cleanly via `adb install app/build/outputs/apk/debug/app-debug.apk`.
3. Tapping the central **[MIC ON]** button prompts for audio permission, starts the Foreground Service synchronously, and routes mic audio through the connected audio output.
4. The live VU meter moves dynamically in response to speaking into the microphone.
5. Moving the **Master Gain** slider adjusts the volume level without audio clipping.
6. Selecting a voice preset (*Natural*, *Clear Speech*, *Loud Speech*) alters filter parameters cleanly without clicks or pops.
7. Background music tracks play with real-time vocal auto-ducking (-14 dB attenuation during speech).
8. In **Speaker Hub** mode, up to 8 client phones can connect concurrently with independent gain and mute controls, bounded by a soft-knee limiter at `-0.5 dBFS`.
9. In **Walkie-Talkie Mesh** mode, holding the PTT button or hardware volume keys transmits audio over multicast UDP (`239.255.42.99:50005`) while silencing local playback to prevent feedback.
10. Tapping **[MIC OFF]** ceases all audio capture immediately and clears the foreground notification.
