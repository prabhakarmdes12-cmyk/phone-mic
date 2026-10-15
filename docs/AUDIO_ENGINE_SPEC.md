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
1. **Spectral Peak Detection:** A low-overhead 512-point sliding FFT runs on a parallel worker thread or downsampled stream to monitor spectral peaks.
2. **Persistence Test:** If a single narrow frequency band exhibits sudden energy growth $> 18\text{ dB}$ above surrounding bins for $> 30\text{ ms}$, it is classified as a feedback howling frequency ($f_{\text{howl}}$).
3. **Dynamic Notch Insertion:** A high-Q notch filter ($Q = 10.0$) is immediately activated at $f_{\text{howl}}$ with an attenuation of $-12\text{ dB}$ to $-24\text{ dB}$.
4. **Hysteresis & Recovery:** The notch filter remains active for $5\text{ seconds}$ and slowly decays if feedback energy disappears.

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
