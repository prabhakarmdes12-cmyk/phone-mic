# PhoneMic — Audio Engine & DSP Specification

**Document Version:** 1.0.0  
**Domain:** Real-Time Digital Signal Processing (DSP)  
**Sample Rate:** 48,000 Hz  
**Processing Format:** 32-bit Floating Point (`float32`, range `[-1.0, 1.0]`)

---

## 1. Overview of the DSP Pipeline

The audio chain is engineered for **minimum latency**, **maximum vocal intelligibility**, and **acoustic stability**. Every stage operates directly on blocks of `float` PCM samples inside the native audio processing thread.

```
Incoming PCM Samples (Float32)
              │
              ▼
    ┌───────────────────┐
    │ 1. DC Blocker     │ (Eliminates DC offset and sub-audible drift)
    └─────────┬─────────┘
              ▼
    ┌───────────────────┐
    │ 2. High-Pass (HPF)│ (2nd-Order Butterworth, Cutoff: 80 Hz)
    └─────────┬─────────┘
              ▼
    ┌───────────────────┐
    │ 3. Noise Gate     │ (Attenuates room hiss when speaker is silent)
    └─────────┬─────────┘
              ▼
    ┌───────────────────┐
    │ 4. Parametric EQ  │ (3-band Biquad: Low Shelf, Mid Peak, High Shelf)
    └─────────┬─────────┘
              ▼
    ┌───────────────────┐
    │ 5. Compressor     │ (Smooths vocal dynamics and prevents volume spikes)
    └─────────┬─────────┘
              ▼
    ┌───────────────────┐
    │ 6. Feedback Notch │ (Adaptive dynamic notch filter on detected howl)
    └─────────┬─────────┘
              ▼
    ┌───────────────────┐
    │ 7. Peak Limiter   │ (Hard ceiling at -0.5 dBFS; protects hearing & hardware)
    └─────────┬─────────┘
              ▼
   Outgoing PCM to Output Buffer
```

---

## 2. Mathematical Filter Specifications

### 2.1 DC Blocker
Acoustic microphones frequently introduce a small DC bias that degrades headroom in downstream compressors.
The single-pole recursive DC blocker is computed as:
$$y[n] = x[n] - x[n-1] + R \cdot y[n-1]$$
Where:
- $R = 0.995$ for 48,000 Hz sample rate.

---

### 2.2 High-Pass Filter (HPF)
Removes handling noise, mic taps, and wind rumble below speech frequencies:
- **Topology:** 2nd-Order Butterworth Biquad Filter
- **Cutoff Frequency ($f_c$):** $80\text{ Hz}$ (Speech) or $120\text{ Hz}$ (Outdoor)
- **Q-Factor:** $0.7071$ (Maximally flat passband)
- **Transfer Function:**
  $$H(z) = \frac{b_0 + b_1 z^{-1} + b_2 z^{-2}}{1 + a_1 z^{-1} + a_2 z^{-2}}$$

---

### 2.3 Noise Gate & Suppressor
Prevents amplification of ambient room noise, air conditioners, and crowd hum when the presenter is not speaking:
- **Threshold:** $-48\text{ dBFS}$ to $-36\text{ dBFS}$ (adaptive or preset-driven)
- **Attack Time:** $2.0\text{ ms}$ (instantaneous opening on vocal onset)
- **Hold Time:** $50\text{ ms}$
- **Release Time:** $150\text{ ms}$ (smooth decay without gating artifacts)
- **Floor Attenuation:** $-18\text{ dB}$ (avoids unnatural total silence)

---

### 2.4 Three-Band Parametric EQ (Biquad Filter Cascades)

Implemented via Robert Bristow-Johnson's Audio EQ Cookbook formulas:

| Band | Filter Type | Frequency ($f_0$) | Q Factor | Gain Range | Purpose |
|---|---|---|---|---|---|
| **Low Band** | Low Shelf | $180\text{ Hz}$ | $0.71$ | $-6\text{ dB}$ to $+6\text{ dB}$ | Body & warmth control |
| **Mid Band** | Peaking (Bell) | $3,000\text{ Hz}$ | $1.2$ | $-6\text{ dB}$ to $+8\text{ dB}$ | Vocal presence & intelligibility |
| **High Band** | High Shelf | $7,500\text{ Hz}$ | $0.71$ | $-6\text{ dB}$ to $+6\text{ dB}$ | Air, crispness & sibilance control |

---

### 2.5 Dynamic Range Compressor
Smooths speech volume variations so quiet words remain audible and loud shouts do not clip:
- **Detector:** Peak or RMS envelope follower:
  $$E[n] = (1 - \alpha) \cdot |x[n]| + \alpha \cdot E[n-1]$$
- **Compression Ratio:** $3:1$ (Moderate speech) or $6:1$ (Loud speech)
- **Threshold:** $-18\text{ dBFS}$
- **Attack Time:** $5.0\text{ ms}$
- **Release Time:** $80.0\text{ ms}$
- **Knee Width:** $6.0\text{ dB}$ (Soft-knee transition)
- **Auto Makeup Gain:** $+4.0\text{ dB}$

---

### 2.6 Feedback Detection & Dynamic Notch Filter
Acoustic feedback occurs when sound from the Bluetooth speaker loops back into the phone microphone, creating an unstable resonant loop at specific frequencies.

#### How PhoneMic Suppresses Feedback:
1. **Resonance Estimation:** A lightweight zero-crossing frequency estimator runs per audio block (192–480 frames) on the limited output, tracking sustained pure-tone resonance signatures without FFT allocations on the real-time thread.
2. **Persistence Test:** If the block exhibits a howling signature — peak amplitude $> 0.65$ with RMS $> 0.45$ (linear, ≈ $-7\text{ dBFS}$) and an estimated resonance frequency between $500\text{ Hz}$ and $7500\text{ Hz}$ — it is classified as feedback howling ($f_{\text{howl}}$). Detection evaluates at audio-block cadence (4–10 ms), well inside a 150 ms deployment budget.
3. **Dynamic Notch Insertion:** Acoustic feedback suppression deploys a dynamic biquad notch filter with **Q = 8.0** (tuned to account for mobile speaker resonance drift without excessive phase ringing). The notch state is primed on recent pre-notch samples (bumpless deployment) so the howl is cancelled from the first output sample with no filter warm-up burst. The same detector/notch pair runs in both the C++ engine (`native-audio-engine.cpp`) and the Platform Java fallback (`SpeechDsp`), keeping Android 11 (API 30) ear-safe.
4. **Hysteresis & Recovery:** The notch filter remains active for $1.5\text{ seconds}$ of audio ($72{,}000$ frames at 48 kHz). When the hold timer expires and feedback energy disappears, the notch resets and the `isFeedbackActive` state flag clears.

---

### 2.7 Brickwall Peak Limiter
The final guardrail in the signal chain:
- **Ceiling:** $-0.5\text{ dBFS}$
- **Lookahead:** $1.0\text{ ms}$ (48 samples)
- **Release Time:** $50.0\text{ ms}$
- **Action:** If the input signal exceeds $-0.5\text{ dBFS}$, instantaneous gain reduction is applied to ensure zero sample values reach $+1.0$ or $-1.0$.

---

## 3. Voice Preset Configuration Matrix

| Preset Name | HPF Cutoff | Low Shelf | Mid Peak | High Shelf | Comp Ratio | Comp Threshold | Noise Gate |
|---|---|---|---|---|---|---|---|
| **Natural** | $80\text{ Hz}$ | $0\text{ dB}$ | $0\text{ dB}$ | $0\text{ dB}$ | $2:1$ | $-16\text{ dB}$ | Moderate ($-42\text{ dB}$) |
| **Clear Speech** | $100\text{ Hz}$ | $-2\text{ dB}$ | $+4.5\text{ dB}$ (3kHz) | $+2\text{ dB}$ | $3.5:1$ | $-18\text{ dB}$ | Tight ($-38\text{ dB}$) |
| **Loud Speech** | $120\text{ Hz}$ | $-3\text{ dB}$ | $+6\text{ dB}$ (2.8kHz) | $+3\text{ dB}$ | $5:1$ | $-22\text{ dB}$ | Aggressive ($-34\text{ dB}$) |
| **Warm Voice** | $70\text{ Hz}$ | $+3\text{ dB}$ (220Hz) | $+1.5\text{ dB}$ (2.2kHz) | $-1\text{ dB}$ | $2.5:1$ | $-14\text{ dB}$ | Gentle ($-46\text{ dB}$) |
| **Outdoor** | $140\text{ Hz}$ | $-4\text{ dB}$ | $+5\text{ dB}$ (3.2kHz) | $+1\text{ dB}$ | $4:1$ | $-20\text{ dB}$ | High wind reject |
| **Karaoke** | $60\text{ Hz}$ | $+2\text{ dB}$ | $+2\text{ dB}$ | $+4\text{ dB}$ | $2.5:1$ | $-15\text{ dB}$ | Off (Plate Reverb on) |

---

## 4. VU Meter & RMS Calculation

For the UI VU meter, RMS and Peak values are calculated over $20\text{ ms}$ windows:
$$\text{RMS}_{\text{dBFS}} = 20 \log_{10} \left( \sqrt{\frac{1}{N} \sum_{i=1}^{N} x_i^2} \right)$$
$$\text{Peak}_{\text{dBFS}} = 20 \log_{10} \left( \max_{i=1 \dots N} |x_i| \right)$$

Values are emitted to the Kotlin UI layer at $30\text{ Hz}$ or $60\text{ Hz}$ via atomic float fields without blocking the audio thread.

---

## 5. Smart Vocal Auto-Ducking DSP

When continuous background music (BGM) or backing tracks are playing alongside live speech, the vocal auto-ducking processor dynamically attenuates music volume to maintain speech intelligibility:
- **Speech Energy Detector:** Monitored on the high-pass filtered vocal input:
  $$\text{IsSpeaking} = |x_{\text{voice}}[n]| > 0.025 \quad (\approx -32\text{ dBFS})$$
- **Target Attenuation:** $-14\text{ dB}$ ($G_{\text{duck}} \approx 0.20$) when speech is detected; $0\text{ dB}$ ($1.0$) during silence.
- **Attack Envelope:** Rapid exponential smoothing with coefficient $\alpha_{\text{attack}} = 0.003$ ($\approx 35\text{ ms}$) on speech onset.
- **Release Envelope:** Gentle exponential swell with coefficient $\alpha_{\text{release}} = 0.0003$ ($\approx 650\text{ ms}$) during pauses.
- **Execution:** Zero heap allocation, lock-free, evaluated directly in the audio rendering loop before final peak limiting.

---

## 6. Multi-Channel Summing Mixer (Up to 8 Microphones)

On the Receiver Speaker Station (`NetworkRole.RECEIVER_MIXER`), incoming UDP PCM streams from up to 8 client microphones are summed and mixed in real-time:
- **Per-Channel Jitter Buffering:** Each active stream owns an independent adaptive jitter buffer (30ms – 60ms) preventing packet reordering or jitter starvation in one stream from stalling the others.
- **Per-Channel Controls:** Independent linear gain trim ($0.0$ to $2.0$, i.e. $0\%$ to $200\%$) and individual mute flags.
- **Summing Bus & Soft Knee:** Audio samples are accumulated into a 32-bit floating-point master bus at 10ms intervals.
- **Master Broadcast Limiter:** A unity-gain region below $0.70$ amplitude and a $\tanh$ soft saturation knee strictly bound output amplitude to **$0.944$ ($-0.5\text{ dBFS}$)**. This eliminates integer overflow wrap-around distortion when multiple participants speak simultaneously.

---

## 7. Hardware ADC Format & Native Sample Rate Compatibility

- **Native Sample Rate:** The input stream builder omits fixed sample rate constraints and resamplers, allowing the microphone hardware to initialize at its native clock (e.g. 48,000 Hz, 44,100 Hz, or 16,000 Hz). The output playback stream is then matched to the native input rate, avoiding Android AAudio MMAP resampler crashes.
- **ADC Bit-Depth Fallback:** The engine requests `AudioFormat::Float` first. If rejected by the device HAL, it gracefully falls back to 16-bit signed PCM (`AudioFormat::I16`). In `onAudioReady()`, 16-bit integer samples are normalized to 32-bit float in-place via:
  $$y[n] = \frac{x_{\text{int16}}[n]}{32768.0f}$$
