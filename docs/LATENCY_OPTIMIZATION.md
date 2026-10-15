# PhoneMic — Latency Optimization & Bluetooth Codecs

**Document Version:** 1.0.0  
**Target:** Sub-100ms Wireless Audio Monitoring

---

## 1. The Anatomy of Android Bluetooth Latency

Bluetooth audio was primarily designed for pre-recorded music playback, where a buffer delay of 200–300 ms goes completely unnoticed. In a live microphone system, however, excessive delay creates a disorienting echo between the speaker's own acoustic voice and the amplified output.

```
Total Round-Trip Latency =
    t_mic (Mic capture + ADC)
  + t_in_buf (Android AAudio input buffer)
  + t_dsp (Real-time filtering & limiting)
  + t_out_buf (Android AAudio output buffer)
  + t_encode (Bluetooth codec encoding: SBC/AAC/aptX)
  + t_rf (Bluetooth radio transmission & packetization)
  + t_speaker_buffer (Speaker jitter buffer)
  + t_decode (Speaker internal DSP & DAC)
```

### Typical Latency Breakdown in Standard Android A2DP:

| Stage | Typical Delay (Standard A2DP) | PhoneMic Optimized Target |
|---|---|---|
| Mic Hardware + ADC | $5\text{ ms} - 10\text{ ms}$ | $5\text{ ms}$ |
| Input Audio Buffer (AAudio Burst) | $20\text{ ms} - 40\text{ ms}$ | $4\text{ ms} - 8\text{ ms}$ (192 frames @ 48kHz) |
| DSP Engine Processing | $10\text{ ms} - 20\text{ ms}$ | $< 2\text{ ms}$ (Single-block float32) |
| Output Audio Buffer | $30\text{ ms} - 60\text{ ms}$ | $8\text{ ms} - 16\text{ ms}$ |
| Bluetooth Codec Encoding | $20\text{ ms} - 40\text{ ms}$ | $10\text{ ms} - 20\text{ ms}$ |
| Bluetooth RF & Jitter Buffer | $60\text{ ms} - 120\text{ ms}$ | $30\text{ ms} - 50\text{ ms}$ (Speaker dependent) |
| Speaker DAC & Internal Amp | $10\text{ ms} - 30\text{ ms}$ | $10\text{ ms}$ |
| **Total End-to-End Latency** | **$155\text{ ms} - 320\text{ ms}$** | **$70\text{ ms} - 120\text{ ms}$** |

---

## 2. Bluetooth Audio Codec Matrix

| Codec | Round-Trip Delay Range | Audio Quality | Android Support | Notes |
|---|---|---|---|---|
| **SBC** | $120\text{ ms} - 200\text{ ms}$ | Standard | Universal (100%) | Mandatory baseline on all Bluetooth devices. |
| **AAC** | $140\text{ ms} - 250\text{ ms}$ | High | Widespread | High encoding complexity can increase delay on older chipsets. |
| **aptX / aptX-LL** | $40\text{ ms} - 90\text{ ms}$ | Excellent | Qualcomm chips | aptX Low Latency (aptX-LL) achieves sub-50ms when supported by speaker. |
| **LDAC** | $150\text{ ms} - 280\text{ ms}$ | Audiophile | Android 8.0+ | High bitrate increases buffering; not recommended for live speech. |
| **LC3 (LE Audio)** | $20\text{ ms} - 40\text{ ms}$ | High | Android 13+ / BT 5.2+ | The future standard for sub-30ms wireless microphone audio. |

---

## 3. Native Buffer Tuning Strategy (Oboe / AAudio)

To extract maximum performance from Android's native audio server:
1. **Match Native Burst Size:** Query `AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER` and configure Oboe stream buffers as exact integer multiples of this burst (typically 96, 128, or 192 samples).
2. **`PerformanceMode::LowLatency`:** Instructs the Android OS to assign the stream to the real-time audio thread priority.
3. **`SharingMode::Exclusive`:** Requests direct DMA hardware access, bypassing AudioFlinger mixing stages when available.
4. **Adaptive Underrun Recovery:** If `xRunCount` increases, PhoneMic incrementally expands the buffer by 1 burst size to eliminate dropouts without adding unnecessary fixed latency.

---

## 4. Acoustic Chirp / Ping Round-Trip Latency Measurement

PhoneMic features a patented-grade **Smart Setup Calibration** that scientifically measures the true physical round-trip acoustic delay without specialized external measurement hardware.

```
PhoneMic Engine
      │
      ├────── Emits 10ms Inaudible Chirp (16 kHz - 20 kHz swept sine) ──────► Speaker
      │                                                                          │
      │                                                                          ▼
      ▼                                                                   Acoustic Output
Phone Microphone captures acoustic sound from speaker ◄─────────────────────────┘
      │
      ▼
Cross-Correlation Analyzer
(Convolves captured signal against reference chirp)
      │
      ▼
Peak Correlation Detected at sample index ΔN
      │
      ▼
Total Round-Trip Latency = ΔN / SampleRate (ms)
```

### Algorithm Steps:
1. Generate an optimized linear swept sine chirp $c(t)$ spanning $2.0\text{ kHz} - 8.0\text{ kHz}$ for $15\text{ ms}$.
2. Transmit $c(t)$ out through the active speaker while recording simultaneously via the phone mic.
3. Compute normalized cross-correlation:
   $$R_{xy}[k] = \sum_{n} x[n] \cdot y[n+k]$$
4. Identify peak index $k_{\max} = \arg\max |R_{xy}[k]|$.
5. Compute physical latency:
   $$\tau = \frac{k_{\max}}{f_s} \times 1000\text{ ms}$$
6. Display result on screen (*"JBL Flip 6 — Measured Delay: 114 ms — Status: GOOD"*).

---

## 5. Device Profiles Database Schema

Once calibrated, PhoneMic stores device profiles locally in Room / SQLite or SharedPreferences so subsequent connections configure optimal buffer sizes immediately:

```json
{
  "deviceAddress": "00:1A:7D:DA:71:13",
  "deviceName": "JBL Flip 6",
  "audioDeviceType": "BLUETOOTH_A2DP",
  "preferredCodec": "AAC",
  "optimalBufferSizeFrames": 256,
  "measuredLatencyMs": 114,
  "recommendedVoicePreset": "CLEAR_SPEECH",
  "recommendedGainDb": 3.0,
  "feedbackRiskRating": "LOW",
  "lastCalibratedTimestamp": 1776241029000
}
```
