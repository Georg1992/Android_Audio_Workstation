#include <gtest/gtest.h>

#include <chrono>
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
    EXPECT_EQ(0, engine.masterPlaybackStartFrame());
    EXPECT_EQ(0, engine.masterPlaybackFrame());
    EXPECT_EQ(0, engine.masterPlaybackPositionMs());
    engine.stopPlayback();

    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 30'000L));
    const int64_t expectedStart = (48'000LL * 30'000LL) / 1000LL;
    EXPECT_EQ(expectedStart, engine.masterPlaybackStartFrame());
    EXPECT_EQ(expectedStart, engine.masterPlaybackFrame());
    EXPECT_EQ(30'000, engine.masterPlaybackPositionMs());
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, RenderAdvancesByOutputCallbackFrames) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    const int64_t start = engine.masterPlaybackFrame();

    std::vector<float> buffer(512 * 2, 0.0f);
    engine.render(buffer.data(), 512, 2, 48'000);
    engine.render(buffer.data(), 256, 2, 48'000);

    EXPECT_EQ(start + 512 + 256, engine.masterPlaybackFrame());
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, StopsAdvancingWhenPlaybackInactive) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    std::vector<float> buffer(128 * 2, 0.0f);
    engine.render(buffer.data(), 128, 2, 48'000);
    ASSERT_GT(engine.masterPlaybackFrame(), 0);

    engine.stopPlayback();
    EXPECT_EQ(0, engine.masterPlaybackFrame());
    EXPECT_EQ(0, engine.masterPlaybackStartFrame());

    engine.render(buffer.data(), 128, 2, 48'000);
    EXPECT_EQ(0, engine.masterPlaybackFrame());
}

TEST_F(AudioEngineMasterPlaybackTest, ResetsOnStopAndRelease) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 10'000L));
    ASSERT_GT(engine.masterPlaybackFrame(), 0);
    engine.stopPlayback();
    EXPECT_EQ(0, engine.masterPlaybackFrame());
    EXPECT_EQ(0, engine.masterPlaybackStartFrame());

    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 5'000L));
    engine.releasePlaybackResources();
    EXPECT_EQ(0, engine.masterPlaybackFrame());
    EXPECT_EQ(0, engine.masterPlaybackStartFrame());
}

TEST_F(AudioEngineMasterPlaybackTest, TimelinePositionAfterRenderedSecond) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 30'000L));
    const int64_t startFrame = engine.masterPlaybackFrame();
    ASSERT_EQ(30'000, engine.masterPlaybackPositionMs());

    std::vector<float> buffer(48'000 * 2, 0.0f);
    engine.render(buffer.data(), 48'000, 2, 48'000);

    EXPECT_EQ(startFrame + 48'000, engine.masterPlaybackFrame());
    EXPECT_EQ(31'000, engine.masterPlaybackPositionMs());
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, HotJoinCommitsAtCurrentMasterFrame) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    std::vector<float> buffer(4'800 * 2, 0.0f);
    engine.render(buffer.data(), 4'800, 2, 48'000);
    const int64_t masterBeforeJoin = engine.masterPlaybackFrame();
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
    EXPECT_GE(engine.masterPlaybackFrame(), masterBeforeJoin);
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, CancelPreventsAudibleCommit) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));
    const std::string secondWav = WriteTempMonoWav("master_playback_cancel_audible.wav", 48'000, 48'000);
    ASSERT_FALSE(secondWav.empty());
    const int32_t laneIndex = engine.beginHotJoinLane(secondWav, 1.0f);
    ASSERT_EQ(1, laneIndex);

    for (int attempt = 0; attempt < 500; ++attempt) {
        if (engine.laneLifecycle(static_cast<std::size_t>(laneIndex)) ==
            PlaybackLaneLifecycle::ReadyToCommit) {
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    ASSERT_EQ(PlaybackLaneLifecycle::ReadyToCommit,
              engine.laneLifecycle(static_cast<std::size_t>(laneIndex)));

    engine.cancelHotJoinLane(static_cast<std::size_t>(laneIndex));
    EXPECT_EQ(PlaybackLaneLifecycle::Inactive,
              engine.laneLifecycle(static_cast<std::size_t>(laneIndex)));

    std::vector<float> buffer(256 * 2, 0.0f);
    for (int attempt = 0; attempt < 100; ++attempt) {
        engine.render(buffer.data(), 256, 2, 48'000);
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }
    EXPECT_NE(PlaybackLaneLifecycle::Active,
              engine.laneLifecycle(static_cast<std::size_t>(laneIndex)));
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

    const int64_t frozen = engine.masterPlaybackFrame();
    ASSERT_GT(frozen, 0);
    engine.render(buffer.data(), 256, 2, 48'000);
    EXPECT_EQ(frozen, engine.masterPlaybackFrame());

    engine.stopPlayback();
    EXPECT_EQ(0, engine.masterPlaybackFrame());
}

TEST_F(AudioEngineMasterPlaybackTest, TransportAdvancesThroughSessionEndWhenLaneExhaustedAtStart) {
    const std::string shortWav = WriteTempMonoWav("master_playback_session_end.wav", 48'000, 256);
    ASSERT_FALSE(shortWav.empty());
    constexpr int64_t kStartMs = 5'000L;
    constexpr int64_t kSessionEndMs = 30'000L;
    ASSERT_TRUE(engine.setPlaybackSource(shortWav, 1.0f, kStartMs, kSessionEndMs));

    const int64_t startFrame = engine.transportFrame();
    ASSERT_GT(startFrame, 0);

    std::vector<float> buffer(256 * 2, 0.0f);
    for (int attempt = 0; attempt < 400 && engine.isPlaybackActive(); ++attempt) {
        engine.render(buffer.data(), 256, 2, 48'000);
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }

    EXPECT_FALSE(engine.isPlaybackActive());
    EXPECT_GE(engine.transportPositionMs(), kSessionEndMs - 50);
    engine.stopPlayback();
}

TEST_F(AudioEngineMasterPlaybackTest, TransportAliasesMatchMasterPlaybackNames) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 30'000L));
    EXPECT_EQ(engine.transportStartFrame(), engine.masterPlaybackStartFrame());
    EXPECT_EQ(engine.transportFrame(), engine.masterPlaybackFrame());
    EXPECT_EQ(engine.transportPositionMs(), engine.masterPlaybackPositionMs());

    std::vector<float> buffer(512 * 2, 0.0f);
    engine.render(buffer.data(), 512, 2, 48'000);
    EXPECT_EQ(engine.transportFrame(), engine.masterPlaybackFrame());
    EXPECT_EQ(engine.transportPositionMs(), engine.masterPlaybackPositionMs());

    engine.stopPlayback();
}

} // namespace
} // namespace dawengine
