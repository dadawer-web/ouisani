#include "aios/memory_manager.h"

#include <algorithm>
#include <cstdio>
#include <filesystem>
#include <fstream>

#include <nlohmann/json.hpp>

namespace aios {

std::string MemoryPage::to_json() const {
    nlohmann::json j;
    j["page_id"] = page_id;
    j["agent_id"] = agent_id;
    j["timestamp"] = timestamp;
    j["role"] = role;
    j["content"] = content;
    if (!embedding.empty()) {
        j["embedding"] = embedding;
    }
    return j.dump();
}

MemoryPage MemoryPage::from_json(const std::string& json_str) {
    auto j = nlohmann::json::parse(json_str);
    MemoryPage p;
    p.page_id = j.value("page_id", "");
    p.agent_id = j.value("agent_id", -1);
    p.timestamp = j.value("timestamp", 0ULL);
    p.role = j.value("role", "");
    p.content = j.value("content", "");
    if (j.contains("embedding") && j["embedding"].is_array()) {
        p.embedding = j["embedding"].get<std::vector<float>>();
    }
    return p;
}

LruMemoryCache::LruMemoryCache(size_t capacity)
    : capacity_(capacity)
{}

MemoryPage* LruMemoryCache::put(const MemoryPage& page) {
    auto it = map_.find(page.page_id);
    if (it != map_.end()) {
        list_.erase(it->second);
        map_.erase(it);
    }

    list_.push_front(page);
    map_[page.page_id] = list_.begin();
    return &(*list_.begin());
}

MemoryPage* LruMemoryCache::get(const std::string& page_id) {
    auto it = map_.find(page_id);
    if (it == map_.end()) return nullptr;

    list_.splice(list_.begin(), list_, it->second);
    it->second = list_.begin();
    return &(*list_.begin());
}

std::vector<MemoryPage> LruMemoryCache::get_all() const {
    std::vector<MemoryPage> result;
    result.reserve(list_.size());
    for (const auto& p : list_) {
        result.push_back(p);
    }
    return result;
}

bool LruMemoryCache::contains(const std::string& page_id) const {
    return map_.find(page_id) != map_.end();
}

bool LruMemoryCache::remove(const std::string& page_id) {
    auto it = map_.find(page_id);
    if (it == map_.end()) return false;
    list_.erase(it->second);
    map_.erase(it);
    return true;
}

size_t LruMemoryCache::size() const {
    return list_.size();
}

size_t LruMemoryCache::capacity() const {
    return capacity_;
}

MemoryPage LruMemoryCache::evict_lru() {
    if (list_.empty()) {
        return {};
    }

    MemoryPage evicted = list_.back();
    map_.erase(evicted.page_id);
    list_.pop_back();
    return evicted;
}

MemoryManager::MemoryManager(size_t context_window_size, size_t semantic_top_k,
                             size_t compress_threshold, size_t compress_count,
                             const std::string& swap_dir)
    : context_window_size_(context_window_size)
    , semantic_top_k_(semantic_top_k)
    , compress_threshold_(compress_threshold)
    , compress_count_(compress_count)
    , swap_dir_(swap_dir)
{
    ensure_swap_dir();
}

LruMemoryCache& MemoryManager::get_or_create_cache(int agent_id) {
    auto it = caches_.find(agent_id);
    if (it != caches_.end()) return it->second;
    auto [inserted, _] = caches_.emplace(agent_id, LruMemoryCache(context_window_size_));
    return inserted->second;
}

std::string MemoryManager::write_page(const MemoryPage& page) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);

    auto& cache = get_or_create_cache(page.agent_id);

    if (cache.size() >= cache.capacity()) {
        MemoryPage evicted = cache.evict_lru();
        if (!evicted.page_id.empty()) {
            std::printf("[Swap Out] Agent %d context overflow, page %s swapped to disk\n",
                        evicted.agent_id, evicted.page_id.c_str());
            swap_out(evicted.agent_id, evicted);
        }
    }

    MemoryPage stored = page;
    if (stored.page_id.empty()) {
        stored.page_id = "page_" + std::to_string(page.agent_id) + "_" + std::to_string(++page_counter_);
    }
    stored.timestamp = ++page_counter_;

    cache.put(stored);

    std::printf("[MMU] WRITE | agent=%d | page=%s | role=%s | emb=%zu | in_mem=%zu/%zu\n",
                stored.agent_id, stored.page_id.c_str(), stored.role.c_str(),
                stored.embedding.size(), cache.size(), cache.capacity());

    return stored.page_id;
}

void MemoryManager::update_embedding(const std::string& page_id, int agent_id,
                                      std::vector<float> embedding) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);

    auto it = caches_.find(agent_id);
    if (it == caches_.end()) return;

    auto* page = it->second.get(page_id);
    if (page) {
        page->embedding = std::move(embedding);
        std::printf("[MMU] EMBED | agent=%d | page=%s | dim=%zu\n",
                    agent_id, page_id.c_str(), page->embedding.size());
    }
}

std::vector<MemoryPage> MemoryManager::read_pages(int agent_id) {
    std::shared_lock<std::shared_mutex> lock(rw_mutex_);

    auto it = caches_.find(agent_id);
    if (it == caches_.end() || it->second.size() == 0) {
        lock.unlock();
        std::printf("[MMU] READ | agent=%d | memory empty, loading from swap...\n", agent_id);
        auto swapped = swap_in_all(agent_id);
        if (!swapped.empty()) {
            std::unique_lock<std::shared_mutex> wlock(rw_mutex_);
            auto& cache = get_or_create_cache(agent_id);
            for (auto& p : swapped) {
                p.timestamp = ++page_counter_;
                cache.put(p);
            }
        }
        return swapped;
    }

    auto pages = it->second.get_all();
    for (auto& p : pages) {
        it->second.get(p.page_id);
    }

    std::printf("[MMU] READ | agent=%d | returned %zu pages from memory\n",
                agent_id, pages.size());
    return pages;
}

std::vector<MemoryPage> MemoryManager::read_pages_semantic(int agent_id,
                                                            const std::vector<float>& query_embedding,
                                                            size_t top_k) {
    std::shared_lock<std::shared_mutex> lock(rw_mutex_);

    if (top_k == 0) top_k = semantic_top_k_;

    std::vector<MemoryPage> all_pages;

    auto it = caches_.find(agent_id);
    if (it != caches_.end()) {
        all_pages = it->second.get_all();
    }

    if (all_pages.empty()) {
        std::printf("[MMU] SEMANTIC_READ | agent=%d | no pages found\n", agent_id);
        return {};
    }

    if (query_embedding.empty()) {
        std::printf("[MMU] SEMANTIC_READ | agent=%d | no query embedding, fallback to LRU\n", agent_id);
        if (all_pages.size() > top_k) {
            all_pages.resize(top_k);
        }
        return all_pages;
    }

    struct ScoredPage {
        float score;
        MemoryPage page;
    };
    std::vector<ScoredPage> scored;
    scored.reserve(all_pages.size());

    size_t with_emb = 0;
    for (auto& p : all_pages) {
        float score = 0.0f;
        if (!p.embedding.empty() && p.embedding.size() == query_embedding.size()) {
            score = CosineSimilarity(query_embedding, p.embedding);
            ++with_emb;
        }
        std::string preview = p.content.size() > 60 ? p.content.substr(0, 60) + "..." : p.content;
        std::printf("[MMU] SCORE | agent=%d | page=%s | role=%s | score=%.4f | emb=%zu | \"%s\"\n",
                    agent_id, p.page_id.c_str(), p.role.c_str(), score,
                    p.embedding.size(), preview.c_str());
        scored.push_back({score, std::move(p)});
    }

    std::partial_sort(scored.begin(),
                       scored.begin() + std::min(top_k, scored.size()),
                       scored.end(),
                       [](const ScoredPage& a, const ScoredPage& b) {
                           return a.score > b.score;
                       });

    std::vector<MemoryPage> result;
    size_t k = std::min(top_k, scored.size());
    result.reserve(k);
    for (size_t i = 0; i < k; ++i) {
        std::string preview = scored[i].page.content.size() > 60
            ? scored[i].page.content.substr(0, 60) + "..." : scored[i].page.content;
        std::printf("[MMU] TOP%d | page=%s | score=%.4f | \"%s\"\n",
                    static_cast<int>(i + 1), scored[i].page.page_id.c_str(),
                    scored[i].score, preview.c_str());
        result.push_back(std::move(scored[i].page));
    }

    std::printf("[MMU] SEMANTIC_READ | agent=%d | total=%zu | with_emb=%zu | top_%zu selected\n",
                agent_id, all_pages.size(), with_emb, k);

    return result;
}

MemoryPage MemoryManager::read_page_by_keyword(int agent_id, const std::string& keyword) {
    std::shared_lock<std::shared_mutex> lock(rw_mutex_);

    auto it = caches_.find(agent_id);
    if (it != caches_.end()) {
        auto all = it->second.get_all();
        for (const auto& p : all) {
            if (p.content.find(keyword) != std::string::npos) {
                it->second.get(p.page_id);
                std::printf("[MMU] KEYWORD_HIT | agent=%d | keyword=\"%s\" | page=%s\n",
                            agent_id, keyword.c_str(), p.page_id.c_str());
                return p;
            }
        }
    }

    std::printf("[MMU] KEYWORD_MISS | agent=%d | keyword=\"%s\"\n", agent_id, keyword.c_str());
    return {};
}

size_t MemoryManager::in_memory_count(int agent_id) const {
    std::shared_lock<std::shared_mutex> lock(rw_mutex_);
    auto it = caches_.find(agent_id);
    return it != caches_.end() ? it->second.size() : 0;
}

bool MemoryManager::should_compress(int agent_id) {
    std::shared_lock<std::shared_mutex> lock(rw_mutex_);
    auto it = caches_.find(agent_id);
    if (it == caches_.end()) return false;
    return it->second.size() >= compress_threshold_;
}

bool MemoryManager::begin_compress(int agent_id) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);
    if (compressing_agents_.count(agent_id) > 0) {
        return false;
    }
    compressing_agents_.insert(agent_id);
    std::printf("[MMU] COMPRESS_LOCK | agent=%d | compression started\n", agent_id);
    return true;
}

void MemoryManager::end_compress(int agent_id) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);
    compressing_agents_.erase(agent_id);
    std::printf("[MMU] COMPRESS_UNLOCK | agent=%d | compression finished\n", agent_id);
}

std::vector<MemoryPage> MemoryManager::extract_oldest_pages(int agent_id, size_t count) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);

    auto it = caches_.find(agent_id);
    if (it == caches_.end()) return {};

    auto all = it->second.get_all();

    std::sort(all.begin(), all.end(),
              [](const MemoryPage& a, const MemoryPage& b) {
                  return a.timestamp < b.timestamp;
              });

    size_t n = std::min(count, all.size());
    std::vector<MemoryPage> oldest(all.begin(), all.begin() + static_cast<ptrdiff_t>(n));

    std::printf("[MMU] EXTRACT | agent=%d | extracted %zu oldest pages (timestamps: %zu..%zu)\n",
                agent_id, n, oldest.front().timestamp, oldest.back().timestamp);

    return oldest;
}

void MemoryManager::remove_pages(int agent_id, const std::vector<std::string>& page_ids) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);

    auto it = caches_.find(agent_id);
    if (it == caches_.end()) return;

    for (const auto& pid : page_ids) {
        it->second.remove(pid);
    }

    std::printf("[MMU] REMOVE | agent=%d | removed %zu pages | remaining=%zu\n",
                agent_id, page_ids.size(), it->second.size());
}

void MemoryManager::swap_out(int agent_id, const MemoryPage& page) {
    std::string path = swap_filepath(agent_id);
    std::ofstream ofs(path, std::ios::app);
    if (!ofs.is_open()) {
        std::printf("[MMU] ERROR: cannot open swap file %s\n", path.c_str());
        return;
    }
    ofs << page.to_json() << "\n";
    ofs.close();
}

std::vector<MemoryPage> MemoryManager::swap_in_all(int agent_id) {
    std::string path = swap_filepath(agent_id);
    std::vector<MemoryPage> result;

    std::ifstream ifs(path);
    if (!ifs.is_open()) return result;

    std::string line;
    while (std::getline(ifs, line)) {
        if (!line.empty()) {
            try {
                result.push_back(MemoryPage::from_json(line));
            } catch (...) {}
        }
    }
    ifs.close();

    if (!result.empty()) {
        std::filesystem::remove(path);
        std::printf("[Swap In] Agent %d: loaded %zu pages from disk\n",
                    agent_id, result.size());
    }
    return result;
}

void MemoryManager::ensure_swap_dir() const {
    if (!std::filesystem::exists(swap_dir_)) {
        std::filesystem::create_directories(swap_dir_);
        std::printf("[MMU] Created swap directory: %s\n", swap_dir_.c_str());
    }
}

std::string MemoryManager::swap_filepath(int agent_id) const {
    return swap_dir_ + "/swap_agent_" + std::to_string(agent_id) + ".jsonl";
}

bool MemoryManager::create_snapshot(int agent_id, const std::string& filepath) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);

    std::vector<MemoryPage> all_pages;

    auto it = caches_.find(agent_id);
    if (it != caches_.end()) {
        all_pages = it->second.get_all();
    }

    auto swapped = swap_in_all(agent_id);
    for (auto& p : swapped) {
        all_pages.push_back(std::move(p));
    }

    if (all_pages.empty()) {
        std::printf("[Snapshot] Agent#%d has no pages to snapshot\n", agent_id);
        return false;
    }

    nlohmann::json snapshot;
    snapshot["agent_id"] = agent_id;
    snapshot["version"] = "1.0";
    snapshot["page_count"] = all_pages.size();

    nlohmann::json pages_arr = nlohmann::json::array();
    for (const auto& page : all_pages) {
        nlohmann::json pj;
        pj["page_id"] = page.page_id;
        pj["agent_id"] = page.agent_id;
        pj["timestamp"] = page.timestamp;
        pj["role"] = page.role;
        pj["content"] = page.content;
        if (!page.embedding.empty()) {
            pj["embedding"] = page.embedding;
        }
        pages_arr.push_back(std::move(pj));
    }
    snapshot["pages"] = std::move(pages_arr);

    std::string dir = filepath.substr(0, filepath.rfind('/'));
    if (!dir.empty() && !std::filesystem::exists(dir)) {
        std::filesystem::create_directories(dir);
    }

    std::ofstream ofs(filepath);
    if (!ofs.is_open()) {
        std::printf("[Snapshot] ERROR: cannot open file %s for writing\n", filepath.c_str());
        return false;
    }

    ofs << snapshot.dump(2);
    ofs.close();

    size_t emb_count = 0;
    for (const auto& p : all_pages) {
        if (!p.embedding.empty()) emb_count++;
    }

    std::printf("[Snapshot] Agent#%d frozen | pages=%zu | with_embedding=%zu | file=%s\n",
                agent_id, all_pages.size(), emb_count, filepath.c_str());
    return true;
}

bool MemoryManager::restore_snapshot(int agent_id, const std::string& filepath) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);

    if (!std::filesystem::exists(filepath)) {
        std::printf("[Restore] ERROR: snapshot file not found: %s\n", filepath.c_str());
        return false;
    }

    std::ifstream ifs(filepath);
    if (!ifs.is_open()) {
        std::printf("[Restore] ERROR: cannot open file %s for reading\n", filepath.c_str());
        return false;
    }

    std::string content((std::istreambuf_iterator<char>(ifs)),
                         std::istreambuf_iterator<char>());
    ifs.close();

    nlohmann::json snapshot;
    try {
        snapshot = nlohmann::json::parse(content);
    } catch (const nlohmann::json::parse_error& e) {
        std::printf("[Restore] ERROR: JSON parse failed: %s\n", e.what());
        return false;
    }

    int snap_agent = snapshot.value("agent_id", -1);
    if (snap_agent != agent_id) {
        std::printf("[Restore] ERROR: agent_id mismatch: snapshot=%d, requested=%d\n",
                    snap_agent, agent_id);
        return false;
    }

    caches_.erase(agent_id);
    auto& cache = get_or_create_cache(agent_id);

    size_t restored_count = 0;
    size_t emb_count = 0;

    if (snapshot.contains("pages") && snapshot["pages"].is_array()) {
        for (const auto& pj : snapshot["pages"]) {
            MemoryPage page;
            page.page_id = pj.value("page_id", "");
            page.agent_id = pj.value("agent_id", agent_id);
            page.timestamp = pj.value("timestamp", 0ULL);
            page.role = pj.value("role", "");
            page.content = pj.value("content", "");
            if (pj.contains("embedding") && pj["embedding"].is_array()) {
                page.embedding = pj["embedding"].get<std::vector<float>>();
                emb_count++;
            }

            if (page.page_id.empty()) {
                page.page_id = "page_" + std::to_string(agent_id) + "_" + std::to_string(++page_counter_);
            }
            page.timestamp = ++page_counter_;

            cache.put(page);
            restored_count++;
        }
    }

    std::printf("[Restore] Agent#%d resurrected | pages=%zu | with_embedding=%zu | from=%s\n",
                agent_id, restored_count, emb_count, filepath.c_str());
    return true;
}

bool MemoryManager::purge_agent(int agent_id) {
    std::unique_lock<std::shared_mutex> lock(rw_mutex_);

    size_t mem_pages = 0;
    auto it = caches_.find(agent_id);
    if (it != caches_.end()) {
        mem_pages = it->second.size();
        caches_.erase(it);
    }

    std::string sf = swap_filepath(agent_id);
    size_t swap_pages = 0;
    if (std::filesystem::exists(sf)) {
        std::ifstream ifs(sf);
        std::string line;
        while (std::getline(ifs, line)) {
            if (!line.empty()) ++swap_pages;
        }
        std::filesystem::remove(sf);
    }

    compressing_agents_.erase(agent_id);

    std::printf("[Purge] Agent#%d memory PURGED | in_mem=%zu | swap=%zu | total=%zu\n",
                agent_id, mem_pages, swap_pages, mem_pages + swap_pages);
    return true;
}

} // namespace aios
