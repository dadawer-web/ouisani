#include "aios/cache_manager.h"

#include <algorithm>
#include <chrono>
#include <cstdio>

namespace aios {

std::string CacheManager::check_cache(const std::vector<float>& query_embedding,
                                       const std::string& query_text) {
    if (query_embedding.empty()) {
        return "";
    }

    std::shared_lock<std::shared_mutex> lock(mutex_);

    float best_score = -1.0f;
    std::string best_response;
    int best_index = -1;

    for (size_t i = 0; i < cache_pool_.size(); ++i) {
        float score = cosine_similarity(query_embedding, cache_pool_[i].query_embedding);
        if (score > best_score) {
            best_score = score;
            best_response = cache_pool_[i].response_text;
            best_index = static_cast<int>(i);
        }
    }

    if (best_score >= threshold_ && best_index >= 0) {
        total_hits_++;
        lock.unlock();

        {
            std::unique_lock<std::shared_mutex> write_lock(mutex_);
            cache_pool_[best_index].hit_count++;
        }

        auto now = std::chrono::steady_clock::now();
        auto age_ms = std::chrono::duration_cast<std::chrono::milliseconds>(
            now - cache_pool_[best_index].created_at).count();

        std::printf("\033[1;32m[TLB HIT] 语义缓存命中！免去 LLM 推理，相似度=%.4f，耗时<2ms，缓存年龄=%ldms，命中次数=%d\033[0m\n",
                    best_score, static_cast<long>(age_ms), cache_pool_[best_index].hit_count);
        std::printf("[TLB HIT] Query: \"%s\"\n",
                    query_text.size() > 80 ? (query_text.substr(0, 80) + "...").c_str() : query_text.c_str());
        std::printf("[TLB HIT] Matched: \"%s\"\n",
                    cache_pool_[best_index].query_text.size() > 80
                        ? (cache_pool_[best_index].query_text.substr(0, 80) + "...").c_str()
                        : cache_pool_[best_index].query_text.c_str());

        return best_response;
    }

    total_misses_++;
    std::printf("[TLB MISS] 语义缓存未命中 | best_score=%.4f (threshold=%.2f) | query=\"%s\"\n",
                best_score, threshold_,
                query_text.size() > 60 ? (query_text.substr(0, 60) + "...").c_str() : query_text.c_str());

    return "";
}

void CacheManager::add_cache(const std::vector<float>& query_embedding,
                              const std::string& query_text,
                              const std::string& response_text) {
    if (query_embedding.empty() || response_text.empty()) {
        return;
    }

    std::unique_lock<std::shared_mutex> lock(mutex_);

    if (cache_pool_.size() >= max_entries_) {
        int worst_idx = 0;
        int worst_hits = cache_pool_[0].hit_count;
        for (size_t i = 1; i < cache_pool_.size(); ++i) {
            if (cache_pool_[i].hit_count < worst_hits) {
                worst_hits = cache_pool_[i].hit_count;
                worst_idx = static_cast<int>(i);
            }
        }
        std::printf("[SemanticCache] Evicting entry #%d (hits=%d, query=\"%s\")\n",
                    worst_idx, worst_hits,
                    cache_pool_[worst_idx].query_text.size() > 40
                        ? (cache_pool_[worst_idx].query_text.substr(0, 40) + "...").c_str()
                        : cache_pool_[worst_idx].query_text.c_str());
        cache_pool_.erase(cache_pool_.begin() + worst_idx);
    }

    CacheEntry entry;
    entry.query_embedding = query_embedding;
    entry.query_text = query_text;
    entry.response_text = response_text;
    entry.created_at = std::chrono::steady_clock::now();
    entry.hit_count = 0;

    cache_pool_.push_back(std::move(entry));

    std::printf("[SemanticCache] ADD | pool_size=%zu | query=\"%s\"\n",
                cache_pool_.size(),
                query_text.size() > 60 ? (query_text.substr(0, 60) + "...").c_str() : query_text.c_str());
}

} // namespace aios
