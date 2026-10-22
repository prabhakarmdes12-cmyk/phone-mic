# PhoneMic — Agent & Developer Guide

This document defines core architectural tenets, coding conventions, native audio rules, and operational workflows for human engineers and autonomous AI agents contributing to the **PhoneMic** repository.

---

## 1. Product Context & Golden Rules

PhoneMic transforms a standard Android smartphone into a real-time wireless microphone amplifying sound to connected Bluetooth speakers or external PA systems.

### Non-Negotiable Tenets:
1. **Audio Engine Decoupling:** The Audio Engine (C++ / AAudio / Oboe) MUST remain strictly decoupled from the UI layer. The UI must only interact with the audio subsystem through deterministic Kotlin state holders (`AudioSessionManager` and `StateFlow`).
2. **Real-Time Safety in the Audio Callback:**
   - **ZERO memory allocation (`malloc`, `new`, `std::vector::push_back`, etc.)** within the audio processing callback thread.
   - **ZERO blocking synchronization primitives (`mutex.lock()`, semaphores, file I/O, network I/O, IPC)** on the audio rendering thread.
   - Use lock-free single-producer single-consumer (SPSC) ring buffers for passing audio frames between capture, DSP, and playback.
3. **Android 14+ Foreground Service Contract & Notification Safety:**
   - On Android 14+ (API 34+), `context.startForegroundService()` requires `Service.startForeground()` to be called synchronously and immediately in `onStartCommand()`.
   - **NEVER** call `stopSelf()` without `startForeground()` having succeeded; doing so causes Android OS to throw an uncatchable `ForegroundServiceDidNotStartInTimeException`.
   - **Notification Icons:** Small icons and action icons MUST be valid monochrome system drawables (`android.R.drawable.ic_btn_speak_now`, etc.). Never pass vector drawables with solid background rectangles or multi-colored paths to avoid SystemUI `BadForegroundServiceNotificationException` crashes.
   - **Role-Based FGS Types:** Use single role-specific types: `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` for Receiver/Mixer stations and `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE` for all mic-capturing roles. Never bitwise-combine `MICROPHONE or MEDIA_PLAYBACK` on standard microphone capture.
4. **Privacy-First Broadcasting:**
   - The microphone must NEVER automatically re-broadcast without explicit user interaction after:
     - Bluetooth disconnection & reconnection
     - An incoming or outgoing cellular/VoIP call
     - Application crash, restart, or reboot
   - An active microphone session MUST display both an Android persistent foreground notification and an in-app animated visual indicator.
5. **Safety Peak Limiter is Permanent:**
   - The final stage before output MUST always include a brickwall peak limiter to prevent acoustic blast hazards caused by drops, taps, or sudden acoustic feedback howling.

---

## 2. Directory & Architecture Mapping

```
phone-mic/
├── AGENTS.md                      # THIS FILE: Agent rules and workflow constraints
├── docs/
│   ├── PRD.md                     # Product Requirements Document
│   ├── ARCHITECTURE.md            # System architecture, lifecycle, and state machines
│   ├── AUDIO_ENGINE_SPEC.md       # DSP algorithms, filters, dynamic ducking, mixer
│   ├── MULTI_DEVICE_MESH.md       # Network streaming, 8-channel mixer, walkie-talkie
│   ├── LATENCY_OPTIMIZATION.md    # Bluetooth codecs, buffer tuning, chirp pinging
│   ├── ROADMAP.md                 # 8-phase implementation roadmap
│   ├── IMPLEMENTATION_VERIFICATION.md # CI runs, crash resolutions, acceptance tests
│   └── TESTING_AND_SPIKE.md       # Device test matrix & acoustic spike procedures
├── app/
│   ├── src/main/cpp/              # C++ Native Audio Engine (Oboe/AAudio)
│   │   ├── CMakeLists.txt
│   │   ├── native-audio-engine.cpp
│   │   └── native-audio-engine.h
│   ├── src/main/java/com/chiti/phonemic/
│   │   ├── MainActivity.kt        # Single-activity Jetpack Compose UI host
│   │   ├── audio/                 # Audio engine interfaces & Kotlin DSP managers
│   │   ├── bluetooth/             # Bluetooth audio routing & device monitoring
│   │   ├── service/               # Foreground service for screen-off microphone operation
│   │   ├── model/                 # Data models: presets, device profiles, metrics
│   │   ├── network/               # Multi-channel UDP audio streamer & discovery
│   │   └── ui/                    # Jetpack Compose UI (PA Console, VU meter, Tools)
│   └── src/main/AndroidManifest.xml
└── web/                           # Landing page and APK distribution
    ├── index.html                 # Accessible WCAG AAA download page with live demo
    ├── app-debug.apk              # Latest v1.2 Beta edition (Multi-Mic & Walkie-Talkie)
    └── app-v1.0-stable.apk        # Original Stable v1.0 edition
```

---

## 3. Real-Time Audio Constraints & C++ Guidelines

When modifying or extending `app/src/main/cpp/`:
- **Engine Framework:** Use **Google Oboe** / **AAudio** with `PerformanceMode::LowLatency` and `SharingMode::Shared` (allows compatibility across OEM HALs without failing exclusive reservations).
- **Sample Rate:** Allow native hardware sample rate negotiation on the input stream (`inputStream_->getSampleRate()`) and configure output to match. Never force sample rates with resamplers on input builders, as this crashes on low-latency MMAP streams.
- **Audio Format:** Support both `AudioFormat::Float` and `AudioFormat::I16` on input. If float input is rejected by the physical device microphone ADC, fall back to `I16` and convert `int16_t` to `float` in `onAudioReady`.
- **Ring Buffers:** Audio frames must be written to and read from lock-free ring buffers (atomic read/write pointers with power-of-two capacity).

---

## 4. State Machine Protocol

The application follows a strict state transition flow managed by `AudioState`:

```
               ┌──────────────┐
               │     IDLE     │
               └──────┬───────┘
                      │ User taps [MIC ON]
                      ▼
               ┌──────────────┐
               │  CONNECTING  │ (Check AudioDeviceInfo, Acquire AudioFocus)
               └──────┬───────┘
                      │ Success
                      ▼
               ┌──────────────┐
  ┌───────────►│    ACTIVE    │◄───────────┐
  │            └──────┬───────┘            │
  │                   │                    │
  │ User taps MUTE    │ Bluetooth drops    │ User taps [RESUME]
  │ or PTT release    │ or Call received   │
  │                   ▼                    │
  │            ┌──────────────┐            │
  └────────────┤    MUTED     ├────────────┘
               └──────┬───────┘
                      │ User taps [STOP MIC]
                      ▼
               ┌──────────────┐
               │     IDLE     │
               └──────────────┘
```

---

## 5. UI/UX Design System Rules

1. **Physical Instrument Aesthetic:** The interface should look and feel like high-end PA/studio hardware (deep obsidian/carbon background, vibrant live status glows, responsive physical-style controls).
2. **Progressive Disclosure:**
   - **Screen 1 (Home):** Only essential controls: Output Speaker status, Big Mic Toggle/PTT button, Real-time VU meter, Gain slider, and Voice Preset selector.
   - **Screen 2 (Effects):** Parametric EQ, reverb, echo depth, and advanced voice shaping.
   - **Screen 3 (Settings):** Latency optimizer, buffer controls, device profile manager, feedback sensitivity, background toggle.
3. **Accessibility:**
   - High color contrast between active state (neon cyan/green/amber) and background (`#121214`).
   - Haptic feedback when the microphone button engages or disengages.

---

## 6. How Online Agents Should Implement Tasks

When assigned a feature or bug fix:
1. **Consult `docs/`:** Always read the corresponding specification before writing code:
   - For DSP changes: `docs/AUDIO_ENGINE_SPEC.md`
   - For latency tuning: `docs/LATENCY_OPTIMIZATION.md`
   - For feature prioritization: `docs/ROADMAP.md`
2. **Keep Commits Clean & Scoped:** Write atomic, well-tested commits describing the architectural reasoning.
3. **Verify Against Edge Cases:**
   - Sudden Bluetooth headset/speaker disconnect.
   - Foreground service destruction under low memory conditions.
   - Rapid double-tapping of the MIC button.
   - Simultaneous headset plug-in while Bluetooth is streaming.
