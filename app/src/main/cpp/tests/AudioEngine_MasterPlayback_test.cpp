#include <gtest/gtest.h>

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#include "engine/AudioEngine.h"
#include "engine/PlaybackLaneLifecycle.h"

namespace dawengine {
namespace {

std::string WriteTempMonoWav(const std::string &path, int32_t sampleRate, int32_t frameCount) {
    std::FILE *file = std::fopen(path.c_str(), "wb");
    if (!file) return {};

    const uint16_t channels = 1;
    const uint16_t bitsPerSample = 16;
    const uint32_t byteRate =
        static_cast<uint32_t>(sampleRate) * channels * (bitsPerSample / 8u);
    const uint16_t blockAlign = channels * (bitsPerSample / 8u);
    const uint32_t dataSize =
        static_cast<uint32_t>(frameCount) * static_cast<uint32_t>(blockAlign);
    const uint32_t riffSize = 36u + dataSize;

    std::fwrite("RIFF", 1, 4, file);
    const uint8_t riffSizeBytes[4] = {
        static_cast<uint8_t>(riffSize & 0xFFu),
        static_cast<uint8_t>((riffSize >> 8u) & 0xFFu),
        static_cast<uint8_t>((riffSize >> 16u) & 0xFFu),
        static_cast<uint8_t>((riffSize >> 24u) & 0xFFu),
    };
    std::fwrite(riffSizeBytes, 1, 4, file);
    std::fwrite("WAVE", 1, 4, file);
    std::fwrite("fmt ", 1, 4, file);
    const uint32_t fmtSize = 16u;
    std::fwrite(reinterpret_cast<const char *>(&fmtSize), 4, 1, file);
    const uint16_t audioFormat = 1u;
    std::fwrite(reinterpret_cast<const char *>(&audioFormat), 2, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&channels), 2, 1, file);
    const uint32_t sr = static_cast<uint32_t>(sampleRate);
    std::fwrite(reinterpret_cast<const char *>(&sr), 4, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&byteRate), 4, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&blockAlign), 2, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&bitsPerSample), 2, 1, file);
    std::fwrite("data", 1, 4, file);
    std::fwrite(reinterpret_cast<const char *>(&dataSize), 4, 1, file);
    std::vector<int16_t> silence(static_cast<std::size_t>(frameCount), 0);
    std::fwrite(silence.data(), sizeof(int16_t), silence.size(), file);
    std::fclose(file);
    return path;
}

std::string WriteTempMonoWavFilled(const std::string &path,
                                   int32_t sampleRate,
                                   int32_t frameCount,
                                   int16_t sampleValue) {
    std::FILE *file = std::fopen(path.c_str(), "wb");
    if (!file) return {};

    const uint16_t channels = 1;
    const uint16_t bitsPerSample = 16;
    const uint32_t byteRate =
        static_cast<uint32_t>(sampleRate) * channels * (bitsPerSample / 8u);
    const uint16_t blockAlign = channels * (bitsPerSample / 8u);
    const uint32_t dataSize =
        static_cast<uint32_t>(frameCount) * static_cast<uint32_t>(blockAlign);
    const uint32_t riffSize = 36u + dataSize;

    std::fwrite("RIFF", 1, 4, file);
    const uint8_t riffSizeBytes[4] = {
        static_cast<uint8_t>(riffSize & 0xFFu),
        static_cast<uint8_t>((riffSize >> 8u) & 0xFFu),
        static_cast<uint8_t>((riffSize >> 16u) & 0xFFu),
        static_cast<uint8_t>((riffSize >> 24u) & 0xFFu),
    };
    std::fwrite(riffSizeBytes, 1, 4, file);
    std::fwrite("WAVE", 1, 4, file);
    std::fwrite("fmt ", 1, 4, file);
    const uint32_t fmtSize = 16u;
    std::fwrite(reinterpret_cast<const char *>(&fmtSize), 4, 1, file);
    const uint16_t audioFormat = 1u;
    std::fwrite(reinterpret_cast<const char *>(&audioFormat), 2, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&channels), 2, 1, file);
    const uint32_t sr = static_cast<uint32_t>(sampleRate);
    std::fwrite(reinterpret_cast<const char *>(&sr), 4, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&byteRate), 4, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&blockAlign), 2, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&bitsPerSample), 2, 1, file);
    std::fwrite("data", 1, 4, file);
    std::fwrite(reinterpret_cast<const char *>(&dataSize), 4, 1, file);
    const std::vector<int16_t> samples(static_cast<std::size_t>(frameCount), sampleValue);
    std::fwrite(samples.data(), sizeof(int16_t), samples.size(), file);
    std::fclose(file);
    return path;
}

float PeakAbsInterleaved(const float *buffer, std::size_t sampleCount) {
    float peak = 0.0f;
    for (std::size_t i = 0; i < sampleCount; ++i) {
        peak = std::max(peak, std::fabs(buffer[i]));
    }
    return peak;
}

class AudioEngineMasterPlaybackTest : public ::testing::Test {
protected:
    void SetUp() override {
        engine.configureProject(48'000, 16);
        wavPath = WriteTempMonoWav("master_playback_test.wav", 48'000, 48'000 * 4);
        ASSERT_FALSE(wavPath.empty());
    }

    AudioEngine engine;
    std::string wavPath;
};

TEST_F(AudioEngineMasterPlaybackTest, InitializesFromStartPositionMs) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    EXPECT_EQ(0, engine.transportStartFrame());
    EXPECT_EQ(0, engine.transportFrame());
    EXPECT_EQ(0, engine.transportPositionMs());
    engine.stopPlayback();

    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 30'000L));
    const int64_t expectedStart = (48'000LL * 30'000LL) / 1000LL;
    EXPECT_EQ(expectedStart, engine.transportStartFrame());
    EXPECT_EQ(expectedStart, engine.transportFrame());
    EXPECT_EQ(30'000, engine.transportPositionMs());
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, RenderAdvancesByOutputCallbackFrames) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    const int64_t start = engine.transportFrame();

    std::vector<float> buffer(512 * 2, 0.0f);
    engine.render(buffer.data(), 512, 2, 48'000);
    engine.render(buffer.data(), 256, 2, 48'000);

    EXPECT_EQ(start + 512 + 256, engine.transportFrame());
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, StopsAdvancingWhenPlaybackInactive) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    std::vector<float> buffer(128 * 2, 0.0f);
    engine.render(buffer.data(), 128, 2, 48'000);
    ASSERT_GT(engine.transportFrame(), 0);

    engine.stopPlayback();
    EXPECT_EQ(0, engine.transportFrame());
    EXPECT_EQ(0, engine.transportStartFrame());

    engine.render(buffer.data(), 128, 2, 48'000);
    EXPECT_EQ(0, engine.transportFrame());
}

TEST_F(AudioEngineMasterPlaybackTest, ResetsOnStopAndRelease) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 10'000L));
    ASSERT_GT(engine.transportFrame(), 0);
    engine.stopPlayback();
    EXPECT_EQ(0, engine.transportFrame());
    EXPECT_EQ(0, engine.transportStartFrame());

    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 5'000L));
    engine.releasePlaybackResources();
    EXPECT_EQ(0, engine.transportFrame());
    EXPECT_EQ(0, engine.transportStartFrame());
}

TEST_F(AudioEngineMasterPlaybackTest, TimelinePositionAfterRenderedSecond) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 30'000L));
    const int64_t startFrame = engine.transportFrame();
    ASSERT_EQ(30'000, engine.transportPositionMs());

    std::vector<float> buffer(48'000 * 2, 0.0f);
    engine.render(buffer.data(), 48'000, 2, 48'000);

    EXPECT_EQ(startFrame + 48'000, engine.transportFrame());
    EXPECT_EQ(31'000, engine.transportPositionMs());
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, HotJoinCommitsAtCurrentMasterFrame) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    std::vector<float> buffer(4'800 * 2, 0.0f);
    engine.render(buffer.data(), 4'800, 2, 48'000);
    const int64_t masterBeforeJoin = engine.transportFrame();
    ASSERT_GT(masterBeforeJoin, 0);

    const std::string secondWav = WriteTempMonoWav("master_playback_join.wav", 48'000, 48'000 * 4);
    ASSERT_FALSE(secondWav.empty());
    const int32_t laneIndex = engine.beginHotJoinLane(secondWav, 1.0f);
    ASSERT_EQ(1, laneIndex);

    for (int attempt = 0; attempt < 500; ++attempt) {
        if (engine.laneLifecycle(static_cast<std::size_t>(laneIndex)) ==
            PlaybackLaneLifecycle::Active) {
            break;
        }
        engine.render(buffer.data(), 256, 2, 48'000);
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    EXPECT_EQ(PlaybackLaneLifecycle::Active,
              engine.laneLifecycle(static_cast<std::size_t>(laneIndex)));
    EXPECT_GE(engine.transportFrame(), masterBeforeJoin);
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, CancelPreventsAudibleCommit) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    const std::string secondWav = WriteTempMonoWav("master_playback_cancel_audible.wav", 48'000, 48'000);
    ASSERT_FALSE(secondWav.empty());
    const int32_t laneIndex = engine.beginHotJoinLane(secondWav, 1.0f);
    ASSERT_EQ(1, laneIndex);

    constexpr int32_t kRenderFrames = 256;
    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    bool cancelledHotJoinLane = false;
    for (int attempt = 0; attempt < 200'000; ++attempt) {
        const PlaybackLaneLifecycle state =
            engine.laneLifecycle(static_cast<std::size_t>(laneIndex));
        if (state == PlaybackLaneLifecycle::ReadyToCommit ||
            state == PlaybackLaneLifecycle::Active) {
            engine.cancelHotJoinLane(static_cast<std::size_t>(laneIndex));
            const PlaybackLaneLifecycle afterCancel =
                engine.laneLifecycle(static_cast<std::size_t>(laneIndex));
            if (state == PlaybackLaneLifecycle::ReadyToCommit) {
                EXPECT_TRUE(afterCancel == PlaybackLaneLifecycle::Inactive ||
                            afterCancel == PlaybackLaneLifecycle::Active);
            } else {
                EXPECT_EQ(PlaybackLaneLifecycle::Active, afterCancel);
            }
            cancelledHotJoinLane = true;
            break;
        }
        engine.render(buffer.data(), kRenderFrames, 2, 48'000);
    }
    ASSERT_TRUE(cancelledHotJoinLane);

    for (int attempt = 0; attempt < 64; ++attempt) {
        engine.render(buffer.data(), kRenderFrames, 2, 48'000);
    }
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, CommitSkippedAfterPlaybackStopped) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    const std::string secondWav = WriteTempMonoWav("master_playback_stop_commit.wav", 48'000, 48'000);
    ASSERT_FALSE(secondWav.empty());
    const int32_t laneIndex = engine.beginHotJoinLane(secondWav, 1.0f);
    ASSERT_EQ(1, laneIndex);
    engine.stopPlayback();
    for (int attempt = 0; attempt < 500; ++attempt) {
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
        if (engine.laneLifecycle(static_cast<std::size_t>(laneIndex)) ==
            PlaybackLaneLifecycle::Inactive) {
            break;
        }
    }
    EXPECT_EQ(PlaybackLaneLifecycle::Inactive,
              engine.laneLifecycle(static_cast<std::size_t>(laneIndex)));
}

TEST_F(AudioEngineMasterPlaybackTest, CancelHotJoinBeforeCommit) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    const std::string secondWav = WriteTempMonoWav("master_playback_cancel.wav", 48'000, 48'000);
    ASSERT_FALSE(secondWav.empty());
    const int32_t laneIndex = engine.beginHotJoinLane(secondWav, 1.0f);
    ASSERT_EQ(1, laneIndex);
    engine.cancelHotJoinLane(static_cast<std::size_t>(laneIndex));
    EXPECT_EQ(PlaybackLaneLifecycle::Inactive,
              engine.laneLifecycle(static_cast<std::size_t>(laneIndex)));
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, InaudibleArmedLaneStillCompletes) {
    const std::string shortWav = WriteTempMonoWav("master_playback_inaudible.wav", 48'000, 512);
    ASSERT_FALSE(shortWav.empty());
    ASSERT_TRUE(engine.setPlaybackSource(shortWav, 1.0f, 0L));
    engine.setPlaybackLaneAudible(0, false);

    std::vector<float> buffer(256 * 2, 0.0f);
    for (int attempt = 0; attempt < 200 && engine.isPlaybackActive(); ++attempt) {
        engine.render(buffer.data(), 256, 2, 48'000);
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    EXPECT_FALSE(engine.isPlaybackActive());
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, FreezesOnNaturalCompletion) {
    const std::string shortWav = WriteTempMonoWav("master_playback_short.wav", 48'000, 512);
    ASSERT_FALSE(shortWav.empty());
    ASSERT_TRUE(engine.setPlaybackSource(shortWav, 1.0f, 0L));

    std::vector<float> buffer(256 * 2, 0.0f);
    for (int attempt = 0; attempt < 200 && engine.isPlaybackActive(); ++attempt) {
        engine.render(buffer.data(), 256, 2, 48'000);
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    ASSERT_FALSE(engine.isPlaybackActive());

    const int64_t frozen = engine.transportFrame();
    ASSERT_GT(frozen, 0);
    engine.render(buffer.data(), 256, 2, 48'000);
    EXPECT_EQ(frozen, engine.transportFrame());

    engine.stopPlayback();
    EXPECT_EQ(0, engine.transportFrame());
}

TEST_F(AudioEngineMasterPlaybackTest, TransportAdvancesThroughSessionEndWhenLaneExhaustedAtStart) {
    const std::string shortWav = WriteTempMonoWav("master_playback_session_end.wav", 48'000, 256);
    ASSERT_FALSE(shortWav.empty());
    constexpr int64_t kStartMs = 5'000L;
    constexpr int64_t kSessionEndMs = 30'000L;
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    ASSERT_TRUE(engine.setPlaybackSource(shortWav, 1.0f, kStartMs, kSessionEndMs));

    const int64_t startFrame = engine.transportFrame();
    ASSERT_GT(startFrame, 0);

    const int64_t sessionEndFrame = (kSessionEndMs * static_cast<int64_t>(kSampleRateHz)) / 1000L;
    const int64_t framesToSessionEnd = sessionEndFrame - startFrame;
    ASSERT_GT(framesToSessionEnd, 0);
    const int maxRenderBlocks =
        static_cast<int>((framesToSessionEnd + static_cast<int64_t>(kRenderFrames) - 1) /
                         static_cast<int64_t>(kRenderFrames)) +
        8;

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    for (int attempt = 0; attempt < maxRenderBlocks && engine.isPlaybackActive(); ++attempt) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
    }

    EXPECT_FALSE(engine.isPlaybackActive());
    EXPECT_GE(engine.transportPositionMs(), kSessionEndMs - 50);
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, LiveLaneGainUpdatesDuringRender) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int16_t kSampleValue = 20'000;

    const std::string wav = WriteTempMonoWavFilled(
        "live_lane_gain.wav", kSampleRateHz, kSampleRateHz * 4, kSampleValue);
    ASSERT_FALSE(wav.empty());
    ASSERT_TRUE(engine.setPlaybackSource(wav, 1.0f, 0L));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    const std::size_t bufferSamples = buffer.size();

    float peakAtFullGain = 0.0f;
    for (int block = 0; block < 32; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        peakAtFullGain =
            std::max(peakAtFullGain, PeakAbsInterleaved(buffer.data(), bufferSamples));
        if (peakAtFullGain > 0.05f) {
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    ASSERT_GT(peakAtFullGain, 0.05f);

    engine.setPlaybackLaneGain(0, 0.25f);

    float peakAtReducedGain = 0.0f;
    for (int block = 0; block < 32; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        peakAtReducedGain =
            std::max(peakAtReducedGain, PeakAbsInterleaved(buffer.data(), bufferSamples));
    }
    ASSERT_GT(peakAtReducedGain, 0.01f);
    EXPECT_LT(peakAtReducedGain, peakAtFullGain * 0.55f);

    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, MultiLaneClipOffsetsLaneBStartsAtFiveSeconds) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kLaneBClipStartMs = 5'000L;
    constexpr int16_t kLaneASample = 10'000;
    constexpr int16_t kLaneBSample = 30'000;
    constexpr int64_t kLaneBClipStartFrame =
        (kLaneBClipStartMs * static_cast<int64_t>(kSampleRateHz)) / 1000L;

    const std::string wavA = WriteTempMonoWavFilled(
        "multi_lane_offset_a.wav", kSampleRateHz, kSampleRateHz * 12, kLaneASample);
    const std::string wavB = WriteTempMonoWavFilled(
        "multi_lane_offset_b.wav", kSampleRateHz, kSampleRateHz * 10, kLaneBSample);
    ASSERT_FALSE(wavA.empty());
    ASSERT_FALSE(wavB.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{wavA, wavB},
        std::vector<float>{1.0f, 1.0f},
        0L,
        0L,
        std::vector<int64_t>{0L, kLaneBClipStartMs},
        {}));

    EXPECT_EQ(0, engine.transportFrame());
    EXPECT_EQ(0, engine.transportPositionMs());
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(0));
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(1));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    const std::size_t bufferSamples = buffer.size();

    int64_t prevTransportMs = -1;
    float laneAPeakBeforeClipStart = 0.0f;
    const int blocksBeforeClipStart =
        static_cast<int>(kLaneBClipStartFrame / static_cast<int64_t>(kRenderFrames));
    ASSERT_GT(blocksBeforeClipStart, 0);
    for (int block = 0; block < blocksBeforeClipStart; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        const int64_t transportMs = engine.transportPositionMs();
        ASSERT_GT(transportMs, prevTransportMs);
        prevTransportMs = transportMs;
        laneAPeakBeforeClipStart =
            std::max(laneAPeakBeforeClipStart, PeakAbsInterleaved(buffer.data(), bufferSamples));
    }

    EXPECT_LT(engine.transportPositionMs(), kLaneBClipStartMs);
    EXPECT_GT(laneAPeakBeforeClipStart, 0.05f);
    const float laneOnlyPeakBeforeB =
        std::max(laneAPeakBeforeClipStart, PeakAbsInterleaved(buffer.data(), bufferSamples));
    // Lane A only (~10k PCM); lane B (~30k) must not be in the mix yet (~40k sum would be ~1.2f).
    EXPECT_LT(laneOnlyPeakBeforeB, 0.40f);

    const int blocksToCrossClipStart =
        static_cast<int>((kLaneBClipStartFrame + static_cast<int64_t>(kRenderFrames) - 1) /
                         static_cast<int64_t>(kRenderFrames)) -
        blocksBeforeClipStart + 4;
    constexpr int kBlocksAfterClipStart = 128;
    float laneMixPeakAfterClipStart = 0.0f;
    bool crossedClipStart = false;
    for (int block = 0; block < blocksToCrossClipStart + kBlocksAfterClipStart; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        const int64_t transportMs = engine.transportPositionMs();
        ASSERT_GT(transportMs, prevTransportMs);
        prevTransportMs = transportMs;
        if (transportMs >= kLaneBClipStartMs) {
            crossedClipStart = true;
            laneMixPeakAfterClipStart = std::max(
                laneMixPeakAfterClipStart, PeakAbsInterleaved(buffer.data(), bufferSamples));
        }
    }

    EXPECT_TRUE(crossedClipStart);
    EXPECT_GE(engine.transportPositionMs(), kLaneBClipStartMs);
    EXPECT_GT(laneMixPeakAfterClipStart, laneOnlyPeakBeforeB * 1.35f);
    EXPECT_GT(laneMixPeakAfterClipStart, 0.55f);

    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, ReopensSingleLaneSourceWhenSamePathFileContentChanges) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    const std::string path = "same_path_reopen.wav";

    WriteTempMonoWavFilled(path, kSampleRateHz, kSampleRateHz, 0);
    ASSERT_TRUE(engine.setPlaybackSource(path, 1.0f, 0L));
    engine.stopPlayback();

    WriteTempMonoWavFilled(path, kSampleRateHz, kSampleRateHz, 20'000);
    ASSERT_TRUE(engine.setPlaybackSource(path, 1.0f, 0L));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
    EXPECT_GT(PeakAbsInterleaved(buffer.data(), buffer.size()), 0.05f);

    engine.stopPlayback();
    std::remove(path.c_str());
}

TEST_F(AudioEngineMasterPlaybackTest, KeepsSingleLaneSourceWhenSamePathFileIsUnchanged) {
    constexpr int32_t kSampleRateHz = 48'000;
    const std::string path = "same_path_unchanged.wav";

    WriteTempMonoWavFilled(path, kSampleRateHz, kSampleRateHz, 20'000);
    ASSERT_TRUE(engine.setPlaybackSource(path, 1.0f, 0L));
    engine.stopPlayback();
    ASSERT_TRUE(engine.setPlaybackSource(path, 1.0f, 0L));
    engine.stopPlayback();
    std::remove(path.c_str());
}

std::string WriteTempMonoWavSegmented(const std::string &path,
                                      int32_t sampleRate,
                                      const std::vector<std::pair<int32_t, int16_t>> &segments) {
    int32_t totalFrames = 0;
    for (const auto &segment : segments) {
        totalFrames += segment.first;
    }
    if (totalFrames <= 0) return {};

    std::FILE *file = std::fopen(path.c_str(), "wb");
    if (!file) return {};

    const uint16_t channels = 1;
    const uint16_t bitsPerSample = 16;
    const uint32_t byteRate =
        static_cast<uint32_t>(sampleRate) * channels * (bitsPerSample / 8u);
    const uint16_t blockAlign = channels * (bitsPerSample / 8u);
    const uint32_t dataSize =
        static_cast<uint32_t>(totalFrames) * static_cast<uint32_t>(blockAlign);
    const uint32_t riffSize = 36u + dataSize;

    std::fwrite("RIFF", 1, 4, file);
    const uint8_t riffSizeBytes[4] = {
        static_cast<uint8_t>(riffSize & 0xFFu),
        static_cast<uint8_t>((riffSize >> 8u) & 0xFFu),
        static_cast<uint8_t>((riffSize >> 16u) & 0xFFu),
        static_cast<uint8_t>((riffSize >> 24u) & 0xFFu),
    };
    std::fwrite(riffSizeBytes, 1, 4, file);
    std::fwrite("WAVE", 1, 4, file);
    std::fwrite("fmt ", 1, 4, file);
    const uint32_t fmtSize = 16u;
    std::fwrite(reinterpret_cast<const char *>(&fmtSize), 4, 1, file);
    const uint16_t audioFormat = 1u;
    std::fwrite(reinterpret_cast<const char *>(&audioFormat), 2, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&channels), 2, 1, file);
    const uint32_t sr = static_cast<uint32_t>(sampleRate);
    std::fwrite(reinterpret_cast<const char *>(&sr), 4, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&byteRate), 4, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&blockAlign), 2, 1, file);
    std::fwrite(reinterpret_cast<const char *>(&bitsPerSample), 2, 1, file);
    std::fwrite("data", 1, 4, file);
    std::fwrite(reinterpret_cast<const char *>(&dataSize), 4, 1, file);

    for (const auto &segment : segments) {
        std::vector<int16_t> samples(static_cast<std::size_t>(segment.first), segment.second);
        std::fwrite(samples.data(), sizeof(int16_t), samples.size(), file);
    }
    std::fclose(file);
    return path;
}

TEST_F(AudioEngineMasterPlaybackTest, LoopLaneWrapsAndRemainsActivePastLoopEnd) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kLoopEndMs = 1'000L;

    const std::string wav =
        WriteTempMonoWavFilled("loop_wrap.wav", kSampleRateHz, kSampleRateHz * 3, 15'000);
    ASSERT_FALSE(wav.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{wav},
        std::vector<float>{1.0f},
        0L,
        0L,
        std::vector<int64_t>{0L},
        std::vector<int64_t>{kLoopEndMs},
        std::vector<uint8_t>{1},
        std::vector<int64_t>{0L},
        std::vector<int64_t>{kLoopEndMs}));

    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(0));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    int64_t prevTransportMs = -1;
    for (int block = 0; block < 600; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        const int64_t transportMs = engine.transportPositionMs();
        ASSERT_GT(transportMs, prevTransportMs);
        prevTransportMs = transportMs;
    }

    EXPECT_TRUE(engine.isPlaybackActive());
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(0));
    EXPECT_GE(engine.transportPositionMs(), 2'500);
    engine.stopPlayback();
    std::remove(wav.c_str());
}

TEST_F(AudioEngineMasterPlaybackTest, LoopLaneStartsFromLoopSourceStart) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kLoopStartMs = 1'000L;
    constexpr int64_t kLoopEndMs = 2'000L;
    constexpr int64_t kClipStartMs = kLoopStartMs;

    const std::string wav = WriteTempMonoWavSegmented(
        "loop_start_offset.wav",
        kSampleRateHz,
        {
            {kSampleRateHz, 0},
            {kSampleRateHz, 20'000},
            {kSampleRateHz, 0},
        });
    ASSERT_FALSE(wav.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{wav},
        std::vector<float>{1.0f},
        0L,
        0L,
        std::vector<int64_t>{kClipStartMs},
        std::vector<int64_t>{kLoopEndMs - kLoopStartMs},
        std::vector<uint8_t>{1},
        std::vector<int64_t>{kLoopStartMs},
        std::vector<int64_t>{kLoopEndMs}));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    float peakBeforeClip = 0.0f;
    const int blocksBeforeClip =
        static_cast<int>(kClipStartMs * kSampleRateHz / 1000 / kRenderFrames);
    for (int block = 0; block < blocksBeforeClip + 2; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        if (engine.transportPositionMs() < kClipStartMs) {
            peakBeforeClip =
                std::max(peakBeforeClip, PeakAbsInterleaved(buffer.data(), buffer.size()));
        }
    }
    EXPECT_LT(peakBeforeClip, 0.01f);

    float peakAfterClip = 0.0f;
    for (int block = 0; block < 64; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        if (engine.transportPositionMs() >= kClipStartMs) {
            peakAfterClip =
                std::max(peakAfterClip, PeakAbsInterleaved(buffer.data(), buffer.size()));
        }
    }
    EXPECT_GT(peakAfterClip, 0.05f);
    engine.stopPlayback();
    std::remove(wav.c_str());
}

TEST_F(AudioEngineMasterPlaybackTest, LoopLaneNeverPlaysBeyondLoopEndSourceRegion) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kLoopEndMs = 1'000L;

    const std::string wav = WriteTempMonoWavSegmented(
        "loop_end_cap.wav",
        kSampleRateHz,
        {
            {kSampleRateHz, 8'000},
            {kSampleRateHz * 4, 24'000},
        });
    ASSERT_FALSE(wav.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{wav},
        std::vector<float>{1.0f},
        0L,
        0L,
        std::vector<int64_t>{0L},
        std::vector<int64_t>{kLoopEndMs},
        std::vector<uint8_t>{1},
        std::vector<int64_t>{0L},
        std::vector<int64_t>{kLoopEndMs}));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    float peak = 0.0f;
    for (int block = 0; block < 800; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        peak = std::max(peak, PeakAbsInterleaved(buffer.data(), buffer.size()));
    }

    EXPECT_LT(peak, 0.35f);
    EXPECT_TRUE(engine.isPlaybackActive());
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(0));
    engine.stopPlayback();
    std::remove(wav.c_str());
}

TEST_F(AudioEngineMasterPlaybackTest, MixedOneShotAndLoopLaneKeepsPlaybackAlive) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kOneShotDurationMs = 1'000L;
    constexpr int64_t kLoopEndMs = 2'000L;

    const std::string oneShotWav = WriteTempMonoWavFilled(
        "mixed_one_shot.wav", kSampleRateHz, kSampleRateHz, 12'000);
    const std::string loopWav = WriteTempMonoWavFilled(
        "mixed_loop.wav", kSampleRateHz, kSampleRateHz * 5, 18'000);
    ASSERT_FALSE(oneShotWav.empty());
    ASSERT_FALSE(loopWav.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{oneShotWav, loopWav},
        std::vector<float>{1.0f, 1.0f},
        0L,
        0L,
        std::vector<int64_t>{0L, 0L},
        std::vector<int64_t>{kOneShotDurationMs, kLoopEndMs},
        std::vector<uint8_t>{0, 1},
        std::vector<int64_t>{0L, 0L},
        std::vector<int64_t>{0L, kLoopEndMs}));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    int64_t prevTransportMs = -1;
    for (int block = 0; block < 700; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        const int64_t transportMs = engine.transportPositionMs();
        ASSERT_GT(transportMs, prevTransportMs);
        prevTransportMs = transportMs;
    }

    EXPECT_TRUE(engine.isPlaybackActive());
    EXPECT_EQ(PlaybackLaneLifecycle::Exhausted, engine.laneLifecycle(0));
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(1));
    EXPECT_GE(engine.transportPositionMs(), 3'000);
    engine.stopPlayback();
    std::remove(oneShotWav.c_str());
    std::remove(loopWav.c_str());
}

TEST_F(AudioEngineMasterPlaybackTest, TwoLoopLanesSameLengthRemainActiveAndAudible) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kLoopEndMs = 1'500L;

    const std::string loopA = WriteTempMonoWavFilled(
        "two_loop_a.wav", kSampleRateHz, kSampleRateHz * 2, 12'000);
    const std::string loopB = WriteTempMonoWavFilled(
        "two_loop_b.wav", kSampleRateHz, kSampleRateHz * 2, 18'000);
    ASSERT_FALSE(loopA.empty());
    ASSERT_FALSE(loopB.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{loopA, loopB},
        std::vector<float>{1.0f, 1.0f},
        0L,
        0L,
        std::vector<int64_t>{0L, 0L},
        std::vector<int64_t>{kLoopEndMs, kLoopEndMs},
        std::vector<uint8_t>{1, 1},
        std::vector<int64_t>{0L, 0L},
        std::vector<int64_t>{kLoopEndMs, kLoopEndMs}));

    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(0));
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(1));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    float peak = 0.0f;
    int64_t prevTransportMs = -1;
    for (int block = 0; block < 900; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        peak = std::max(peak, PeakAbsInterleaved(buffer.data(), buffer.size()));
        const int64_t transportMs = engine.transportPositionMs();
        ASSERT_GT(transportMs, prevTransportMs);
        prevTransportMs = transportMs;
    }

    EXPECT_GT(peak, 0.05f);
    EXPECT_TRUE(engine.isPlaybackActive());
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(0));
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(1));
    EXPECT_GE(engine.transportPositionMs(), 3'500);
    engine.stopPlayback();
    std::remove(loopA.c_str());
    std::remove(loopB.c_str());
}

TEST_F(AudioEngineMasterPlaybackTest, TwoLoopLanesDifferentLengthsWrapIndependently) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kLoopEndShortMs = 1'000L;
    constexpr int64_t kLoopEndLongMs = 2'500L;

    const std::string shortLoop = WriteTempMonoWavFilled(
        "short_loop.wav", kSampleRateHz, kSampleRateHz * 4, 14'000);
    const std::string longLoop = WriteTempMonoWavFilled(
        "long_loop.wav", kSampleRateHz, kSampleRateHz * 6, 20'000);
    ASSERT_FALSE(shortLoop.empty());
    ASSERT_FALSE(longLoop.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{shortLoop, longLoop},
        std::vector<float>{1.0f, 1.0f},
        0L,
        0L,
        std::vector<int64_t>{0L, 0L},
        std::vector<int64_t>{kLoopEndShortMs, kLoopEndLongMs},
        std::vector<uint8_t>{1, 1},
        std::vector<int64_t>{0L, 0L},
        std::vector<int64_t>{kLoopEndShortMs, kLoopEndLongMs}));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    for (int block = 0; block < 1'000; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
    }

    EXPECT_TRUE(engine.isPlaybackActive());
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(0));
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(1));
    EXPECT_GE(engine.transportPositionMs(), 4'000);
    engine.stopPlayback();
    std::remove(shortLoop.c_str());
    std::remove(longLoop.c_str());
}

TEST_F(AudioEngineMasterPlaybackTest, MixedOneShotAndTwoLoopLanesKeepsLoopLanesActive) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kOneShotDurationMs = 800L;
    constexpr int64_t kLoopShortEndMs = 1'200L;
    constexpr int64_t kLoopLongEndMs = 2'400L;

    const std::string oneShotWav = WriteTempMonoWavFilled(
        "mixed3_one_shot.wav", kSampleRateHz, kSampleRateHz, 12'000);
    const std::string loopShortWav = WriteTempMonoWavFilled(
        "mixed3_loop_short.wav", kSampleRateHz, kSampleRateHz * 4, 16'000);
    const std::string loopLongWav = WriteTempMonoWavFilled(
        "mixed3_loop_long.wav", kSampleRateHz, kSampleRateHz * 6, 22'000);
    ASSERT_FALSE(oneShotWav.empty());
    ASSERT_FALSE(loopShortWav.empty());
    ASSERT_FALSE(loopLongWav.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{oneShotWav, loopShortWav, loopLongWav},
        std::vector<float>{1.0f, 1.0f, 1.0f},
        0L,
        0L,
        std::vector<int64_t>{0L, 0L, 0L},
        std::vector<int64_t>{kOneShotDurationMs, kLoopShortEndMs, kLoopLongEndMs},
        std::vector<uint8_t>{0, 1, 1},
        std::vector<int64_t>{0L, 0L, 0L},
        std::vector<int64_t>{0L, kLoopShortEndMs, kLoopLongEndMs}));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    int64_t prevTransportMs = -1;
    for (int block = 0; block < 900; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        const int64_t transportMs = engine.transportPositionMs();
        ASSERT_GT(transportMs, prevTransportMs);
        prevTransportMs = transportMs;
    }

    EXPECT_TRUE(engine.isPlaybackActive());
    EXPECT_EQ(PlaybackLaneLifecycle::Exhausted, engine.laneLifecycle(0));
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(1));
    EXPECT_EQ(PlaybackLaneLifecycle::Active, engine.laneLifecycle(2));
    EXPECT_GE(engine.transportPositionMs(), 4'000);
    engine.stopPlayback();
    std::remove(oneShotWav.c_str());
    std::remove(loopShortWav.c_str());
    std::remove(loopLongWav.c_str());
}

TEST_F(AudioEngineMasterPlaybackTest, TransportFrameMonotonicPastBaseTimelineEnd) {
    constexpr int32_t kSampleRateHz = 48'000;
    constexpr int32_t kRenderFrames = 256;
    constexpr int64_t kBaseTimelineEndMs = 2'000L;
    constexpr int64_t kLoopEndMs = 1'000L;

    const std::string loopWav = WriteTempMonoWavFilled(
        "monotonic_loop.wav", kSampleRateHz, kSampleRateHz * 4, 15'000);
    ASSERT_FALSE(loopWav.empty());

    ASSERT_TRUE(engine.setPlaybackSources(
        std::vector<std::string>{loopWav},
        std::vector<float>{1.0f},
        0L,
        0L,
        std::vector<int64_t>{0L},
        std::vector<int64_t>{kBaseTimelineEndMs},
        std::vector<uint8_t>{1},
        std::vector<int64_t>{0L},
        std::vector<int64_t>{kLoopEndMs}));

    std::vector<float> buffer(static_cast<std::size_t>(kRenderFrames) * 2u, 0.0f);
    int64_t prevTransportFrame = -1;
    for (int block = 0; block < 600; ++block) {
        engine.render(buffer.data(), kRenderFrames, 2, kSampleRateHz);
        const int64_t transportFrame = engine.transportFrame();
        ASSERT_GT(transportFrame, prevTransportFrame);
        prevTransportFrame = transportFrame;
    }

    EXPECT_TRUE(engine.isPlaybackActive());
    EXPECT_GT(engine.transportPositionMs(), kBaseTimelineEndMs);
    engine.stopPlayback();
    std::remove(loopWav.c_str());
}

} // namespace
} // namespace dawengine
