#pragma once

#include "aios/vfs_node.h"

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <string>
#include <vector>

namespace aios {

class AudioNode : public VfsNode {
public:
    static constexpr size_t kDefaultRingCapacity = 1024 * 1024;
    static constexpr size_t kDefaultVisemeCapacity = 65536;

    enum class StreamType {
        PCM,
        VISEME
    };

    explicit AudioNode(const std::string& path, StreamType stream_type,
                       size_t ring_capacity = 0,
                       int owner_uid = 0, int permissions = 0666);

    ~AudioNode() override;

    bool write(const std::string& data) override;
    std::string read() const override;

    bool write_as(const std::string& data, int caller_uid);
    std::string read_as(int caller_uid);

    bool write_stream(const std::vector<uint8_t>& audio_chunk,
                      const std::string& viseme_json);

    std::vector<uint8_t> read_pcm_nonblocking(size_t max_bytes = 0);
    std::vector<uint8_t> read_pcm_blocking(size_t max_bytes = 0,
                                            int timeout_ms = -1);

    std::string read_viseme_nonblocking();
    std::string read_viseme_blocking(int timeout_ms = -1);

    size_t pcm_available() const;
    size_t viseme_available() const;

    void flush();
    void flush_pcm();
    void flush_visemes();

    StreamType stream_type() const { return stream_type_; }

    size_t total_pcm_written() const { return total_pcm_written_.load(); }
    size_t total_visemes_written() const { return total_visemes_written_.load(); }
    size_t total_pcm_read() const { return total_pcm_read_.load(); }
    size_t total_visemes_read() const { return total_visemes_read_.load(); }

private:
    struct RingBuffer {
        std::vector<uint8_t> buf;
        std::atomic<size_t> head{0};
        std::atomic<size_t> tail{0};
        size_t capacity;

        explicit RingBuffer(size_t cap);

        size_t write_available() const;
        size_t read_available() const;

        size_t write(const uint8_t* data, size_t len);
        size_t read(uint8_t* out, size_t max_len);

        void flush();
    };

    StreamType stream_type_;

    RingBuffer pcm_ring_;
    RingBuffer viseme_ring_;

    mutable std::mutex pcm_mutex_;
    mutable std::mutex viseme_mutex_;
    std::condition_variable pcm_cv_;
    std::condition_variable viseme_cv_;

    std::atomic<size_t> total_pcm_written_{0};
    std::atomic<size_t> total_visemes_written_{0};
    std::atomic<size_t> total_pcm_read_{0};
    std::atomic<size_t> total_visemes_read_{0};
    std::atomic<bool> running_{true};
};

} // namespace aios
