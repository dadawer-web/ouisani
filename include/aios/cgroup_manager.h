#pragma once

#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace aios {

struct CgroupNode {
    std::string group_name;
    int max_tokens_per_minute;
    int tokens_used;
    double cpu_quota;
    std::vector<int> agent_ids;
    std::string parent_name;
    std::vector<std::string> children_names;
    bool oom_blocked;

    CgroupNode(const std::string& name, int max_tpm, double cpu_q)
        : group_name(name)
        , max_tokens_per_minute(max_tpm)
        , tokens_used(0)
        , cpu_quota(cpu_q)
        , oom_blocked(false)
    {}
};

class CgroupManager {
public:
    static CgroupManager& instance();

    bool create_cgroup(const std::string& name, int max_tokens_per_minute,
                       double cpu_quota, const std::string& parent = "/");

    bool attach_to_cgroup(int agent_id, const std::string& cgroup_name);
    bool detach_from_cgroup(int agent_id);

    bool consume_tokens(int agent_id, int tokens);

    bool is_oom_blocked(int agent_id) const;

    std::string get_agent_cgroup(int agent_id) const;

    void reset_period();

    std::string dump_tree() const;

    std::string get_cgroup_info(const std::string& name) const;

    CgroupManager(const CgroupManager&) = delete;
    CgroupManager& operator=(const CgroupManager&) = delete;

private:
    CgroupManager();

    CgroupNode* find_node(const std::string& name);
    const CgroupNode* find_node(const std::string& name) const;

    bool recursive_check_limit(const std::string& cgroup_name) const;
    void recursive_block_agents(const std::string& cgroup_name);
    void recursive_unblock_agents(const std::string& cgroup_name);

    std::unordered_map<std::string, std::unique_ptr<CgroupNode>> nodes_;
    std::unordered_map<int, std::string> agent_cgroup_map_;
    mutable std::mutex mutex_;
};

} // namespace aios
