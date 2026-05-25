#include <gtest/gtest.h>

#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

#include "engine/LocalWavSource.h"

namespace dawengine {
namespace {

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

TEST(LocalWavSourceTest, DetectsSamePathFileReplacement) {
    const std::string path = "local_wav_source_replace.wav";
    WriteTempMonoWavFilled(path, 48'000, 48'000, 0);

    LocalWavSource source(path);
    ASSERT_TRUE(source.open());
    EXPECT_FALSE(source.hasDiskContentChanged());

    WriteTempMonoWavFilled(path, 48'000, 48'000, 20'000);
    EXPECT_TRUE(source.hasDiskContentChanged());

    std::remove(path.c_str());
}

} // namespace
} // namespace dawengine
