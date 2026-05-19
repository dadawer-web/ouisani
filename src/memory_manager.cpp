#include "aios/memory_manager.h"

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

MemoryManager::MemoryManager(size_t context_window_size, const std::string& swap_dir)
    : context_window_size_(context_window_size)
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
    std::lock_guard<std::mutex> lock(mutex_);

    auto& cache = get_or_create_cache(page.agent_id);

    if (cache.size() >= cache.capacity()) {
        MemoryPage evicted = cache.evict_lru();
        if (!evicted.page_id.empty()) {
            std::printf("[Swap Out] Agent %d 内存溢出，页面 %s 已写入磁盘\n",
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

    std::printf("[MemoryManager] WRITE | agent=%d | page_id=%s | role=%s | in_memory=%zu/%zu\n",
                stored.agent_id, stored.page_id.c_str(), stored.role.c_str(),
                cache.size(), cache.capacity());

    return stored.page_id;
}

std::vector<MemoryPage> MemoryManager::read_pages(int agent_id) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = caches_.find(agent_id);
    if (it == caches_.end() || it->second.size() == 0) {
        std::printf("[MemoryManager] READ | agent=%d | memory empty, loading from swap...\n",
                    agent_id);
        auto swapped = swap_in_all(agent_id);
        if (!swapped.empty()) {
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

    std::printf("[MemoryManager] READ | agent=%d | returned %zu pages from memory\n",
                agent_id, pages.size());
    return pages;
}

MemoryPage MemoryManager::read_page_by_keyword(int agent_id, const std::string& keyword) {
    std::lock_guard<std::mutex> lock(mutex_);

    auto it = caches_.find(agent_id);
    if (it != caches_.end()) {
        auto all = it->second.get_all();
        for (const auto& p : all) {
            if (p.content.find(keyword) != std::string::npos) {
                it->second.get(p.page_id);
                std::printf("[MemoryManager] READ_PAGE | agent=%d | HIT in memory | keyword=\"%s\" | page=%s\n",
                            agent_id, keyword.c_str(), p.page_id.c_str());
                return p;
            }
        }
    }

    std::printf("[Page Fault] 发生缺页中断，从磁盘查找 agent=%d keyword=\"%s\"\n",
                agent_id, keyword.c_str());

    MemoryPage* found = swap_in_by_keyword(agent_id, keyword);
    if (found) {
        return *found;
    }

    std::printf("[MemoryManager] READ_PAGE | agent=%d | NOT FOUND | keyword=\"%s\"\n",
                agent_id, keyword.c_str());
    return {};
}

size_t MemoryManager::in_memory_count(int agent_id) const {
    std::lock_guard<std::mutex> lock(mutex_);
    auto it = caches_.find(agent_id);
    return it != caches_.end() ? it->second.size() : 0;
}

void MemoryManager::swap_out(int agent_id, const MemoryPage& page) {
    std::string path = swap_filepath(agent_id);
    std::ofstream ofs(path, std::ios::app);
    if (!ofs.is_open()) {
        std::printf("[MemoryManager] ERROR: cannot open swap file %s\n", path.c_str());
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
        std::printf("[Swap In] Agent %d: 从磁盘换入 %zu 个页面\n",
                    agent_id, result.size());
    }
    return result;
}

MemoryPage* MemoryManager::swap_in_by_keyword(int agent_id, const std::string& keyword) {
    std::string path = swap_filepath(agent_id);
    std::vector<MemoryPage> all_swapped;
    size_t found_index = static_cast<size_t>(-1);

    {
        std::ifstream ifs(path);
        if (!ifs.is_open()) {
            std::printf("[Swap In] Agent %d: 无 swap 文件\n", agent_id);
            return nullptr;
        }

        std::string line;
        while (std::getline(ifs, line)) {
            if (line.empty()) continue;
            try {
                auto page = MemoryPage::from_json(line);
                if (found_index == static_cast<size_t>(-1) &&
                    page.content.find(keyword) != std::string::npos) {
                    found_index = all_swapped.size();
                }
                all_swapped.push_back(std::move(page));
            } catch (...) {}
        }
        ifs.close();
    }

    if (found_index != static_cast<size_t>(-1)) {
        std::filesystem::remove(path);

        auto& cache = get_or_create_cache(agent_id);
        if (cache.size() >= cache.capacity()) {
            MemoryPage evicted = cache.evict_lru();
            if (!evicted.page_id.empty()) {
                std::printf("[Swap Out] Agent %d 内存溢出，页面 %s 已写入磁盘\n",
                            evicted.agent_id, evicted.page_id.c_str());
                swap_out(evicted.agent_id, evicted);
            }
        }

        all_swapped[found_index].timestamp = ++page_counter_;
        cache.put(all_swapped[found_index]);

        for (size_t i = 0; i < all_swapped.size(); ++i) {
            if (i != found_index) {
                swap_out(agent_id, all_swapped[i]);
            }
        }

        std::printf("[Page Fault] 发生缺页中断，从磁盘换入页面 %s\n",
                    all_swapped[found_index].page_id.c_str());

        static thread_local MemoryPage result;
        result = all_swapped[found_index];
        return &result;
    }

    return nullptr;
}

void MemoryManager::ensure_swap_dir() const {
    if (!std::filesystem::exists(swap_dir_)) {
        std::filesystem::create_directories(swap_dir_);
        std::printf("[MemoryManager] Created swap directory: %s\n", swap_dir_.c_str());
    }
}

std::string MemoryManager::swap_filepath(int agent_id) const {
    return swap_dir_ + "/swap_agent_" + std::to_string(agent_id) + ".jsonl";
}

} // namespace aios
