#pragma once

#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace aios {

struct MemoryPage {
    std::string page_id;
    int agent_id;
    size_t timestamp;
    std::string role;
    std::string content;

    std::string to_json() const;
    static MemoryPage from_json(const std::string& json_str);
};

class LruMemoryCache {
public:
    using PageList = std::list<MemoryPage>;
    using PageMap = std::unordered_map<std::string, PageList::iterator>;

    explicit LruMemoryCache(size_t capacity);

    MemoryPage* put(const MemoryPage& page);
    MemoryPage* get(const std::string& page_id);
    std::vector<MemoryPage> get_all() const;
    bool contains(const std::string& page_id) const;
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
                           const std::string& swap_dir = "./swap");

    std::string write_page(const MemoryPage& page);

    std::vector<MemoryPage> read_pages(int agent_id);

    MemoryPage read_page_by_keyword(int agent_id, const std::string& keyword);

    size_t in_memory_count(int agent_id) const;

private:
    LruMemoryCache& get_or_create_cache(int agent_id);
    void swap_out(int agent_id, const MemoryPage& page);
    std::vector<MemoryPage> swap_in_all(int agent_id);
    MemoryPage* swap_in_by_keyword(int agent_id, const std::string& keyword);
    void ensure_swap_dir() const;
    std::string swap_filepath(int agent_id) const;

    size_t context_window_size_;
    std::string swap_dir_;
    std::unordered_map<int, LruMemoryCache> caches_;
    mutable std::mutex mutex_;
    size_t page_counter_{0};
};

} // namespace aios
