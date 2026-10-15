# PhoneMic — Testing & Hardware Spike Plan

**Document Version:** 1.0.0  
**Focus:** 2–3 Day Technical Audio Spike & Hardware Validation

---

## 1. Technical Spike Objectives

Before committing weeks of UI refinement, the core audio feasibility must be experimentally proven on real-world hardware. The spike addresses four fundamental hypotheses:
1. **Audio Routing Reliability:** Can Android consistently capture microphone audio and route it to standard Bluetooth A2DP speakers without dropped sessions or crashes?
2. **Speech Latency Acceptability:** Does the end-to-end delay remain within an acceptable window ($< 150\text{ ms}$) across common Bluetooth speakers?
3. **Feedback Stability:** Does our high-pass filter, distance guidance, and peak limiter successfully prevent destructive acoustic feedback?
4. **Session Continuity:** Can an Android foreground service stream live audio continuously for 30–60 minutes without OS throttling, thermal degradation, or buffer overruns?

---

## 2. Hardware Test Matrix

| Smartphone OEM / Model | Chipset | Android Version | Bluetooth Speaker | Codec Used | Target Latency | Pass/Fail |
|---|---|---|---|---|---|---|
| **Google Pixel 7 / 8** | Google Tensor | Android 14 | JBL Flip 6 | AAC / SBC | $< 120\text{ ms}$ | Pending |
| **Samsung Galaxy S22 / S23** | Snapdragon 8 Gen 1/2 | Android 14 (OneUI) | Sony SRS-XB23 | AAC / LDAC | $< 130\text{ ms}$ | Pending |
| **OnePlus 11 / 12** | Snapdragon 8 Gen 2/3 | Android 14 (OxygenOS)| boAt Stone 650 | SBC | $< 150\text{ ms}$ | Pending |
| **Xiaomi Redmi Note 12 / 13** | MediaTek Dimensity | Android 13 (HyperOS)| JBL Go 3 | SBC / AAC | $< 160\text{ ms}$ | Pending |
| **Budget Smartphone (< ₹15k)**| MediaTek Helio | Android 12 / 13 | Generic BT 5.0 Speaker| SBC | $< 180\text{ ms}$ | Pending |

---

## 3. Acoustic Latency Benchmark Protocol

### Equipment Required:
- Smartphone under test with PhoneMic Spike APK installed.
- Target Bluetooth speaker placed 2.5 meters away from the phone.
- Secondary audio recorder (or PC with Audacity / DAW) equipped with an external stereo microphone.

### Physical Dual-Mic Measurement Method:
```
           PRESENTER
              🎙 (Speaks sharp click: "Tuk")
              │
      ┌───────┴───────┐
      │               │
      ▼               ▼
Phone Microphone   DAW Left Channel
(PhoneMic Input)   (Direct Acoustic Time T0)
      │
      ▼ (PhoneMic DSP + Bluetooth Transmission)
Bluetooth Speaker
      │
      ▼
DAW Right Channel (Amplified Acoustic Time T1)
```

1. The presenter makes an instantaneous transient sound (hand clap, tongue click, or audio metronome beep).
2. The DAW Left channel captures the direct acoustic sound at $T_0$.
3. The Bluetooth speaker outputs the amplified sound, which is recorded on the DAW Right channel at $T_1$.
4. True physical latency is calculated as:
   $$\text{Latency} = T_1 - T_0\text{ (milliseconds)}$$
5. Compare the measured DAW latency against PhoneMic's in-app acoustic chirp estimation to validate calibration accuracy.

---

## 4. Acoustic Feedback & Limiter Validation

### Feedback Onset Test:
1. Position the smartphone 3 meters away from the speaker at $50\%$ volume.
2. Slowly increase input gain from $0\text{ dB}$ to $+12\text{ dB}$.
3. Verify that the dynamic notch filter engages within $20\text{ ms}$ upon detection of self-reinforcing howling.
4. Verify that the peak limiter clamps output below $-0.5\text{ dBFS}$ with zero audible crackle or speaker damage.

### Physical Distance Step-Down Test:
1. Walk from 4 meters down to 0.5 meters while speaking continuously.
2. Confirm the app prompts: *"Warning: Move 2–3 meters away from speaker to prevent feedback"*.

---

## 5. Long-Duration Stability & Battery Test

### 60-Minute Continuous Session Benchmark:
1. Connect to a fully charged Bluetooth speaker at $70\%$ volume.
2. Launch PhoneMic, engage **MIC ON**, and run an continuous pink noise or speech loop.
3. Lock the phone screen to verify that `MicForegroundService` and `PARTIAL_WAKE_LOCK` prevent deep sleep drops.
4. Record metrics:
   - Total buffer underruns (`xRuns`) over 60 minutes (Target: $< 3$).
   - Battery percentage consumed over 60 minutes (Target: $< 6\%$).
   - Thermal rise on device (Target: $< 4^\circ\text{C}$).
   - Any crash or audio freezing (Target: $0$).

---

## 6. Phase 0 Spike Signoff Criteria

To approve transition to Phase 1 (MVP Production Development), the spike must meet the following thresholds:
- [ ] At least 3 of the 5 hardware combinations achieve measured end-to-end latency $\le 150\text{ ms}$.
- [ ] Zero unhandled native C++ SIGSEGV or Kotlin crashes during audio routing.
- [ ] Foreground service streams for $\ge 30\text{ minutes}$ with the screen turned off.
- [ ] Instantaneous mute occurs within $< 50\text{ ms}$ when Bluetooth speaker is powered off.
