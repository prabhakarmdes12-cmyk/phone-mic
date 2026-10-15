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
    // 0: Natural, 1: Clear Speech, 2: Loud Speech, 3: Warm, 4: Outdoor
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

        // 5. Brickwall Peak Limiter (-0.5 dBFS ceiling approx 0.944)
        const float ceiling = 0.944f;
        float yLimited = std::clamp(yEq, -ceiling, ceiling);

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
