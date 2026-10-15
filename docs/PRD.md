# PhoneMic — Product Requirements Document (PRD)

**Document Version:** 1.0.0  
**Status:** Approved for Implementation  
**Product Category:** Real-Time Audio Utility / Live PA System  
**Initial Platform:** Android 8.0+ (Oreo, API 26+)  
**Secondary Platform:** iOS  
**Working Titles:** PhoneMic / PocketMic / MicCast / Chiti Mic

---

## 1. Executive Summary

PhoneMic transforms any modern smartphone into a zero-hardware portable PA microphone. By routing audio captured from the smartphone's built-in microphone array through a low-latency real-time digital signal processing (DSP) engine and out to any standard Bluetooth or external speaker, PhoneMic eliminates the need for expensive dedicated wireless microphones, mixers, and receivers.

The product positions itself not simply as a karaoke utility, but as:
> **"A professional PA microphone that is already in your pocket."**

---

## 2. Problem Statement & Market Opportunity

Over 1.5 billion portable Bluetooth speakers exist globally in homes, offices, schools, and outdoor gatherings. These speakers possess robust class-D amplifiers and high-efficiency acoustic drivers, but virtually none have a physical 6.35mm/3.5mm microphone input or an integrated wireless microphone system.

At the same time, every modern smartphone possesses:
- Multi-microphone beamforming arrays
- High-performance mobile CPU/DSP chips
- Bluetooth 5.0+ radios supporting high-bandwidth audio
- Long-lasting lithium-ion batteries
- Touchscreen interfaces with internet connectivity

Operating systems normally segregate audio: phones record voice internally or stream music outwardly to Bluetooth speakers. PhoneMic creates a real-time bridge:
```
PHONE MICROPHONE → REAL-TIME DSP PIPELINE → BLUETOOTH SPEAKER
```

---

## 3. Target User Personas & Use Cases

| Persona | Scenario | Key Requirement |
|---|---|---|
| **School Teacher** | Classroom lecture amplification without straining voice | Continuous speech mode, high speech clarity, background noise rejection. |
| **Tour Guide** | Outdoor walking tour with a portable waist speaker | High mobility, wind/ambient noise suppression, screen-locked operation. |
| **Shopkeeper / Street Vendor** | Periodic announcements & promotions in crowded markets | Push-to-Talk (PTT), loud speech preset, quick activation. |
| **Yoga / Fitness Instructor** | Instructing small to medium fitness groups over music | Dynamic ducking, natural tone, hands-free operation. |
| **Community / Religious Host** | Small prayer meetings, bhajans, or community discourses | Warm voice EQ, gentle reverb, zero feedback howling. |
| **Family / Social Gathering** | Impromptu karaoke, party announcements, toasts | Fun vocal effects, low delay, simple one-touch setup. |
| **Event Organizer** | Pop-up presentations, meeting rooms without dedicated AV | Quick 10-second setup, reliable 60+ minute audio stability. |

---

## 4. Primary User Journey & North Star Target

**North Star Metric:**
> **Time to First Voice (TTFV): < 10 seconds** from application launch to audible amplification on a previously paired Bluetooth speaker.

### Ideal User Flow:
1. Connect Bluetooth speaker via Android system settings (or auto-connect).
2. Open **PhoneMic**.
3. Connected speaker name (e.g., *"JBL Flip 6"*) and latency indicator (*"126 ms — GOOD"*) appear instantly on the Home card.
4. User taps the prominent central **MIC ON** button.
5. User speaks into the phone; amplified speech projects instantly from the speaker with zero acoustic feedback.

---

## 5. System Features & Functional Requirements

### 5.1 Real-Time Audio Capture & Output
- **Capture Source:** Device microphone array (`MediaRecorder.AudioSource.VOICE_COMMUNICATION` or `UNPROCESSED` depending on DSP profile).
- **Audio Output Routing:** Automatic identification and dynamic routing to `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`, `TYPE_WIRED_HEADPHONES`, or `TYPE_USB_DEVICE`.
- **Audio Quality:** 16-bit PCM or 32-bit Float at 48,000 Hz native sampling rate.

### 5.2 Microphone Operational Modes
- **Toggle Mode (Default):** Single tap turns microphone continuously **ON**; second tap turns it **OFF**. Suitable for speeches, classes, and lectures.
- **Push-to-Talk (PTT) Mode:** Microphone is active **only** while the user holds down the button. Emits an unobtrusive haptic click when engaging and disengaging. Ideal for announcements, markets, and intermittent paging.

### 5.3 Acoustic Feedback Suppression & Safety Limiting
- **Acoustic Feedback Detection:** Continuous frequency domain monitoring detects sharp, self-reinforcing resonant peaks (howling) between phone microphone and speaker.
- **Dynamic Notch Filtering:** Automatically drops pinpoint notch filters on feedback frequencies within 15 milliseconds.
- **Dynamic Gain Cut:** Automatically drops input gain if continuous resonant oscillation begins.
- **Brickwall Peak Limiter:** Hard output ceiling to protect speakers from popping, drops, screams, or microphone taps.

### 5.4 Gain & VU Metering
- **Software Input Gain:** Adjustable input trim ranging from `-12 dB` to `+12 dB`.
- **VU Peak & RMS Meter:** Multi-segment LED/color bar display showing live decibel levels:
  - Green (`-40 dB` to `-12 dB`): Optimal speech level
  - Yellow (`-12 dB` to `-2 dB`): High speech level
  - Red (`-2 dB` to `0 dB`): Warning: Clipping / feedback risk

### 5.5 Voice Presets
Tailored combinations of High-Pass Filter, Parametric EQ, Noise Gate, and Compression:
1. **Natural:** Transparent amplification with minimal coloration; low-frequency rolloff below 80 Hz.
2. **Clear Speech:** Boosted upper-mid presence (2.5 kHz - 4 kHz) for maximum vocal intelligibility in noisy rooms.
3. **Loud Speech:** Aggressive multi-band compression, higher make-up gain, and tight limiting for projection in noisy street markets.
4. **Warm:** Gentle high-frequency smoothing and slight lower-mid warmth (200 Hz - 350 Hz) for podcast/radio tone.
5. **Outdoor:** Aggressive wind noise high-pass filter (120 Hz) with dynamic background noise suppression.
6. **Karaoke:** Vocal EQ with subtle studio plate reverb and 150ms tempo-synced echo.

### 5.6 Latency Measurement & Indicator
- **Live Latency Estimate:** Calculated from hardware buffer size, burst frame intervals, and Bluetooth codec profile.
- **Quality Banding:**
  - `< 40 ms`: **EXCELLENT** (Near imperceptible)
  - `40 – 80 ms`: **GOOD** (Imperceptible for standard speech)
  - `80 – 150 ms`: **USABLE** (Noticeable but easy to adapt)
  - `150 – 250 ms`: **NOTICEABLE** (Better suited for announcements / PTT)
  - `> 250 ms`: **HIGH DELAY** (Warning shown)
- **Acoustic Ping/Chirp Calibration:** Optional smart calibration plays a silent/inaudible ultra-short chirp out of the speaker to measure true acoustic round-trip delay.

### 5.7 Background & Lock-Screen Execution
- **Foreground Service:** Runs a prioritized Android foreground service with a persistent notification bar displaying:
  - Active speaker name
  - Quick **MUTE / UNMUTE** button
  - Stop button
- **Screen-Lock Prevention (Keep Screen Awake):** Optional setting using `FLAG_KEEP_SCREEN_ON` during active speaking sessions.
- **Accidental Touch Lock Mode:** One-swipe lock screen overlay preventing accidental touches during lectures.

---

## 6. Privacy & Safety Guarantees

1. **No Auto-Resume on Reconnect:** If the Bluetooth connection drops and re-establishes, PhoneMic holds in a **PAUSED / MUTED** state and requires an explicit user tap on `[RESUME]` to broadcast.
2. **Phone Call Protection:** Inbound cellular or VoIP calls immediately pause microphone broadcast. No call audio is ever routed through PhoneMic or external speakers.
3. **Visual Privacy:** The app displays an active glowing ring and persistent notification whenever the microphone is capturing audio, adhering to Android microphone privacy indicators.
4. **Local Processing:** All audio capture, DSP, and routing occur strictly on-device in real-time. No voice or audio recordings are uploaded to remote servers.

---

## 7. User Interface & Screen Architecture

### Primary Screens:
1. **Home (The PA Console):**
   - Speaker status card with latency badge
   - Giant animated central **MIC ON / MIC OFF** button
   - Live VU meter and reactive audio waveform
   - Input Gain slider (`-12 dB` to `+12 dB`)
   - Quick Voice Preset pill selector
2. **Effects (Sound Shaping):**
   - 3-band Parametric EQ (Bass, Mid, Treble)
   - Reverb & Echo depth sliders
   - Vocal Character presets (Megaphone, Studio, Radio, Deep Voice)
3. **Settings & Diagnostics:**
   - Latency Optimizer (Buffer size: 64, 128, 256, 512 frames)
   - Bluetooth Codec detector
   - Feedback suppression sensitivity toggle
   - Smart Speaker Calibration test
   - Saved Speaker Profiles

---

## 8. Non-Functional Requirements

- **Audio Latency:** Target native pipeline buffer latency `<= 25 ms` (excluding Bluetooth A2DP transport buffer).
- **Crash-Free Audio Sessions:** `> 99.5%` uptime during 60+ minute continuous broadcasting sessions.
- **Battery Consumption:** `< 6%` battery drain per 60 minutes of active continuous broadcasting.
- **Binary Footprint:** Lean APK size `< 15 MB` (including native C++ audio libraries).

---

## 9. Monetization Strategy

- **Free Tier:** Core microphone functionality, Bluetooth speaker routing, input gain, basic noise suppression, Natural & Clear Speech presets, and VU meter.
- **PhoneMic Pro (₹99 – ₹199 one-time purchase):**
  - Advanced parametric EQ and voice effects (Reverb, Megaphone, Studio)
  - Smart acoustic chirp latency calibration
  - Unlimited custom speaker profiles
  - High-resolution session recording (WAV / AAC)
  - Latency optimizer buffer tuning
