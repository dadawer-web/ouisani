#pragma once

#include <cmath>
#include <list>
#include <mutex>
#include <shared_mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace aios {

struct MemoryPage {
    std::string page_id;
    int agent_id;
    size_t timestamp;
    std::string role;
    std::string content;
    std::vector<float> embedding;

    std::string to_json() const;
    static MemoryPage from_json(const std::string& json_str);
};

inline float CosineSimilarity(const std::vector<float>& a, const std::vector<float>& b) {
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

class LruMemoryCache {
public:
    using PageList = std::list<MemoryPage>;
    using PageMap = std::unordered_map<std::string, PageList::iterator>;

    explicit LruMemoryCache(size_t capacity);

    MemoryPage* put(const MemoryPage& page);
    MemoryPage* get(const std::string& page_id);
    std::vector<MemoryPage> get_all() const;
    bool contains(const std::string& page_id) const;
    bool remove(const std::string& page_id);
    size_t size() const;
    size_t capacity() const;
    MemoryPage evict_lru();

private:
    size_t capacity_;
    PageList list_;
    PageMap map_;
};

class MemoryManager {
public:
    explicit MemoryManager(size_t context_window_size = 5,
                           size_t semantic_top_k = 5,
                           size_t compress_threshold = 10,
                           size_t compress_count = 5,
                           const std::string& swap_dir = "./swap");

    std::string write_page(const MemoryPage& page);

    void update_embedding(const std::string& page_id, int agent_id, std::vector<float> embedding);

    std::vector<MemoryPage> read_pages(int agent_id);

    std::vector<MemoryPage> read_pages_semantic(int agent_id,
                                                 const std::vector<float>& query_embedding,
                                                 size_t top_k = 0);

    MemoryPage read_page_by_keyword(int agent_id, const std::string& keyword);

    size_t in_memory_count(int agent_id) const;

    bool should_compress(int agent_id);
    bool begin_compress(int agent_id);
    void end_compress(int agent_id);
    std::vector<MemoryPage> extract_oldest_pages(int agent_id, size_t count);
    void remove_pages(int agent_id, const std::vector<std::string>& page_ids);

    bool create_snapshot(int agent_id, const std::string& filepath);
    bool restore_snapshot(int agent_id, const std::string& filepath);
    bool purge_agent(int agent_id);

private:
    LruMemoryCache& get_or_create_cache(int agent_id);
    void swap_out(int agent_id, const MemoryPage& page);
    std::vector<MemoryPage> swap_in_all(int agent_id);
    void ensure_swap_dir() const;
    std::string swap_filepath(int agent_id) const;

    size_t context_window_size_;
    size_t semantic_top_k_;
    size_t compress_threshold_;
    size_t compress_count_;
    std::string swap_dir_;
    std::unordered_map<int, LruMemoryCache> caches_;
    mutable std::shared_mutex rw_mutex_;
    std::unordered_set<int> compressing_agents_;
    size_t page_counter_{0};
};

} // namespace aios
