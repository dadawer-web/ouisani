#include "aios/audio_node.h"
#include "aios/event_bus.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>

namespace aios {

AudioNode::RingBuffer::RingBuffer(size_t cap)
    : buf(cap, 0), capacity(cap) {}

size_t AudioNode::RingBuffer::write_available() const {
    size_t h = head.load(std::memory_order_relaxed);
    size_t t = tail.load(std::memory_order_relaxed);
    if (h >= t) return capacity - (h - t) - 1;
    return t - h - 1;
}

size_t AudioNode::RingBuffer::read_available() const {
    size_t h = head.load(std::memory_order_relaxed);
    size_t t = tail.load(std::memory_order_relaxed);
    if (h >= t) return h - t;
    return capacity - t + h;
}

size_t AudioNode::RingBuffer::write(const uint8_t* data, size_t len) {
    size_t avail = write_available();
    size_t to_write = std::min(len, avail);
    for (size_t i = 0; i < to_write; ++i) {
        size_t h = head.load(std::memory_order_relaxed);
        buf[h] = data[i];
        head.store((h + 1) % capacity, std::memory_order_release);
    }
    return to_write;
}

size_t AudioNode::RingBuffer::read(uint8_t* out, size_t max_len) {
    size_t avail = read_available();
    size_t to_read = std::min(max_len, avail);
    for (size_t i = 0; i < to_read; ++i) {
        size_t t = tail.load(std::memory_order_relaxed);
        out[i] = buf[t];
        tail.store((t + 1) % capacity, std::memory_order_release);
    }
    return to_read;
}

void AudioNode::RingBuffer::flush() {
    tail.store(head.load(std::memory_order_relaxed), std::memory_order_release);
}

AudioNode::AudioNode(const std::string& path, StreamType stream_type,
                     size_t ring_capacity,
                     int owner_uid, int permissions)
    : VfsNode(VfsNodeType::AUDIO, path, owner_uid, permissions)
    , stream_type_(stream_type)
    , pcm_ring_(ring_capacity > 0 ? ring_capacity : kDefaultRingCapacity)
    , viseme_ring_(ring_capacity > 0 ? (ring_capacity / 4) : kDefaultVisemeCapacity)
{
    std::printf("[AudioNode] Created: %s | type=%s | pcm_cap=%zu | viseme_cap=%zu\n",
                path.c_str(),
                stream_type == StreamType::PCM ? "PCM" : "VISEME",
                pcm_ring_.capacity,
                viseme_ring_.capacity);
}

AudioNode::~AudioNode() {
    running_.store(false);
    pcm_cv_.notify_all();
    viseme_cv_.notify_all();
}

bool AudioNode::write(const std::string& data) {
    return write_as(data, 0);
}

bool AudioNode::write_as(const std::string& data, int caller_uid) {
    if (!check_write(caller_uid)) {
        std::printf("[AudioNode] WRITE DENIED %s | uid=%d no write permission\n",
                    path_.c_str(), caller_uid);
        return false;
    }

    if (stream_type_ == StreamType::PCM) {
        std::lock_guard<std::mutex> lock(pcm_mutex_);
        auto written = pcm_ring_.write(
            reinterpret_cast<const uint8_t*>(data.data()), data.size());
        total_pcm_written_.fetch_add(written, std::memory_order_relaxed);
        std::printf("[AudioNode] PCM WRITE %s | %zu/%zu bytes | ring_avail=%zu\n",
                    path_.c_str(), written, data.size(), pcm_ring_.read_available());
        pcm_cv_.notify_one();
        return written > 0;
    } else {
        std::lock_guard<std::mutex> lock(viseme_mutex_);
        std::string frame = data;
        if (!frame.empty() && frame.back() != '\n') {
            frame += '\n';
        }
        auto written = viseme_ring_.write(
            reinterpret_cast<const uint8_t*>(frame.data()), frame.size());
        total_visemes_written_.fetch_add(written, std::memory_order_relaxed);
        std::printf("[AudioNode] VISEME WRITE %s | %zu/%zu bytes | ring_avail=%zu\n",
                    path_.c_str(), written, frame.size(), viseme_ring_.read_available());
        viseme_cv_.notify_one();
        return written > 0;
    }
}

std::string AudioNode::read() const {
    if (stream_type_ == StreamType::PCM) {
        auto chunk = const_cast<AudioNode*>(this)->read_pcm_nonblocking();
        if (chunk.empty()) return "";
        return std::string(chunk.begin(), chunk.end());
    } else {
        return const_cast<AudioNode*>(this)->read_viseme_nonblocking();
    }
}

std::string AudioNode::read_as(int caller_uid) {
    if (!check_read(caller_uid)) {
        return "";
    }

    if (stream_type_ == StreamType::PCM) {
        auto chunk = const_cast<AudioNode*>(this)->read_pcm_nonblocking();
        if (chunk.empty()) return "";
        return std::string(chunk.begin(), chunk.end());
    } else {
        return const_cast<AudioNode*>(this)->read_viseme_nonblocking();
    }
}

bool AudioNode::write_stream(const std::vector<uint8_t>& audio_chunk,
                              const std::string& viseme_json) {
    bool ok = true;

    if (!audio_chunk.empty()) {
        std::lock_guard<std::mutex> lock(pcm_mutex_);
        auto written = pcm_ring_.write(audio_chunk.data(), audio_chunk.size());
        total_pcm_written_.fetch_add(written, std::memory_order_relaxed);
        if (written == 0) ok = false;
        pcm_cv_.notify_one();
        std::printf("[AudioNode] write_stream PCM | %zu/%zu bytes written\n",
                    written, audio_chunk.size());
    }

    if (!viseme_json.empty()) {
        std::lock_guard<std::mutex> lock(viseme_mutex_);
        std::string frame = viseme_json;
        if (!frame.empty() && frame.back() != '\n') {
            frame += '\n';
        }
        auto written = viseme_ring_.write(
            reinterpret_cast<const uint8_t*>(frame.data()), frame.size());
        total_visemes_written_.fetch_add(written, std::memory_order_relaxed);
        if (written == 0) ok = false;
        viseme_cv_.notify_one();
        std::printf("[AudioNode] write_stream VISEME | %zu/%zu bytes written\n",
                    written, frame.size());
    }

    if (ok) {
        EventBus::instance().publish(EventType::VFS_WRITE, "AudioNode",
            path_ + " | pcm=" + std::to_string(total_pcm_written_.load()) +
            " visemes=" + std::to_string(total_visemes_written_.load()));
    }

    return ok;
}

std::vector<uint8_t> AudioNode::read_pcm_nonblocking(size_t max_bytes) {
    std::lock_guard<std::mutex> lock(pcm_mutex_);
    size_t avail = pcm_ring_.read_available();
    if (avail == 0) return {};

    size_t to_read = max_bytes > 0 ? std::min(max_bytes, avail) : avail;
    std::vector<uint8_t> out(to_read);
    size_t got = pcm_ring_.read(out.data(), to_read);
    out.resize(got);
    total_pcm_read_.fetch_add(got, std::memory_order_relaxed);
    return out;
}

std::vector<uint8_t> AudioNode::read_pcm_blocking(size_t max_bytes, int timeout_ms) {
    std::unique_lock<std::mutex> lock(pcm_mutex_);

    if (timeout_ms < 0) {
        pcm_cv_.wait(lock, [this]() {
            return pcm_ring_.read_available() > 0 || !running_.load();
        });
    } else {
        pcm_cv_.wait_for(lock, std::chrono::milliseconds(timeout_ms), [this]() {
            return pcm_ring_.read_available() > 0 || !running_.load();
        });
    }

    size_t avail = pcm_ring_.read_available();
    if (avail == 0) return {};

    size_t to_read = max_bytes > 0 ? std::min(max_bytes, avail) : avail;
    std::vector<uint8_t> out(to_read);
    size_t got = pcm_ring_.read(out.data(), to_read);
    out.resize(got);
    total_pcm_read_.fetch_add(got, std::memory_order_relaxed);
    return out;
}

std::string AudioNode::read_viseme_nonblocking() {
    std::lock_guard<std::mutex> lock(viseme_mutex_);
    size_t avail = viseme_ring_.read_available();
    if (avail == 0) return "";

    std::vector<uint8_t> tmp(avail);
    size_t got = viseme_ring_.read(tmp.data(), avail);
    tmp.resize(got);

    std::string result(tmp.begin(), tmp.end());
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r')) {
        result.pop_back();
    }

    total_visemes_read_.fetch_add(got, std::memory_order_relaxed);
    return result;
}

std::string AudioNode::read_viseme_blocking(int timeout_ms) {
    std::unique_lock<std::mutex> lock(viseme_mutex_);

    if (timeout_ms < 0) {
        viseme_cv_.wait(lock, [this]() {
            return viseme_ring_.read_available() > 0 || !running_.load();
        });
    } else {
        viseme_cv_.wait_for(lock, std::chrono::milliseconds(timeout_ms), [this]() {
            return viseme_ring_.read_available() > 0 || !running_.load();
        });
    }

    size_t avail = viseme_ring_.read_available();
    if (avail == 0) return "";

    std::vector<uint8_t> tmp(avail);
    size_t got = viseme_ring_.read(tmp.data(), avail);
    tmp.resize(got);

    std::string result(tmp.begin(), tmp.end());
    while (!result.empty() && (result.back() == '\n' || result.back() == '\r')) {
        result.pop_back();
    }

    total_visemes_read_.fetch_add(got, std::memory_order_relaxed);
    return result;
}

size_t AudioNode::pcm_available() const {
    std::lock_guard<std::mutex> lock(pcm_mutex_);
    return pcm_ring_.read_available();
}

size_t AudioNode::viseme_available() const {
    std::lock_guard<std::mutex> lock(viseme_mutex_);
    return viseme_ring_.read_available();
}

void AudioNode::flush() {
    flush_pcm();
    flush_visemes();
}

void AudioNode::flush_pcm() {
    std::lock_guard<std::mutex> lock(pcm_mutex_);
    pcm_ring_.flush();
}

void AudioNode::flush_visemes() {
    std::lock_guard<std::mutex> lock(viseme_mutex_);
    viseme_ring_.flush();
}

} // namespace aios
