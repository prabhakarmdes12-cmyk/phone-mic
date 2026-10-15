# PhoneMic — Product & Engineering Roadmap

This document outlines the multi-phase execution strategy transforming PhoneMic from an initial low-latency mobile audio spike into a full-scale distributed PA ecosystem.

---

## Roadmap Overview

```
Phase 0 ──► Phase 1 ──► Phase 2 ──► Phase 3 ──► Phase 4 ──► Phase 5 ──► Phase 6 ──► Phase 7 ──► Phase 8
(Spike)     (Core)      (DSP)       (PA UI)     (Record)    (2-Phone)   (MultiMic)  (AI Voice)  (Chiti PA)
```

---

## Phase 0: Technical Audio Spike & Latency Verification
**Goal:** Prove feasibility and measure true latency across major Android phone and Bluetooth speaker hardware combinations.

### Deliverables:
- Minimalist Android audio prototype using AAudio / Oboe.
- Audio routing loop between phone microphone and connected Bluetooth A2DP device.
- Acoustic chirp ping measurement tool.
- Benchmark data collected across flagship and budget devices (Samsung, OnePlus, Xiaomi, Pixel with JBL, Sony, boAt).
- Decision gate on latency expectations and default buffer configurations.

---

## Phase 1: Core MVP Wireless Microphone
**Goal:** Bulletproof end-to-end audio pipeline with instant activation and privacy protections.

### Deliverables:
- Native C++ audio engine with decoupled Kotlin lifecycle manager.
- Bluetooth device discovery and dynamic `AudioDeviceInfo` routing.
- Big **MIC ON / MIC OFF** toggle and Push-to-Talk (PTT) modes.
- Input Gain trim slider (`-12 dB` to `+12 dB`).
- AudioFocus management (immediate pause on incoming phone calls).
- Disconnect safety policy (immediate mute if Bluetooth speaker drops).
- Foreground service with persistent Android notification for screen-off execution.

---

## Phase 2: DSP, Feedback Suppression & Safety Limiting
**Goal:** Eliminate acoustic feedback howling and volume blast hazards.

### Deliverables:
- 2nd-order Butterworth High-Pass Filter (80 Hz / 120 Hz cutoff).
- Real-time DC blocking recursive filter.
- Dynamic peak limiter with lookahead ceiling at `-0.5 dBFS`.
- Spectral feedback frequency detector and dynamic high-Q notch filters.
- Core Voice Presets (*Natural*, *Clear Speech*, *Loud Speech*, *Warm Voice*, *Outdoor*).
- Physical-distance onboarding walkthrough (visual distance warning).

---

## Phase 3: Polished Single-Screen PA Console UI
**Goal:** Deliver an intuitive, physical-instrument aesthetic operable with one hand from a distance.

### Deliverables:
- Jetpack Compose dark-theme PA instrument UI (`#121214` obsidian palette).
- Giant animated center microphone button with reactive audio pulse ring.
- Real-time 30/60 fps LED-style VU peak and RMS meter.
- Device connection card showing speaker model and latency health badge (*GOOD*, *USABLE*, *NOTICEABLE*).
- One-swipe accidental touch lock mode for continuous lectures and speeches.

---

## Phase 4: Session Recording & Karaoke Mode
**Goal:** Enable capture of live audio and vocal accompaniment.

### Deliverables:
- Direct lossless WAV / AAC encoding of the processed microphone stream.
- Audio file exporter with system share sheet integration.
- Karaoke mode: background music playback mixed with real-time vocal channel.
- Studio vocal plate reverb and synchronized delay/echo effects.

---

## Phase 5: Two-Phone Wireless Mode (Phone A → Phone B)
**Goal:** Break the Bluetooth physical distance barrier by decoupling the presenter from the speaker.

### Architecture:
```
PRESENTER                             SPEAKER STATION
 📱 Phone A                             📱 Phone B
    Mic                                  Receiver
     │                                      │
     └────── Wi-Fi Direct / WebRTC ─────────┘
                                            │
                                        Bluetooth
                                            │
                                            ▼
                                     🔊 PA Speaker
```
- Presenter carries Phone A anywhere in the room.
- Phone B remains stationed right next to the speaker.
- Ultra-low latency Opus audio streaming over local Wi-Fi / Wi-Fi Direct.

---

## 6. Phase 6: MultiMic Distributed Mixer
**Goal:** Multiple wireless microphones transmitting concurrently to a single receiver.

### Deliverables:
- Multi-client connection protocol over local subnet.
- Multi-channel audio mixer UI on Receiver phone.
- Individual channel gain, solo, and mute controls.
- Broadcast master limiter.
- Use cases: Panel discussions, classrooms, debates, religious discourses.

---

## Phase 7: On-Device AI Voice Enhancement
**Goal:** Deep-learning acoustic speech cleanup without cloud round-trip delay.

### Deliverables:
- Lightweight TensorFlow Lite / ONNX Runtime acoustic isolation model.
- Dynamic ambient noise cancellation (traffic, crowd murmur, fan noise).
- AI automatic voice equalization matching individual vocal timbre.

---

## Phase 8: Chiti PA Ecosystem
**Goal:** Unified enterprise and community public address platform.

### Architecture:
- Software-defined PA controller bridging Bluetooth, Wi-Fi, and USB audio outputs.
- Integration with external soundboards and broadcast recording suites.
- Central management dashboard for institutions, schools, and temples.
