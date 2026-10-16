#include "native-audio-engine.h"
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>

#define TAG "PhoneMicNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace phonemic {

// -------------------------------------------------------------
// LockFreeAudioBuffer Implementation
// -------------------------------------------------------------
LockFreeAudioBuffer::LockFreeAudioBuffer(size_t capacity) {
    // Ensure capacity is a power of two
    size_t pow2 = 1;
    while (pow2 < capacity) pow2 <<= 1;
    capacity_ = pow2;
    mask_ = capacity_ - 1;
    buffer_.resize(capacity_, 0.0f);
}

size_t LockFreeAudioBuffer::write(const float* data, size_t count) {
    size_t w = writeIndex_.load(std::memory_order_relaxed);
    size_t r = readIndex_.load(std::memory_order_acquire);
    size_t available = capacity_ - (w - r);
    size_t toWrite = std::min(count, available);

    for (size_t i = 0; i < toWrite; ++i) {
        buffer_[(w + i) & mask_] = data[i];
    }

    writeIndex_.store(w + toWrite, std::memory_order_release);
    return toWrite;
}

size_t LockFreeAudioBuffer::read(float* data, size_t count) {
    size_t r = readIndex_.load(std::memory_order_relaxed);
    size_t w = writeIndex_.load(std::memory_order_acquire);
    size_t available = w - r;
    size_t toRead = std::min(count, available);

    for (size_t i = 0; i < toRead; ++i) {
        data[i] = buffer_[(r + i) & mask_];
    }

    readIndex_.store(r + toRead, std::memory_order_release);
    return toRead;
}

size_t LockFreeAudioBuffer::availableRead() const {
    size_t w = writeIndex_.load(std::memory_order_acquire);
    size_t r = readIndex_.load(std::memory_order_relaxed);
    return w - r;
}

size_t LockFreeAudioBuffer::availableWrite() const {
    size_t w = writeIndex_.load(std::memory_order_relaxed);
    size_t r = readIndex_.load(std::memory_order_acquire);
    return capacity_ - (w - r);
}

void LockFreeAudioBuffer::reset() {
    writeIndex_.store(0, std::memory_order_relaxed);
    readIndex_.store(0, std::memory_order_relaxed);
    std::fill(buffer_.begin(), buffer_.end(), 0.0f);
}

// -------------------------------------------------------------
// BiquadFilter Formulas
// -------------------------------------------------------------
void BiquadFilter::setHighPass(float sampleRate, float cutoffHz, float q) {
    float omega = 2.0f * M_PI * cutoffHz / sampleRate;
    float alpha = sinf(omega) / (2.0f * q);
    float cosw = cosf(omega);

    float a0 = 1.0f + alpha;
    b0 = ((1.0f + cosw) / 2.0f) / a0;
    b1 = (-(1.0f + cosw)) / a0;
    b2 = ((1.0f + cosw) / 2.0f) / a0;
    a1 = (-2.0f * cosw) / a0;
    a2 = (1.0f - alpha) / a0;
}

void BiquadFilter::setPeaking(float sampleRate, float centerHz, float gainDb, float q) {
    float A = powf(10.0f, gainDb / 40.0f);
    float omega = 2.0f * M_PI * centerHz / sampleRate;
    float alpha = sinf(omega) / (2.0f * q);
    float cosw = cosf(omega);

    float a0 = 1.0f + alpha / A;
    b0 = (1.0f + alpha * A) / a0;
    b1 = (-2.0f * cosw) / a0;
    b2 = (1.0f - alpha * A) / a0;
    a1 = (-2.0f * cosw) / a0;
    a2 = (1.0f - alpha / A) / a0;
}

void BiquadFilter::setNotch(float sampleRate, float notchHz, float q) {
    float omega = 2.0f * M_PI * notchHz / sampleRate;
    float alpha = sinf(omega) / (2.0f * q);
    float cosw = cosf(omega);

    float a0 = 1.0f + alpha;
    b0 = 1.0f / a0;
    b1 = (-2.0f * cosw) / a0;
    b2 = 1.0f / a0;
    a1 = (-2.0f * cosw) / a0;
    a2 = (1.0f - alpha) / a0;
}

// -------------------------------------------------------------
// NativeAudioEngine Implementation
// -------------------------------------------------------------
NativeAudioEngine::NativeAudioEngine() {
    ringBuffer_ = std::make_unique<LockFreeAudioBuffer>(4096);
    hpfFilter_.setHighPass(sampleRate_, 80.0f, 0.7071f);
    eqMidFilter_.setPeaking(sampleRate_, 3000.0f, 3.0f, 1.2f);
}

NativeAudioEngine::~NativeAudioEngine() {
    stop();
}

bool NativeAudioEngine::start() {
    if (isRunning_.load()) return true;

    ringBuffer_->reset();
    recordBuffer_.reset();
    soundFrame_ = 96000;
    isMuted_.store(false);
    dcBlockerPrevX_ = dcBlockerPrevY_ = 0.0f;
    hpfFilter_.reset(); eqMidFilter_.reset();
    appliedPreset_ = -1;
    smoothedGain_ = 0.0f;
    if (!openStreams()) {
        closeStreams();
        LOGE("Failed to open audio streams");
        return false;
    }

    isRunning_.store(true);
    LOGI("Audio Engine successfully started");
    return true;
}

void NativeAudioEngine::stop() {
    isRunning_.store(false);
    closeStreams();
    LOGI("Audio Engine stopped");
}

bool NativeAudioEngine::openStreams() {
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
             ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
             ->setSharingMode(oboe::SharingMode::Exclusive)
             ->setFormat(oboe::AudioFormat::Float)
             ->setChannelCount(oboe::ChannelCount::Mono)
             ->setSampleRate(48000)
             ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
             ->setInputPreset(oboe::InputPreset::VoiceCommunication)
             ->setDataCallback(this);

    oboe::Result res = inBuilder.openStream(inputStream_);
    if (res != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(res));
        return false;
    }

    sampleRate_ = static_cast<float>(inputStream_->getSampleRate());
    hpfFilter_.setHighPass(sampleRate_, 80.0f, 0.7071f);

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
              ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
              ->setSharingMode(oboe::SharingMode::Shared)
              ->setFormat(oboe::AudioFormat::Float)
              ->setChannelCount(oboe::ChannelCount::Mono)
              ->setUsage(oboe::Usage::Media)
              ->setContentType(oboe::ContentType::Speech)
              ->setSampleRate(static_cast<int32_t>(sampleRate_))
              ->setDataCallback(this);

    res = outBuilder.openStream(outputStream_);
    if (res != oboe::Result::OK) {
        LOGE("Failed to open output stream: %s", oboe::convertToText(res));
        inputStream_->close();
        return false;
    }

    if (inputStream_->requestStart() != oboe::Result::OK) return false;
    if (outputStream_->requestStart() != oboe::Result::OK) return false;
    return true;
}

void NativeAudioEngine::closeStreams() {
    if (inputStream_) {
        inputStream_->stop();
        inputStream_->close();
        inputStream_.reset();
    }
    if (outputStream_) {
        outputStream_->stop();
        outputStream_->close();
        outputStream_.reset();
    }
}

void NativeAudioEngine::setInputGain(float gainDb) {
    float lin = powf(10.0f, gainDb / 20.0f);
    linearGain_.store(lin, std::memory_order_relaxed);
}

void NativeAudioEngine::setMuted(bool muted) {
    isMuted_.store(muted, std::memory_order_relaxed);
}

void NativeAudioEngine::setVoicePreset(int presetId) {
    requestedPreset_.store(presetId, std::memory_order_relaxed);
}

void NativeAudioEngine::applyVoicePreset(int presetId) {
    // 0: Natural, 1: Clear Speech, 2: Loud Speech, 3: Warm, 4: Outdoor, 5: Karaoke, 6: Studio Vocal, 7: Vocal Chorus, 8: Robot Mod
    switch (presetId) {
        case 1: // Clear Speech
            hpfFilter_.setHighPass(sampleRate_, 100.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 3000.0f, 4.5f, 1.2f);
            break;
        case 2: // Loud Speech
            hpfFilter_.setHighPass(sampleRate_, 120.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 2800.0f, 6.0f, 1.4f);
            break;
        case 3: // Warm Voice
            hpfFilter_.setHighPass(sampleRate_, 70.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 2200.0f, 1.5f, 1.0f);
            break;
        case 4: // Outdoor
            hpfFilter_.setHighPass(sampleRate_, 140.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 3200.0f, 5.0f, 1.3f);
            break;
        case 5: // Karaoke (Singing Reverb & Echo)
            hpfFilter_.setHighPass(sampleRate_, 65.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 4500.0f, 3.5f, 0.9f);
            break;
        case 6: // Studio Vocal
            hpfFilter_.setHighPass(sampleRate_, 70.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 3500.0f, 2.5f, 1.0f);
            break;
        case 7: // Vocal Chorus (Voice Modulation)
            hpfFilter_.setHighPass(sampleRate_, 80.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 2500.0f, 2.0f, 1.0f);
            break;
        case 8: // Robot Mod
            hpfFilter_.setHighPass(sampleRate_, 150.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 1800.0f, 3.0f, 1.5f);
            break;
        default: // Natural
            hpfFilter_.setHighPass(sampleRate_, 80.0f, 0.7071f);
            eqMidFilter_.setPeaking(sampleRate_, 3000.0f, 0.0f, 1.0f);
            break;
    }
}

void NativeAudioEngine::setFeedbackSuppressionEnabled(bool enabled) {
    feedbackProtection_.store(enabled, std::memory_order_relaxed);
}

int64_t NativeAudioEngine::getUnderrunCount() const {
    if (outputStream_) {
        auto result = outputStream_->getXRunCount();
        if (result) return result.value();
    }
    return 0;
}

float NativeAudioEngine::nextSound() {
    const int request = soundRequest_.load(std::memory_order_relaxed);
    if (request != soundConsumed_) { soundConsumed_ = request; soundEffect_ = request & 3; soundFrame_ = 0; }
    if (soundFrame_ >= 96000) return 0.0f;
    const float t = soundFrame_++ / sampleRate_;
    float y = 0;
    if (soundEffect_ == 1) {
        const float phase = t < .6f ? t : t - .6f;
        y = sinf(2 * M_PI * (t < .6f ? 587 : 880) * phase) * expf(-5 * phase);
    } else if (soundEffect_ == 2) {
        noise_ ^= noise_ << 13; noise_ ^= noise_ >> 17; noise_ ^= noise_ << 5;
        y = (static_cast<float>(noise_) / 2147483648.0f - 1.0f) * expf(-1.8f * t);
    } else if (soundEffect_ == 3) {
        y = sinf(2 * M_PI * (650 * t - 180 / (2 * M_PI * 1.5f) * cosf(2 * M_PI * 1.5f * t)));
    }
    return y * .23f * std::min(1.0f, t * 200) * std::clamp((2.0f - t) * 100, 0.0f, 1.0f);
}

void NativeAudioEngine::setBgmTrack(int trackId) {
    bgmTrack_.store(trackId, std::memory_order_relaxed);
    if (trackId == 0) bgmFrame_ = 0;
}

void NativeAudioEngine::setBgmVolume(float volume) {
    bgmVolume_.store(std::clamp(volume, 0.0f, 1.0f), std::memory_order_relaxed);
}

float NativeAudioEngine::nextBgm() {
    const int track = bgmTrack_.load(std::memory_order_relaxed);
    if (track <= 0) return 0.0f;
    const float vol = bgmVolume_.load(std::memory_order_relaxed);
    const float t = static_cast<float>(bgmFrame_++) / sampleRate_;
    float sample = 0.0f;

    if (track == 1) {
        // Track 1: Lo-Fi Chill / Acoustic Chords (80 BPM, Am7 -> Fmaj7 -> C -> G)
        float loopT = fmodf(t, 12.0f);
        int chordIdx = static_cast<int>(loopT / 3.0f);
        float chordPhase = fmodf(loopT, 3.0f);
        float chordEnv = expf(-1.1f * chordPhase) + 0.18f * expf(-1.5f * fmodf(chordPhase, 1.5f));

        float f0 = 110.0f, f1 = 130.81f, f2 = 164.81f, f3 = 196.00f; // Am7
        if (chordIdx == 1) { f0 = 87.31f; f1 = 110.0f; f2 = 130.81f; f3 = 164.81f; } // Fmaj7
        else if (chordIdx == 2) { f0 = 130.81f; f1 = 164.81f; f2 = 196.0f; f3 = 246.94f; } // Cmaj
        else if (chordIdx == 3) { f0 = 98.0f; f1 = 123.47f; f2 = 146.83f; f3 = 174.61f; } // G7

        float chordSound = sinf(2.0f * M_PI * f0 * t) * 0.4f +
                           sinf(2.0f * M_PI * f1 * t) * 0.3f +
                           sinf(2.0f * M_PI * f2 * t) * 0.25f +
                           sinf(2.0f * M_PI * f3 * t) * 0.2f;

        float beatT = fmodf(chordPhase, 0.75f);
        float kick = sinf(2.0f * M_PI * (75.0f - 40.0f * beatT) * beatT) * expf(-14.0f * beatT);
        sample = chordSound * chordEnv * 0.35f + kick * 0.12f;
    } else if (track == 2) {
        // Track 2: Pop Acoustic Groove (110 BPM, C -> G -> Am -> F)
        float loopT = fmodf(t, 8.72f);
        int chordIdx = static_cast<int>(loopT / 2.18f);
        float chordPhase = fmodf(loopT, 2.18f);
        float strumPhase = fmodf(chordPhase, 0.2727f);
        float strumEnv = expf(-6.0f * strumPhase);

        float f0 = 130.81f, f1 = 164.81f, f2 = 196.0f; // C
        if (chordIdx == 1) { f0 = 98.0f; f1 = 123.47f; f2 = 146.83f; } // G
        else if (chordIdx == 2) { f0 = 110.0f; f1 = 130.81f; f2 = 164.81f; } // Am
        else if (chordIdx == 3) { f0 = 87.31f; f1 = 110.0f; f2 = 130.81f; } // F

        float strum = (sinf(2.0f * M_PI * f0 * t) + sinf(2.0f * M_PI * f1 * t) + sinf(2.0f * M_PI * f2 * t)) * 0.33f;
        float beatT = fmodf(chordPhase, 0.545f);
        float kick = sinf(2.0f * M_PI * 65.0f * beatT) * expf(-18.0f * beatT);
        sample = strum * strumEnv * 0.40f + kick * 0.15f;
    } else if (track == 3) {
        // Track 3: Ambient Vocal Pad (Slow evolving harmonic drone for singing)
        float sweep = 1.0f + 0.008f * sinf(0.5f * t);
        float pad = sinf(2.0f * M_PI * 130.81f * sweep * t) * 0.35f +
                    sinf(2.0f * M_PI * 196.00f * t) * 0.30f +
                    sinf(2.0f * M_PI * 329.63f * t) * 0.25f +
                    sinf(2.0f * M_PI * 392.00f * sweep * t) * 0.18f;
        sample = pad * 0.45f;
    } else if (track == 4) {
        // Track 4: R&B Rhythm Beat (90 BPM syncopated groove)
        float beatT = fmodf(t, 0.666f);
        float kick = sinf(2.0f * M_PI * (80.0f - 45.0f * beatT) * beatT) * expf(-12.0f * beatT);
        float hatT = fmodf(t, 0.166f);
        noise_ ^= noise_ << 13; noise_ ^= noise_ >> 17; noise_ ^= noise_ << 5;
        float hat = (static_cast<float>(noise_) / 2147483648.0f - 1.0f) * expf(-45.0f * hatT) * 0.08f;
        sample = kick * 0.28f + hat;
    }

    return sample * vol;
}

void NativeAudioEngine::processDsp(float* buffer, int32_t numFrames) {
    const int preset = requestedPreset_.load(std::memory_order_relaxed);
    // Only the output callback owns filter state and coefficients.
    if (preset != appliedPreset_) { applyVoicePreset(preset); appliedPreset_ = preset; }
    float gain = linearGain_.load(std::memory_order_relaxed);
    bool muted = isMuted_.load(std::memory_order_relaxed);
    bool feedbackEnabled = feedbackProtection_.load(std::memory_order_relaxed);

    if (muted) {
        std::fill(buffer, buffer + numFrames, 0.0f);
        currentRmsDb_.store(-96.0f, std::memory_order_relaxed);
        currentPeakDb_.store(-96.0f, std::memory_order_relaxed);
        isFeedbackDetected_.store(false, std::memory_order_relaxed);
        feedbackHoldFrames_ = 0;
        return;
    }

    float sumSq = 0.0f;
    float peak = 0.0f;
    int zeroCrossings = 0;

    for (int32_t i = 0; i < numFrames; ++i) {
        smoothedGain_ += 0.002f * (gain - smoothedGain_);
        float x = buffer[i] * smoothedGain_;

        // 1. DC Blocker
        float yDc = x - dcBlockerPrevX_ + 0.995f * dcBlockerPrevY_;
        dcBlockerPrevX_ = x;
        dcBlockerPrevY_ = yDc;

        // 2. High-Pass Filter
        float yHpf = hpfFilter_.process(yDc);

        // 3. Speech Intelligibility EQ
        float yEq = eqMidFilter_.process(yHpf);

        // 4. Dynamic Feedback Notch Filter
        if (feedbackEnabled && feedbackHoldFrames_ > 0) {
            yEq = feedbackNotchFilter_.process(yEq);
        }

        // 5. Singing Reverb & Voice Modulation DSP
        float yProcessed = yEq;
        if (preset == 5) { // Karaoke: Slapback echo + concert plate reverb
            size_t delaySamples = static_cast<size_t>(sampleRate_ * 0.160f);
            size_t readIndex = (delayWriteIndex_ + kDelayBufferSize - delaySamples) % kDelayBufferSize;
            float delayed = delayBuffer_[readIndex];
            size_t tap2Index = (delayWriteIndex_ + kDelayBufferSize - static_cast<size_t>(sampleRate_ * 0.060f)) % kDelayBufferSize;
            float tap2 = delayBuffer_[tap2Index];
            
            reverbFeedbackLp_ = reverbFeedbackLp_ * 0.6f + delayed * 0.4f;
            delayBuffer_[delayWriteIndex_] = yEq + reverbFeedbackLp_ * 0.38f;
            yProcessed = yEq * 0.85f + (delayed * 0.35f + tap2 * 0.20f);
        } else if (preset == 6) { // Studio Vocal: Smooth room ambience
            size_t delaySamples = static_cast<size_t>(sampleRate_ * 0.045f);
            size_t readIndex = (delayWriteIndex_ + kDelayBufferSize - delaySamples) % kDelayBufferSize;
            float delayed = delayBuffer_[readIndex];
            
            reverbFeedbackLp_ = reverbFeedbackLp_ * 0.7f + delayed * 0.3f;
            delayBuffer_[delayWriteIndex_] = yEq + reverbFeedbackLp_ * 0.25f;
            yProcessed = yEq * 0.92f + delayed * 0.28f;
        } else if (preset == 7) { // Vocal Chorus (Voice Modulation)
            lfoPhase_ += 2.0f * M_PI * 1.2f / sampleRate_;
            if (lfoPhase_ > 2.0f * M_PI) lfoPhase_ -= 2.0f * M_PI;
            float modMs = 0.019f + 0.005f * sinf(lfoPhase_);
            size_t delaySamples = static_cast<size_t>(sampleRate_ * modMs);
            size_t readIndex = (delayWriteIndex_ + kDelayBufferSize - delaySamples) % kDelayBufferSize;
            float delayed = delayBuffer_[readIndex];

            delayBuffer_[delayWriteIndex_] = yEq;
            yProcessed = yEq * 0.65f + delayed * 0.55f;
        } else if (preset == 8) { // Robot Voice (Ring Modulation)
            robotPhase_ += 2.0f * M_PI * 140.0f / sampleRate_;
            if (robotPhase_ > 2.0f * M_PI) robotPhase_ -= 2.0f * M_PI;
            yProcessed = yEq * 0.35f + (yEq * sinf(robotPhase_)) * 0.65f;
            delayBuffer_[delayWriteIndex_] = 0.0f;
        } else {
            delayBuffer_[delayWriteIndex_] = 0.0f;
        }
        delayWriteIndex_ = (delayWriteIndex_ + 1) % kDelayBufferSize;

        // 6. Brickwall Peak Limiter (-0.5 dBFS ceiling approx 0.944)
        const float ceiling = 0.944f;
        float totalMix = yProcessed + nextSound() + nextBgm();
        float yLimited = std::clamp(totalMix, -ceiling, ceiling);

        buffer[i] = yLimited;

        // Detect zero crossings for resonance frequency estimation
        if ((prevSample_ < 0.0f && yLimited >= 0.0f) || (prevSample_ >= 0.0f && yLimited < 0.0f)) {
            zeroCrossings++;
        }
        prevSample_ = yLimited;

        // Accumulate metering
        sumSq += yLimited * yLimited;
        float absVal = std::fabs(yLimited);
        if (absVal > peak) peak = absVal;
    }

    // Update RMS & Peak for VU meter
    float rms = sqrtf(sumSq / static_cast<float>(numFrames));
    float rmsDb = (rms > 1e-5f) ? (20.0f * log10f(rms)) : -96.0f;
    float peakDb = (peak > 1e-5f) ? (20.0f * log10f(peak)) : -96.0f;

    currentRmsDb_.store(rmsDb, std::memory_order_relaxed);
    currentPeakDb_.store(peakDb, std::memory_order_relaxed);

    // Adaptive Feedback Howling Detector
    if (feedbackEnabled) {
        if (feedbackHoldFrames_ > 0) {
            feedbackHoldFrames_ -= numFrames;
            if (feedbackHoldFrames_ <= 0) {
                isFeedbackDetected_.store(false, std::memory_order_relaxed);
                feedbackNotchFilter_.reset();
            }
        } else if (peak > 0.65f && rms > 0.45f && numFrames > 0) {
            // Sustained pure tone resonance signature (high peak-to-average ratio)
            float estFreq = (static_cast<float>(zeroCrossings) * sampleRate_) / (2.0f * static_cast<float>(numFrames));
            if (estFreq >= 500.0f && estFreq <= 7500.0f) {
                feedbackNotchFilter_.setNotch(sampleRate_, estFreq, 8.0f);
                feedbackHoldFrames_ = static_cast<int>(sampleRate_ * 1.5f); // 1.5s suppression
                isFeedbackDetected_.store(true, std::memory_order_relaxed);
            }
        }
    }
}

oboe::DataCallbackResult NativeAudioEngine::onAudioReady(
    oboe::AudioStream* stream,
    void* audioData,
    int32_t numFrames) {

    auto* floatData = static_cast<float*>(audioData);

    if (stream->getDirection() == oboe::Direction::Input) {
        // Microphone captured samples
        ringBuffer_->write(floatData, static_cast<size_t>(numFrames));
    } else {
        // Output playback stream
        size_t readCount = ringBuffer_->read(floatData, static_cast<size_t>(numFrames));
        if (readCount < static_cast<size_t>(numFrames)) {
            // Buffer underrun: fill remaining frames with silence
            std::fill(floatData + readCount, floatData + numFrames, 0.0f);
        }
        processDsp(floatData, numFrames);
        recordBuffer_.write(floatData, static_cast<size_t>(numFrames));
    }

    return oboe::DataCallbackResult::Continue;
}

} // namespace phonemic

// -------------------------------------------------------------
// JNI Export Functions
// -------------------------------------------------------------
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeCreate(JNIEnv* env, jobject thiz) {
    auto* engine = new phonemic::NativeAudioEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeDestroy(JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    delete engine;
}

JNIEXPORT jboolean JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeStart(JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    return engine ? engine->start() : false;
}

JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeStop(JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (engine) engine->stop();
}

JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeSetInputGain(JNIEnv* env, jobject thiz, jlong handle, jfloat gainDb) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (engine) engine->setInputGain(gainDb);
}

JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeSetMuted(JNIEnv* env, jobject thiz, jlong handle, jboolean muted) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (engine) engine->setMuted(muted);
}

JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeSetVoicePreset(JNIEnv* env, jobject thiz, jlong handle, jint presetId) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (engine) engine->setVoicePreset(presetId);
}

JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeSetFeedbackSuppression(JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (engine) engine->setFeedbackSuppressionEnabled(enabled);
}

JNIEXPORT jfloat JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeGetRmsDb(JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    return engine ? engine->getCurrentRmsDb() : -96.0f;
}

JNIEXPORT jfloat JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeGetPeakDb(JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    return engine ? engine->getCurrentPeakDb() : -96.0f;
}

JNIEXPORT jboolean JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeIsFeedbackActive(JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    return engine ? engine->isFeedbackActive() : false;
}

JNIEXPORT jlong JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeGetUnderruns(JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    return engine ? engine->getUnderrunCount() : 0;
}

} // extern "C"

extern "C" JNIEXPORT jint JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeReadProcessed(JNIEnv* env, jobject, jlong handle, jshortArray target) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (!engine) return 0;
    float data[2048];
    jshort pcm[2048];
    const auto count = engine->readProcessed(data, std::min(2048, env->GetArrayLength(target)));
    for (size_t i = 0; i < count; ++i) pcm[i] = static_cast<jshort>(data[i] * 32767);
    env->SetShortArrayRegion(target, 0, static_cast<jsize>(count), pcm);
    return static_cast<jint>(count);
}
extern "C" JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeTriggerSound(JNIEnv*, jobject, jlong handle, jint effect) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (engine) engine->triggerSound(effect);
}

extern "C" JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeSetBgmTrack(JNIEnv*, jobject, jlong handle, jint trackId) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (engine) engine->setBgmTrack(trackId);
}

extern "C" JNIEXPORT void JNICALL
Java_com_chiti_phonemic_audio_NativeAudioEngine_nativeSetBgmVolume(JNIEnv*, jobject, jlong handle, jfloat volume) {
    auto* engine = reinterpret_cast<phonemic::NativeAudioEngine*>(handle);
    if (engine) engine->setBgmVolume(volume);
}
