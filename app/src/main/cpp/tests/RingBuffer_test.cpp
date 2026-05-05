#include <gtest/gtest.h>

#include <array>
#include <cstddef>
#include <vector>

#include "engine/RingBuffer.h"

namespace dawengine {
namespace {

TEST(RingBufferTest, EmptyReadReturnsZero) {
    RingBuffer ring(16);
    std::array<float, 8> dst{};
    EXPECT_EQ(ring.read(dst.data(), dst.size()), 0u);
    EXPECT_EQ(ring.readable(), 0u);
}

TEST(RingBufferTest, PartialWriteThenPartialRead) {
    RingBuffer ring(8);
    const float in[] = {1.f, 2.f, 3.f, 4.f, 5.f};
    EXPECT_EQ(ring.write(in, 5), 5u);
    EXPECT_EQ(ring.readable(), 5u);
    EXPECT_EQ(ring.writable(), 3u);

    std::array<float, 8> dst{};
    EXPECT_EQ(ring.read(dst.data(), 2), 2u);
    EXPECT_FLOAT_EQ(dst[0], 1.f);
    EXPECT_FLOAT_EQ(dst[1], 2.f);
    EXPECT_EQ(ring.readable(), 3u);

    EXPECT_EQ(ring.read(dst.data(), 3), 3u);
    EXPECT_FLOAT_EQ(dst[0], 3.f);
    EXPECT_FLOAT_EQ(dst[1], 4.f);
    EXPECT_FLOAT_EQ(dst[2], 5.f);
    EXPECT_EQ(ring.readable(), 0u);
}

TEST(RingBufferTest, WriteDoesNotExceedCapacityOverrunProtection) {
    RingBuffer ring(6);
    std::vector<float> fill(10, 7.f);
    EXPECT_EQ(ring.write(fill.data(), fill.size()), 6u);
    EXPECT_EQ(ring.writable(), 0u);
    EXPECT_EQ(ring.write(fill.data(), fill.size()), 0u);

    std::array<float, 8> dst{};
    EXPECT_EQ(ring.read(dst.data(), 8), 6u);
    for (std::size_t i = 0; i < 6; ++i) {
        EXPECT_FLOAT_EQ(dst[i], 7.f) << "i=" << i;
    }
}

TEST(RingBufferTest, ReadDoesNotOverrunEmptyOrShortReadableOverrunProtection) {
    RingBuffer ring(4);
    std::array<float, 8> dst{99.f, 99.f, 99.f, 99.f, 99.f, 99.f, 99.f, 99.f};
    EXPECT_EQ(ring.read(dst.data(), 100), 0u);

    const float in[] = {1.f, 2.f};
    ASSERT_EQ(ring.write(in, 2), 2u);
    EXPECT_EQ(ring.read(dst.data(), 100), 2u);
    EXPECT_FLOAT_EQ(dst[0], 1.f);
    EXPECT_FLOAT_EQ(dst[1], 2.f);
}

TEST(RingBufferTest, WrapAroundWriteAndRead) {
    RingBuffer ring(8);
    const float first[] = {1.f, 2.f, 3.f, 4.f, 5.f, 6.f};
    ASSERT_EQ(ring.write(first, 6), 6u);

    std::array<float, 4> chunk{};
    ASSERT_EQ(ring.read(chunk.data(), 4), 4u);
    EXPECT_FLOAT_EQ(chunk[0], 1.f);
    EXPECT_FLOAT_EQ(chunk[3], 4.f);
    EXPECT_EQ(ring.readable(), 2u);

    const float second[] = {10.f, 20.f, 30.f, 40.f, 50.f, 60.f};
    ASSERT_EQ(ring.write(second, 6), 6u);
    EXPECT_EQ(ring.readable(), 8u);
    EXPECT_EQ(ring.writable(), 0u);

    std::array<float, 8> out{};
    ASSERT_EQ(ring.read(out.data(), 8), 8u);
    const float expected[] = {5.f, 6.f, 10.f, 20.f, 30.f, 40.f, 50.f, 60.f};
    for (std::size_t i = 0; i < 8; ++i) {
        EXPECT_FLOAT_EQ(out[i], expected[i]) << "i=" << i;
    }
    EXPECT_EQ(ring.readable(), 0u);
}

} // namespace
} // namespace dawengine
