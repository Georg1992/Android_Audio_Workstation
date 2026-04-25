#pragma once

#include <atomic>
#include <cstddef>
#include <cstring>
#include <vector>

namespace dawengine {

/**
 * Single-producer / single-consumer lock-free ring buffer of floats.
 *
 * Producer thread is the I/O worker that pulls PCM frames from an [IAudioSource].
 * Consumer thread is the Oboe render callback. They never block each other; the
 * data path uses only acquire/release atomics on the head and tail indices.
 *
 * Reset() must only be called when both producer and consumer are stopped.
 *
 * Capacity is fixed at construction. Internally we allocate `capacity + 1`
 * slots so the "full" condition (head == tail after a write) is distinguishable
 * from "empty" (head == tail at rest).
 */
class RingBuffer {
public:
    explicit RingBuffer(std::size_t capacity)
        : m_buffer(capacity + 1), m_capacity(capacity + 1) {}

    /** Maximum payload size (one slot is reserved for the empty/full distinction). */
    std::size_t capacity() const { return m_capacity - 1; }

    /** Number of payload floats currently readable by the consumer. */
    std::size_t readable() const {
        const std::size_t head = m_head.load(std::memory_order_acquire);
        const std::size_t tail = m_tail.load(std::memory_order_acquire);
        return (head + m_capacity - tail) % m_capacity;
    }

    /** Number of payload floats the producer can still write without blocking. */
    std::size_t writable() const { return capacity() - readable(); }

    /**
     * Producer side. Copies up to `count` floats from `src` into the ring,
     * stops early if the ring fills up. Returns the number of floats written.
     */
    std::size_t write(const float *src, std::size_t count) {
        const std::size_t available = writable();
        const std::size_t toWrite = count < available ? count : available;
        if (toWrite == 0) return 0;

        const std::size_t head = m_head.load(std::memory_order_relaxed);
        const std::size_t firstChunk = std::min(toWrite, m_capacity - head);
        std::memcpy(m_buffer.data() + head, src, firstChunk * sizeof(float));
        if (toWrite > firstChunk) {
            std::memcpy(m_buffer.data(), src + firstChunk, (toWrite - firstChunk) * sizeof(float));
        }
        m_head.store((head + toWrite) % m_capacity, std::memory_order_release);
        return toWrite;
    }

    /**
     * Consumer side. Copies up to `count` floats from the ring into `dst`,
     * stops early if the ring drains. Returns the number of floats read.
     */
    std::size_t read(float *dst, std::size_t count) {
        const std::size_t available = readable();
        const std::size_t toRead = count < available ? count : available;
        if (toRead == 0) return 0;

        const std::size_t tail = m_tail.load(std::memory_order_relaxed);
        const std::size_t firstChunk = std::min(toRead, m_capacity - tail);
        std::memcpy(dst, m_buffer.data() + tail, firstChunk * sizeof(float));
        if (toRead > firstChunk) {
            std::memcpy(dst + firstChunk, m_buffer.data(), (toRead - firstChunk) * sizeof(float));
        }
        m_tail.store((tail + toRead) % m_capacity, std::memory_order_release);
        return toRead;
    }

    /**
     * Drops everything currently in the ring. Must only be called while both
     * producer and consumer are quiesced — used between play sessions to start
     * from a clean buffer.
     */
    void reset() {
        m_head.store(0, std::memory_order_release);
        m_tail.store(0, std::memory_order_release);
    }

private:
    std::vector<float> m_buffer;
    const std::size_t m_capacity;
    std::atomic<std::size_t> m_head{0}; // producer cursor
    std::atomic<std::size_t> m_tail{0}; // consumer cursor
};

} // namespace dawengine
