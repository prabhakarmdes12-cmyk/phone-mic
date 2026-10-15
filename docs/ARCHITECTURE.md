# PhoneMic — System Architecture

**Document Version:** 1.0.0  
**Target Platform:** Android (API 26+)  
**Primary Stack:** Kotlin, Jetpack Compose, Android NDK, C++20, Google Oboe / AAudio

---

## 1. Architectural Philosophy

Real-time audio processing on mobile devices demands uncompromising isolation between the user interface and the audio rendering pipeline. Garbage collection pauses, Android main-thread frame drops, and IPC latency can destroy audio streaming continuity, leading to buffer underruns, clicks, and pops.

PhoneMic is structured into four distinct, loosely coupled layers:
1. **UI Presentation Layer (Jetpack Compose):** Observes deterministic state flows and issues command intents.
2. **Audio Session & Service Layer (Kotlin Coroutines & Foreground Service):** Manages Android lifecycle, audio focus, permissions, and system notifications.
3. **Device & Bluetooth Router Layer:** Interacts with `AudioManager` and `AudioDeviceInfo` to detect output routing and monitor connection stability.
4. **Native Real-Time Audio Engine (C++ / Oboe):** Executes the capture loop, real-time DSP chain, lock-free ring buffering, and playback streaming with zero memory allocations on the audio thread.

```
┌────────────────────────────────────────────────────────┐
│               Jetpack Compose UI Layer                 │
│   (HomeScreen, VU Meter, Gain Slider, Preset Pills)   │
└───────────────────────────▲────────────────────────────┘
                            │ StateFlow / Actions
┌───────────────────────────▼────────────────────────────┐
│              AudioSessionManager                       │
│   (Lifecycle Coordination, Audio Focus, Permissions)  │
└──────┬────────────────────┬────────────────────┬───────┘
       │                    │                    │
┌──────▼──────┐      ┌──────▼──────┐      ┌──────▼───────┐
│ Foreground  │      │  Bluetooth  │      │  JNI Bridge  │
│   Service   │      │   Router    │      │ (Native API) │
└─────────────┘      └─────────────┘      └──────┬───────┘
                                                 │
┌────────────────────────────────────────────────▼───────┐
│             Native C++ Audio Engine (Oboe)             │
│                                                        │
│   Capture Stream (AAudio / LowLatency / Exclusive)     │
│                         │                              │
│                         ▼                              │
│             Lock-Free SPSC Ring Buffer                 │
│                         │                              │
│                         ▼                              │
│             Real-Time DSP Chain (Float32)              │
│       HPF → Noise Gate → AEC → EQ → Comp → Limiter     │
│                         │                              │
│                         ▼                              │
│   Playback Stream (AAudio / LowLatency / Shared)       │
└────────────────────────────────────────────────────────┘
```

---

## 2. Component Breakdown

### 2.1 Native Audio Engine (`app/src/main/cpp/`)
Built with modern C++20 and Google Oboe:
- **`AudioEngine` Class:** Coordinates full-duplex or synchronized duplex capture and playback streams.
- **Audio Stream Configuration:**
  - `Direction::Input` configured with `AudioSource::VoiceCommunication` (for AEC) or `AudioSource::Unprocessed` (for clean DSP), `PerformanceMode::LowLatency`, `SharingMode::Exclusive`.
  - `Direction::Output` configured with `PerformanceMode::LowLatency`.
  - Native sample rate automatically negotiated using `oboe::DefaultStreamValues::SampleRate` to avoid Android internal resampler latency overhead.
- **Lock-Free Ring Buffer:** Implemented as a Single-Producer Single-Consumer (SPSC) ring buffer with cache-line-padded atomic indices (`std::atomic<size_t>`). Eliminates mutex locks on the audio thread.

### 2.2 Audio Session Manager (`com.chiti.phonemic.audio.AudioSessionManager`)
The central Kotlin state coordinator:
- Manages the active `AudioState` (`IDLE`, `CONNECTING`, `ACTIVE`, `MUTED`, `ERROR`).
- Requests and manages `AudioFocusRequest` with `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` or `AUDIOFOCUS_GAIN`.
- Interfaces with the JNI bridge to set real-time parameters (Gain, Preset, Filter cuts).

### 2.3 Bluetooth & Device Router (`com.chiti.phonemic.bluetooth.BluetoothAudioRouter`)
- Monitors device attachments and detachments via `AudioDeviceCallback`.
- Dynamically queries available `AudioDeviceInfo` objects:
  - Detects `AudioDeviceInfo.TYPE_BLUETOOTH_A2DP`
  - Detects `AudioDeviceInfo.TYPE_BLUETOOTH_SCO`
  - Detects `AudioDeviceInfo.TYPE_WIRED_HEADPHONES` / `TYPE_WIRED_HEADSET`
  - Detects `AudioDeviceInfo.TYPE_USB_DEVICE` / `TYPE_USB_HEADSET`
- Detects Bluetooth codec profiles (SBC, AAC, aptX, LDAC) via system Bluetooth reflection or capability dumps where available.

### 2.4 Foreground Service (`com.chiti.phonemic.service.MicForegroundService`)
Enables uninterrupted audio streaming when the screen turns off or the user switches applications:
- Declared with Android 14+ `android:foregroundServiceType="microphone"`.
- Displays a persistent status notification with live microphone indicators and an instantaneous hardware **[MUTE]** toggle.
- Automatically handles device sleep transitions using a lightweight CPU partial wake lock (`PowerManager.PARTIAL_WAKE_LOCK`).

---

## 3. Real-Time Audio Dataflow & Threading Model

```
Hardware Mic  ───────►  DMA Interrupt / Kernel Driver
                              │
                              ▼
                  Oboe Input Audio Callback
                 (High-Priority Audio Thread)
                              │
               Writes frames to Lock-Free SPSC Buffer
                              │
                              ▼
                   DSP Processing Thread
             • DC Blocking Filter (biquad)
             • High-Pass Filter (80 Hz butterworth)
             • Parametric Intelligibility EQ
             • Dynamic Compressor (3:1 ratio, 5ms attack)
             • Feedback Frequency Notch Filter
             • Brickwall Peak Limiter (-0.5 dBFS)
                              │
               Writes processed frames to Output SPSC
                              │
                              ▼
                  Oboe Output Audio Callback
                              │
                              ▼
                 Bluetooth A2DP Hardware DMA
                              │
                              ▼
                      Bluetooth Speaker
```

### Real-Time Thread Constraints:
1. **No Allocations:** Heap memory is pre-allocated during `AudioEngine::initialize()`.
2. **No Locks:** Inter-thread communication uses atomic pointers and lock-free rings.
3. **Predictable Execution:** Every DSP stage calculates a fixed number of arithmetic operations per frame without unbounded loops.

---

## 4. State Machine & Lifecycle Transitions

```
               ┌──────────────┐
               │     IDLE     │
               └──────┬───────┘
                      │ User taps [MIC ON]
                      ▼
               ┌──────────────┐
               │  CONNECTING  │ ── Acquire AudioFocus
               └──────┬───────┘    Start Foreground Service
                      │            Start Native Audio Stream
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

### Critical State Transition Policies:
- **Sudden Disconnect:** If `AudioDeviceCallback::onAudioDevicesRemoved` identifies the current active output speaker, the engine transitions immediately to `MUTED`, ceases audio capture, and notifies the UI.
- **Device Reconnection:** When the speaker reconnects, the engine does NOT resume automatically. It transitions to `MUTED` with a prompt: *"Speaker reconnected. Tap RESUME to talk."*
- **Telephony Interruption:** Registered `PhoneStateListener` / `TelephonyCallback` triggers an immediate pause. Microphone broadcast halts during any call.

---

## 5. Security & Permission Architecture

| Permission | Android Version | Purpose |
|---|---|---|
| `RECORD_AUDIO` | All | Capture voice from device microphone array. |
| `BLUETOOTH` | `<= 11` | Query paired Bluetooth audio speakers. |
| `BLUETOOTH_CONNECT` | `>= 12` (API 31+) | Identify and route audio to connected Bluetooth devices. |
| `FOREGROUND_SERVICE` | `>= 9` (API 28+) | Maintain audio capture in background. |
| `FOREGROUND_SERVICE_MICROPHONE` | `>= 14` (API 34+) | System compliance for microphone foreground service. |
| `WAKE_LOCK` | All | Prevent CPU sleep during active broadcasting sessions. |
