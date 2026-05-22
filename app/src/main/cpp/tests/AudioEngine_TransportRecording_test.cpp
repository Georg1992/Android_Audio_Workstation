#include <gtest/gtest.h>

#include <chrono>
#include <cstdint>
#include <cstdio>
#include <string>
#include <thread>
#include <vector>

#include "engine/AudioEngine.h"

namespace dawengine {
namespace {

std::string UniqueTempWavPath(const char *prefix) {
    return std::string(prefix) + std::to_string(
               std::chrono::steady_clock::now().time_since_epoch().count()) +
           ".wav";
}

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

    auto writeU32 = [&](uint32_t v) {
        const uint8_t b[4] = {
            static_cast<uint8_t>(v & 0xFFu),
            static_cast<uint8_t>((v >> 8u) & 0xFFu),
            static_cast<uint8_t>((v >> 16u) & 0xFFu),
            static_cast<uint8_t>((v >> 24u) & 0xFFu),
        };
        std::fwrite(b, 1, 4, file);
    };
    auto writeU16 = [&](uint16_t v) {
        const uint8_t b[2] = {
            static_cast<uint8_t>(v & 0xFFu),
            static_cast<uint8_t>((v >> 8u) & 0xFFu),
        };
        std::fwrite(b, 1, 2, file);
    };

    std::fwrite("RIFF", 1, 4, file);
    writeU32(riffSize);
    std::fwrite("WAVE", 1, 4, file);
    std::fwrite("fmt ", 1, 4, file);
    writeU32(16u);
    writeU16(1u);
    writeU16(channels);
    writeU32(static_cast<uint32_t>(sampleRate));
    writeU32(byteRate);
    writeU16(blockAlign);
    writeU16(bitsPerSample);
    std::fwrite("data", 1, 4, file);
    writeU32(dataSize);
    std::vector<int16_t> silence(static_cast<std::size_t>(frameCount), 0);
    std::fwrite(silence.data(), sizeof(int16_t), silence.size(), file);
    std::fclose(file);
    return path;
}

class AudioEngineTransportRecordingTest : public ::testing::Test {
protected:
    void SetUp() override {
        engine.configureProject(48'000, 16);
        wavPath = WriteTempMonoWav(UniqueTempWavPath("transport_rec_playback_"), 48'000, 48'000 * 4);
        ASSERT_FALSE(wavPath.empty());
        recordPath = UniqueTempWavPath("transport_rec_capture_");
    }

    void TearDown() override {
        engine.stopRecording();
        engine.stopPlayback();
        std::remove(wavPath.c_str());
        std::remove(recordPath.c_str());
    }

    AudioEngine engine;
    std::string wavPath;
    std::string recordPath;
};

TEST_F(AudioEngineTransportRecordingTest, RecordingOnlyInitializesTransportFromStartOffsetMs) {
    const int64_t expectedStart = (48'000LL * 30'000LL) / 1000LL;
    if (!engine.startRecording(1, recordPath, 30'000L)) {
        GTEST_SKIP() << "No input audio device available for recording transport test.";
    }

    EXPECT_EQ(expectedStart, engine.transportStartFrame());
    EXPECT_EQ(expectedStart, engine.transportFrame());
    EXPECT_EQ(30'000, engine.transportPositionMs());

    engine.stopRecording();
}

TEST_F(AudioEngineTransportRecordingTest, StopPlaybackPreservesTransportWhileRecording) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 30'000L));

    std::vector<float> buffer(4'800 * 2, 0.0f);
    engine.render(buffer.data(), 4'800, 2, 48'000);
    const int64_t transportAfterRender = engine.transportFrame();
    ASSERT_GT(transportAfterRender, (48'000LL * 30'000LL) / 1000LL);

    if (!engine.startRecording(1, recordPath, 0L)) {
        engine.stopPlayback();
        GTEST_SKIP() << "No input audio device available for play+record handoff test.";
    }

    engine.stopPlayback();

    EXPECT_EQ(transportAfterRender, engine.transportFrame());
    EXPECT_EQ(engine.transportPositionMs(),
              (transportAfterRender * 1000LL) / 48'000LL);

    engine.stopRecording();
}

TEST_F(AudioEngineTransportRecordingTest, PlayAndRecordDoesNotResetTransportOnRecordingStart) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 30'000L));
    const int64_t transportAtPlaybackStart = engine.transportFrame();
    ASSERT_EQ((48'000LL * 30'000LL) / 1000LL, transportAtPlaybackStart);

    if (!engine.startRecording(1, recordPath, 0L)) {
        engine.stopPlayback();
        GTEST_SKIP() << "No input audio device available for play+record transport test.";
    }

    EXPECT_EQ(transportAtPlaybackStart, engine.transportFrame());

    engine.stopRecording();
    engine.stopPlayback();
}

TEST_F(AudioEngineTransportRecordingTest, StopPlaybackResetsTransportWhenNotRecording) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 30'000L));
    std::vector<float> buffer(256 * 2, 0.0f);
    engine.render(buffer.data(), 256, 2, 48'000);
    ASSERT_GT(engine.transportFrame(), 0);

    engine.stopPlayback();
    EXPECT_EQ(0, engine.transportFrame());
    EXPECT_EQ(0, engine.transportStartFrame());
}

TEST_F(AudioEngineTransportRecordingTest, RecordLoopDoesNotAdvanceTransportWhilePlaybackActive) {
    ASSERT_TRUE(engine.setPlaybackSource(wavPath, 1.0f, 0L));

    if (!engine.startRecording(1, recordPath, 0L)) {
        engine.stopPlayback();
        GTEST_SKIP() << "No input audio device available for dual-writer guard test.";
    }

    const int64_t transportBefore = engine.transportFrame();
    std::this_thread::sleep_for(std::chrono::milliseconds(200));
    EXPECT_EQ(transportBefore, engine.transportFrame());

    engine.stopRecording();
    engine.stopPlayback();
}

TEST_F(AudioEngineTransportRecordingTest, RecordingOnlyAdvancesTransportWithCapturedFrames) {
    if (!engine.startRecording(1, recordPath, 0L)) {
        GTEST_SKIP() << "No input audio device available for recording advance test.";
    }

    const int64_t startFrame = engine.transportFrame();
    std::this_thread::sleep_for(std::chrono::milliseconds(250));
    const int64_t afterCapture = engine.transportFrame();

    engine.stopRecording();

    EXPECT_GE(afterCapture, startFrame);
}

} // namespace
} // namespace dawengine
