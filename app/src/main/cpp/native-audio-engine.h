#ifndef PHONEMIC_NATIVE_AUDIO_ENGINE_H
#define PHONEMIC_NATIVE_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <memory>
#include <vector>
#include <cmath>

namespace phonemic {

// Lock-Free Single-Producer Single-Consumer (SPSC) Ring Buffer
class LockFreeAudioBuffer {
public:
    explicit LockFreeAudioBuffer(size_t capacity);
    ~LockFreeAudioBuffer() = default;

    size_t write(const float* data, size_t count);
    size_t read(float* data, size_t count);
    size_t availableRead() const;
    size_t availableWrite() const;
    void reset();

private:
    std::vector<float> buffer_;
    size_t capacity_;
    size_t mask_;
    alignas(64) std::atomic<size_t> writeIndex_{0};
    alignas(64) std::atomic<size_t> readIndex_{0};
};

// Biquad Filter for EQ, HPF, and Notch filtering
struct BiquadFilter {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f;
    float a1 = 0.0f, a2 = 0.0f;
    float z1 = 0.0f, z2 = 0.0f;

    inline float process(float in) {
        float out = in * b0 + z1;
        z1 = in * b1 - a1 * out + z2;
        z2 = in * b2 - a2 * out;
        return out;
    }

    void setHighPass(float sampleRate, float cutoffHz, float q);
    void setPeaking(float sampleRate, float centerHz, float gainDb, float q);
    void setNotch(float sampleRate, float notchHz, float q);
    void reset() { z1 = 0.0f; z2 = 0.0f; }
};

class NativeAudioEngine : public oboe::AudioStreamDataCallback {
public:
    NativeAudioEngine();
    virtual ~NativeAudioEngine();

    bool start();
    void stop();
    bool isRunning() const { return isRunning_.load(); }

    void setInputGain(float gainDb);
    void setMuted(bool muted);
    void setVoicePreset(int presetId);
    void setFeedbackSuppressionEnabled(bool enabled);

    float getCurrentRmsDb() const { return currentRmsDb_.load(); }
    float getCurrentPeakDb() const { return currentPeakDb_.load(); }
    bool isFeedbackActive() const { return isFeedbackDetected_.load(std::memory_order_relaxed); }
    int64_t getUnderrunCount() const;

    size_t readProcessed(float* data, size_t count) { return recordBuffer_.read(data, count); }
    void triggerSound(int effect) { soundRequest_.store(((soundRequest_.load() + 4) & ~3) | effect); }
    float nextSound();
    void setBgmTrack(int trackId);
    void setBgmVolume(float volume);
    float nextBgm();
    // Oboe Callbacks
    oboe::DataCallbackResult onAudioReady(
        oboe::AudioStream* stream,
        void* audioData,
        int32_t numFrames) override;

private:
    bool openStreams();
    void closeStreams();
    void processDsp(float* buffer, int32_t numFrames);

    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::unique_ptr<LockFreeAudioBuffer> ringBuffer_;

    LockFreeAudioBuffer recordBuffer_{65536};
    std::atomic<int> soundRequest_{0};
    int soundConsumed_ = 0, soundEffect_ = 0, soundFrame_ = 96000;
    uint32_t noise_ = 1234567;
    std::atomic<bool> isRunning_{false};
    std::atomic<bool> isMuted_{false};
    std::atomic<float> linearGain_{1.0f};
    std::atomic<bool> feedbackProtection_{true};

    std::atomic<int> requestedPreset_{0};
    int appliedPreset_ = -1;
    float smoothedGain_ = 0.0f;
    void applyVoicePreset(int presetId);

    // Metering
    std::atomic<float> currentRmsDb_{-96.0f};
    std::atomic<float> currentPeakDb_{-96.0f};

    // DSP components
    float dcBlockerPrevX_ = 0.0f;
    float dcBlockerPrevY_ = 0.0f;
    BiquadFilter hpfFilter_;
    BiquadFilter eqLowFilter_;
    BiquadFilter eqMidFilter_;
    BiquadFilter eqHighFilter_;
    BiquadFilter feedbackNotchFilter_;

    // Feedback suppression state
    std::atomic<bool> isFeedbackDetected_{false};
    int feedbackHoldFrames_ = 0;
    float prevSample_ = 0.0f;
    int zeroCrossings_ = 0;
    float peakInBlock_ = 0.0f;

    // Compressor & Limiter State
    float compressorEnv_ = 0.0f;
    float sampleRate_ = 48000.0f;

    // Reverb, Vocal Modulation & Delay (for Singing)
    static constexpr size_t kDelayBufferSize = 16384;
    std::vector<float> delayBuffer_{kDelayBufferSize, 0.0f};
    size_t delayWriteIndex_ = 0;
    float reverbFeedbackLp_ = 0.0f;
    float lfoPhase_ = 0.0f;
    float robotPhase_ = 0.0f;

    // Continuous Background Music (BGM) Synthesizer
    std::atomic<int> bgmTrack_{0};
    std::atomic<float> bgmVolume_{0.35f};
    int64_t bgmFrame_ = 0;
};

} // namespace phonemic

#endif // PHONEMIC_NATIVE_AUDIO_ENGINE_H
