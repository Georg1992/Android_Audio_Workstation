#include <gtest/gtest.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <string>
#include <vector>

#include "engine/StreamingPcm16WavWriter.h"

namespace {

std::string TempWavPath() {
    const auto path =
        std::filesystem::temp_directory_path() / "daw_streaming_pcm16_take.wav";
    std::filesystem::remove(path);
    return path.string();
}

uint32_t ReadLe32(std::FILE *file) {
    uint8_t bytes[4] = {};
    EXPECT_EQ(4u, std::fread(bytes, 1, 4, file));
    return static_cast<uint32_t>(bytes[0]) |
           (static_cast<uint32_t>(bytes[1]) << 8u) |
           (static_cast<uint32_t>(bytes[2]) << 16u) |
           (static_cast<uint32_t>(bytes[3]) << 24u);
}

} // namespace

TEST(StreamingPcm16WavWriter, CommittedChunksStayInTheFileAfterClose) {
    const std::string path = TempWavPath();
    dawengine::StreamingPcm16WavWriter writer;
    ASSERT_TRUE(writer.Open(path, 48'000, 1, dawengine::StreamingPcm16WavWriter::kBitsPerSample));
    ASSERT_TRUE(writer.CommitDurableHeader());

    const float first[] = {0.5f, -0.5f};
    ASSERT_TRUE(writer.WriteFloatInterleaved(first, 2));
    ASSERT_TRUE(writer.CommitDurableHeader());
    EXPECT_EQ(4u, writer.dataBytesWritten());

    const float second[] = {0.25f};
    ASSERT_TRUE(writer.WriteFloatInterleaved(second, 1));
    ASSERT_TRUE(writer.CommitDurableHeader());
    EXPECT_EQ(6u, writer.dataBytesWritten());
    ASSERT_TRUE(writer.Close());

    std::FILE *file = std::fopen(path.c_str(), "rb");
    ASSERT_NE(nullptr, file);
    char riff[4] = {};
    ASSERT_EQ(4u, std::fread(riff, 1, 4, file));
    EXPECT_EQ(0, std::memcmp(riff, "RIFF", 4));
    const uint32_t riffSize = ReadLe32(file);
    EXPECT_EQ(42u, riffSize);
    ASSERT_EQ(0, std::fseek(file, 40, SEEK_SET));
    const uint32_t dataSize = ReadLe32(file);
    EXPECT_EQ(6u, dataSize);
    std::vector<int16_t> pcm(3);
    ASSERT_EQ(3u, std::fread(pcm.data(), sizeof(int16_t), pcm.size(), file));
    EXPECT_GT(pcm[0], 0);
    EXPECT_LT(pcm[1], 0);
    EXPECT_GT(pcm[2], 0);
    std::fclose(file);
    std::filesystem::remove(path);
}

TEST(StreamingPcm16WavWriter, EmptyPathDoesNotOpen) {
    dawengine::StreamingPcm16WavWriter writer;
    EXPECT_FALSE(writer.Open("", 48'000, 1, dawengine::StreamingPcm16WavWriter::kBitsPerSample));
}

TEST(StreamingPcm16WavWriter, NonPcm16BitDepthDoesNotOpen) {
    dawengine::StreamingPcm16WavWriter writer;
    EXPECT_FALSE(writer.Open("take.wav", 48'000, 1, 24));
}
