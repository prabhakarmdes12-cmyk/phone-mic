# PhoneMic 🎙️🔊

> **Turn any smartphone into a real-time wireless microphone.**  
> *Phone microphone → Low-latency DSP → Bluetooth audio output → Speaker*

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Platform: Android](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg)]()
[![Audio Engine: AAudio / Oboe](https://img.shields.io/badge/Audio%20Engine-AAudio%20%2F%20Oboe%20C%2B%2B-orange.svg)]()
[![Architecture: Clean Native](https://img.shields.io/badge/Architecture-Kotlin%20%2B%20C%2B%2B%20NDK-purple.svg)]()

---

## 1. Product Vision

Millions of Bluetooth speakers have high-output acoustic amplifiers but no dedicated microphone input. Meanwhile, every smartphone contains high-grade microphones, multi-band DSP, Bluetooth radios, and touch interfaces.

**PhoneMic** bridges this gap: a user connects their phone to any Bluetooth speaker, taps one large **MIC ON** button, and their voice is amplified live through the connected speaker with optimized low latency and feedback protection. No dedicated wireless microphone, receiver, mixer, or PA hardware required.

> **Positioning:** *"A professional wireless microphone that's already in your pocket."*

---

## 2. Core Audio Architecture

```
                 PHONE MICROPHONE
                        │
                        ▼
             ┌─────────────────────┐
             │ Audio Capture       │ (Oboe / AAudio / AudioRecord)
             └──────────┬──────────┘
                        │ PCM 16-bit / 48 kHz (Low-latency Burst)
                        ▼
             ┌─────────────────────┐
             │ Real-Time DSP Chain │
             │  • DC Blocker       │
             │  • High-Pass Filter │ (80Hz cutoff)
             │  • Noise Suppressor │ (Acoustic Spectral Subtraction)
             │  • Echo Cancellation│ (AEC)
             │  • Parametric EQ    │ (3-band Speech Intelligibility)
             │  • Dynamic Comp.    │ (Leveling & Voice Consistency)
             │  • Feedback Notch   │ (Real-Time Frequency Detection)
             │  • Peak Limiter     │ (Speaker & Ear Protection)
             └──────────┬──────────┘
                        │
                        ▼
             ┌─────────────────────┐
             │ Output Router       │
             └──────────┬──────────┘
                        │
         ┌──────────────┼──────────────┐
         ▼              ▼              ▼
   Bluetooth Speaker  Wired Speaker   Phone Speaker (Test)
     (A2DP / BLE)      (3.5mm / USB)
```

---

## 3. Key Features

- **Instant Live Broadcasting:** Launch app → auto-detects connected speaker → tap to speak in `< 10 seconds`.
- **Latency Optimization & Estimation:** Exposes estimated round-trip delay (ms) using acoustic chirp pinging.
- **Acoustic Feedback Protection:** Dynamically detects acoustic howling frequencies and applies sharp notch filters before oscillation occurs.
- **Voice Presets:** Tailored DSP pipelines for *Natural*, *Clear Speech*, *Loud Speech*, *Warm Voice*, *Outdoor*, and *Karaoke*.
- **Push-to-Talk & Toggle Modes:** Tap once for continuous presentation, or hold to broadcast (walkie-talkie style for announcements).
- **Foreground Audio Service:** Operates stably while backgrounded or when the screen is locked, with persistent notifications and instant hardware mute.
- **Privacy First:** Strictly prevents accidental broadcasts on speaker reconnect, phone calls, or app relaunch. Requires explicit user activation.

---

## 4. Documentation Index

Comprehensive engineering and product documentation is maintained in the [`docs/`](./docs) directory:

| Document | Description |
|---|---|
| 📋 [**Product Requirements Document (PRD)**](./docs/PRD.md) | Exhaustive PRD, personas, feature tiers (Free vs Pro), UI/UX specifications, error states, and metrics. |
| 🏗️ [**System Architecture**](./docs/ARCHITECTURE.md) | Architectural decoupling of Audio Engine, Foreground Service, Bluetooth Router, and State Machines. |
| 🎛️ [**Audio Engine & DSP Specification**](./docs/AUDIO_ENGINE_SPEC.md) | Mathematical design of the DSP pipeline, biquad filters, compression ratios, and feedback cancellation. |
| ⚡ [**Latency & Codec Optimization**](./docs/LATENCY_OPTIMIZATION.md) | In-depth breakdown of Android A2DP latency, Bluetooth codecs (SBC, AAC, aptX, LDAC, LC3), and chirp test. |
| 🗺️ [**Engineering Roadmap**](./docs/ROADMAP.md) | Multi-phase roadmap from Phase 0 (Audio Spike) to Phase 8 (Chiti PA Distributed Ecosystem). |
| 🧪 [**Testing & Hardware Spike Plan**](./docs/TESTING_AND_SPIKE.md) | Hardware testing matrix across phone brands and Bluetooth speakers, benchmark criteria, and stability tests. |
| 🤖 [**AI Agent Guide (AGENTS.md)**](./AGENTS.md) | Operational guidelines, coding conventions, native audio real-time safety rules, and instructions for AI agents. |

---

## 5. Technology Stack

- **Target OS:** Android 8.0+ (API Level 26+)
- **Primary Language:** Kotlin (App, UI, Lifecycle) & C++ (Native Low-Latency Audio Engine)
- **Audio Stack:** 
  - Android NDK with **Google Oboe / AAudio** for sub-buffer native audio processing
  - Fallback: High-priority `AudioRecord` / `AudioTrack` with `AudioDeviceInfo` routing
  - Android Jetpack Compose for reactive, dark-themed PA instrument UI
  - Kotlin Coroutines & StateFlow for deterministic state transitions
- **Build System:** Gradle (Kotlin DSL) with CMake 3.22+

---

## 6. Project Layout

```
phone-mic/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── cpp/               # Native C++ Audio Engine (Oboe / AAudio / DSP)
│   │   │   │   ├── CMakeLists.txt
│   │   │   │   ├── native-audio-engine.cpp
│   │   │   │   └── native-audio-engine.h
│   │   │   ├── java/com/chiti/phonemic/
│   │   │   │   ├── MainActivity.kt
│   │   │   │   ├── audio/         # Audio Engine abstractions & DSP controllers
│   │   │   │   ├── bluetooth/     # Bluetooth device discovery & routing
│   │   │   │   ├── service/       # Foreground service for screen-off execution
│   │   │   │   ├── model/         # State, Presets, Profiles, Metrics models
│   │   │   │   └── ui/            # Jetpack Compose UI screens & VU meters
│   │   │   ├── res/               # Android XML resources, icons, themes
│   │   │   └── AndroidManifest.xml
│   │   └── test/                  # Unit and integration test suites
│   └── build.gradle.kts
├── docs/                          # In-depth architectural & product specifications
├── AGENTS.md                      # Operational rules and technical constraints for AI agents
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

---

## 7. License

Copyright © 2026 Chiti Technologies. Licensed under the [Apache License, Version 2.0](LICENSE).
