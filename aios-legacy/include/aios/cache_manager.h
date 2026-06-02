#pragma once

#include <chrono>
#include <cmath>
#include <cstdio>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <vector>

namespace aios {

struct CacheEntry {
    std::vector<float> query_embedding;
    std::string query_text;
    std::string response_text;
    std::chrono::steady_clock::time_point created_at;
    int hit_count = 0;
};

class CacheManager {
public:
    static CacheManager& instance() {
        static CacheManager mgr;
        return mgr;
    }

    void set_threshold(float threshold) {
        threshold_ = threshold;
        std::printf("[SemanticCache] Similarity threshold: %.2f\n", threshold_);
    }

    void set_max_entries(size_t max_entries) {
        max_entries_ = max_entries;
        std::printf("[SemanticCache] Max entries: %zu\n", max_entries_);
    }

    std::string check_cache(const std::vector<float>& query_embedding,
                            const std::string& query_text);

    void add_cache(const std::vector<float>& query_embedding,
                   const std::string& query_text,
                   const std::string& response_text);

    size_t size() const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        return cache_pool_.size();
    }

    void clear() {
        std::unique_lock<std::shared_mutex> lock(mutex_);
        cache_pool_.clear();
        std::printf("[SemanticCache] Cache cleared\n");
    }

    void print_stats() const {
        std::shared_lock<std::shared_mutex> lock(mutex_);
        std::printf("[SemanticCache] Stats: %zu entries, %d hits, %d misses, hit_rate=%.1f%%\n",
                    cache_pool_.size(), total_hits_, total_misses_,
                    (total_hits_ + total_misses_) > 0
                        ? 100.0f * total_hits_ / (total_hits_ + total_misses_)
                        : 0.0f);
    }

private:
    CacheManager() = default;
    CacheManager(const CacheManager&) = delete;
    CacheManager& operator=(const CacheManager&) = delete;

    static float cosine_similarity(const std::vector<float>& a,
                                   const std::vector<float>& b) {
        if (a.size() != b.size() || a.empty()) return 0.0f;
        float dot = 0.0f;
        float norm_a = 0.0f;
        float norm_b = 0.0f;
        for (size_t i = 0; i < a.size(); ++i) {
            dot += a[i] * b[i];
            norm_a += a[i] * a[i];
            norm_b += b[i] * b[i];
        }
        float denom = std::sqrt(norm_a) * std::sqrt(norm_b);
        if (denom < 1e-8f) return 0.0f;
        return dot / denom;
    }

    std::vector<CacheEntry> cache_pool_;
    mutable std::shared_mutex mutex_;
    float threshold_ = 0.95f;
    size_t max_entries_ = 1000;
    mutable int total_hits_ = 0;
    mutable int total_misses_ = 0;
};

} // namespace aios
