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
- **Multi-Mic Speaker Hub (Up to 8 Microphones):** Connect up to 8 smartphones wirelessly over local Wi-Fi to a single speaker station phone, each with dedicated live VU metering, gain trim, and individual mute controls.
- **Wi-Fi Walkie-Talkie Mesh (LAN-1):** Instant local multicast push-to-talk across unlimited devices on the same Wi-Fi network with zero cloud servers and hardware volume key PTT.
- **Hardware Volume Key PTT:** Hold physical `Volume Up` or `Volume Down` buttons to broadcast instantly without looking at or touching the screen.
- **Smart Vocal Auto-Ducking DSP:** Automatically attenuates continuous background music by **-14 dB** during speech with smooth exponential attack (35ms) and natural release (650ms).
- **Clean Distraction-Free UI:** Minimalist default interface displaying only the big Mic toggle, VU meter, and essential speaker status; advanced mixer controls, soundboard, and network tools tucked under an expandable toggle.
- **Acoustic Feedback Protection:** Dynamically detects acoustic howling frequencies and applies sharp notch filters before oscillation occurs.
- **Voice Presets & Scenarios:** One-tap acoustic profiles for *Classroom*, *Fitness & Gym*, *Tour Guide*, *Singing & Karaoke*, and *Warehouse*.
- **Android 14 Hardened Foreground Service:** Operates stably while backgrounded or when the screen is locked, with persistent notifications, compliant FGS role-based permission types, and instant hardware mute.
- **Dual Release Distributions:** Download either the verified **Original Stable v1.0 Edition** (`app-v1.0-stable.apk`) or the cutting-edge **Multi-Mic v1.2 Beta Edition** (`app-debug.apk`) directly from the web landing page.
- **Privacy First:** Strictly prevents accidental broadcasts on speaker reconnect, phone calls, or app relaunch. Requires explicit user activation.

---

## 4. Documentation Index

Comprehensive engineering and product documentation is maintained in the [`docs/`](./docs) directory:

| Document | Description |
|---|---|
| 📋 [**Product Requirements Document (PRD)**](./docs/PRD.md) | Exhaustive PRD, personas, feature tiers (Free vs Pro), UI/UX specifications, error states, and metrics. |
| 🏗️ [**System Architecture**](./docs/ARCHITECTURE.md) | Architectural decoupling of Audio Engine, Foreground Service, Bluetooth Router, Network Streamer, and State Machines. |
| 🎛️ [**Audio Engine & DSP Specification**](./docs/AUDIO_ENGINE_SPEC.md) | Mathematical design of the DSP pipeline, biquad filters, dynamic ducking, 8-channel mixer, and feedback cancellation. |
| 📻 [**Multi-Device Mesh & Walkie-Talkie**](./docs/MULTI_DEVICE_MESH.md) | Network streaming protocols, UDP frame specs, 8-slot preallocated pool, multicast LAN-1, and PTT tokens. |
| ⚡ [**Latency & Codec Optimization**](./docs/LATENCY_OPTIMIZATION.md) | In-depth breakdown of Android A2DP latency, Bluetooth codecs (SBC, AAC, aptX, LDAC, LC3), and chirp test. |
| 🗺️ [**Engineering Roadmap**](./docs/ROADMAP.md) | Multi-phase roadmap from Phase 0 (Spike) through Phase 6 (MultiMic Mixer) and Phase 8 (Chiti PA Ecosystem). |
| 🧪 [**Testing & Hardware Spike Plan**](./docs/TESTING_AND_SPIKE.md) | Hardware testing matrix across phone brands and Bluetooth speakers, benchmark criteria, and stability tests. |
| ✅ [**Implementation & Verification**](./docs/IMPLEMENTATION_VERIFICATION.md) | Build instructions, CI run logs, Android 14 FGS crash resolutions, and device acceptance test suites. |
| 🤖 [**AI Agent Guide (AGENTS.md)**](./AGENTS.md) | Operational rules, real-time safety constraints, network protocols, and guidelines for AI developers. |

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
