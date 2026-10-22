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

### 5.5 Voice Presets & BGM Auto-Ducking
Tailored combinations of High-Pass Filter, Parametric EQ, Noise Gate, and Compression:
1. **Natural:** Transparent amplification with minimal coloration; low-frequency rolloff below 80 Hz.
2. **Clear Speech:** Boosted upper-mid presence (2.5 kHz - 4 kHz) for maximum vocal intelligibility in noisy rooms.
3. **Loud Speech:** Aggressive multi-band compression, higher make-up gain, and tight limiting for projection in noisy street markets.
4. **Warm:** Gentle high-frequency smoothing and slight lower-mid warmth (200 Hz - 350 Hz) for podcast/radio tone.
5. **Outdoor:** Aggressive wind noise high-pass filter (120 Hz) with dynamic background noise suppression.
6. **Karaoke / BGM Mode:** Vocal EQ with subtle studio plate reverb and continuous synthesized background music (Lo-Fi Chill, Acoustic Groove, Ambient Vocal Pad, R&B Rhythm).
   - **Smart Vocal Auto-Ducking:** Automatically attenuates background music by $-14\text{ dB}$ ($G_{\text{duck}} \approx 0.20$) whenever vocal speech energy exceeds $-32\text{ dBFS}$ ($0.025$ amplitude). Features rapid 35ms attack smoothing and a gentle 650ms release swell when speaking ceases, ensuring the voice always cuts through clearly.

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

### 5.8 Multi-Microphone Speaker Hub Mode (Multi-Mic Receiver)
- **Central Station Concept:** A single smartphone connects to the external PA speaker (via Bluetooth, USB, or 3.5mm Aux) and acts as an 8-channel summing mixer.
- **Up to 8 Concurrent Transmitters:** Receives uncompressed 480-sample (10ms) PCM16 audio datagrams from up to 8 client phones over local Wi-Fi UDP.
- **Per-Channel Jitter & Controls:** Each client is demultiplexed into an independent slot with its own adaptive jitter buffer (30ms – 60ms), gain trim ($0\%$ to $200\%$), solo, and mute controls.
- **Master Summing & Soft-Knee Limiter:** Floating-point master bus summation with a soft-knee $\tanh$ saturation curve strictly capping output at **$-0.5\text{ dBFS}$** ($0.944$), completely eliminating digital wrap distortion when multiple presenters speak simultaneously.

### 5.9 LAN-1 Walkie-Talkie Mesh Mode
- **Decentralized Local Multicast:** All phones connect to the same Wi-Fi network and join multicast group `239.255.42.99:50005`, enabling zero-server walkie-talkie communication across an unlimited number of devices.
- **Hardware Push-to-Talk (PTT):** Transmit voice by holding down the on-screen central button or physical hardware volume buttons (`KEYCODE_VOLUME_DOWN` / `KEYCODE_VOLUME_UP`) while the app is in the foreground.
- **Half-Duplex Acoustic Protection:** Local audio playback is silenced during transmission to eliminate acoustic feedback loops without requiring headphones.
- **Simultaneous Talker Summing:** Any incoming concurrent talkers are mixed together on the receiving bus.
- **Battery-Preserving Idle State:** `AudioRecord` hardware capture remains closed while idle; only wake on active PTT press.

---

## 6. Privacy & Safety Guarantees

1. **No Auto-Resume on Reconnect:** If the Bluetooth connection drops and re-establishes, PhoneMic holds in a **PAUSED / MUTED** state and requires an explicit user tap on `[RESUME]` to broadcast.
2. **Phone Call Protection:** Inbound cellular or VoIP calls immediately pause microphone broadcast. No call audio is ever routed through PhoneMic or external speakers.
3. **Visual Privacy:** The app displays an active glowing ring and persistent notification whenever the microphone is capturing audio, adhering to Android microphone privacy indicators.
4. **Local Processing:** All audio capture, DSP, and routing occur strictly on-device in real-time. No voice or audio recordings are uploaded to remote servers.

---

## 7. User Interface & Screen Architecture

### Minimalist Default UI with Progressive Disclosure
To keep the application instantly approachable for non-technical users while providing studio-grade power to audio engineers, PhoneMic adopts a progressive disclosure pattern:

1. **Default View (Ultra-Clean PA Console):**
   - Active speaker/network status badge with latency health indicator.
   - Prominent central **MIC ON / MIC OFF** circular button with animated pulse ring.
   - Real-time 30/60 fps LED VU meter and reactive audio waveform.
   - Master volume/gain slider.
   - **"Show Advanced Controls" Toggle Button:** A single, clean expandable trigger at the bottom of the screen.

2. **Advanced Tools (Progressive Disclosure Drawer):**
   - **Network Mode Selector:**
     - `STANDALONE`: Single-phone direct PA amplification to Bluetooth/Wired speaker.
     - `TRANSMITTER`: Wireless microphone sending PCM audio to a designated Speaker Hub.
     - `RECEIVER_MIXER`: 8-microphone speaker station with individual channel strips.
     - `WALKIE_TALKIE`: LAN-1 multicast peer-to-peer walkie-talkie mesh.
   - **Sound Shaping & Effects:** 3-band Parametric EQ, Studio Reverb, Echo depth, and Vocal Presets (*Natural, Clear Speech, Loud Speech, Warm, Outdoor, Karaoke*).
   - **BGM Soundboard:** Lo-Fi Chill, Acoustic Groove, Ambient Vocal Pad, and R&B Rhythm with live Vocal Auto-Ducking.
   - **Multi-Mic Mixing Console:** 8-slot channel strip showing live RMS, per-channel gain sliders (0–200%), and mute buttons.
   - **Session Recording:** Lossless 16-bit 48 kHz WAV recording with one-tap export and share sheet.

---

## 8. Non-Functional Requirements

- **Audio Latency:** Target native pipeline buffer latency `<= 25 ms` (excluding Bluetooth A2DP transport buffer).
- **Crash-Free Audio Sessions:** `> 99.5%` uptime during 60+ minute continuous broadcasting sessions.
- **Android 14+ FGS Lifecycle Compliance:** Calls `startForeground()` synchronously inside `onStartCommand()` with strict role-based service types (`microphone` for transmitters/standalone/walkie-talkie, `mediaPlayback` for receiver hub) to guarantee zero `ForegroundServiceDidNotStartInTimeException` crashes.
- **SystemUI Notification Safety:** Strict use of platform framework drawables (`android.R.drawable.*`) eliminating SystemUI vector crashes (`BadForegroundServiceNotificationException`).
- **Hardware ADC Compatibility:** Dynamic native sample rate negotiation and automated fallback from `Float` to signed 16-bit PCM (`I16`) on physical hardware ADCs.
- **Battery Consumption:** `< 6%` battery drain per 60 minutes of active continuous broadcasting.
- **Binary Footprint:** Lean APK size `< 18 MB` (including native C++ Oboe/AAudio libraries).

---

## 9. Monetization & Distribution Strategy

- **Dual Release Distribution:**
  - **Original Stable v1.0 Edition (`app-v1.0-stable.apk`):** Verified rock-solid single-phone PA, proven on legacy devices (API 26+), zero experimental dependencies, ultra-lightweight.
  - **Multi-Mic v1.2 Beta Edition (`app-debug.apk`):** Next-generation ecosystem featuring the 8-Microphone Speaker Hub, LAN-1 Walkie-Talkie Mesh, Vocal Auto-Ducking, Hardware Volume Key PTT, and full Android 14 FGS compliance.
- **Free Tier:** Core microphone functionality, Bluetooth speaker routing, input gain, basic noise suppression, Natural & Clear Speech presets, and VU meter.
- **PhoneMic Pro (₹99 – ₹199 one-time purchase):**
  - Advanced parametric EQ and voice effects (Reverb, Megaphone, Studio)
  - 8-Microphone Speaker Hub multi-client mixing
  - Smart acoustic chirp latency calibration
  - Unlimited custom speaker profiles
  - High-resolution session recording (WAV / AAC)
  - Latency optimizer buffer tuning
