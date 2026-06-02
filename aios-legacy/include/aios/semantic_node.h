#pragma once

#include "aios/agent_task.h"
#include "aios/vfs_node.h"

#include <functional>
#include <memory>
#include <mutex>
#include <string>

namespace aios {

class MemoryManager;

class SemanticNode : public VfsNode {
public:
    using SubmitLlmFn = std::function<void(std::shared_ptr<AgentTask>)>;
    using SubmitTaskFn = std::function<void(std::shared_ptr<AgentTask>)>;

    SemanticNode(const std::string& path,
                 SubmitLlmFn submit_llm_fn,
                 SubmitTaskFn submit_task_fn,
                 std::shared_ptr<MemoryManager> mmgr);

    std::string read() const override;
    bool write(const std::string& data) override;

    std::string last_result() const;

private:
    std::shared_ptr<VfsNode> resolve_or_create(const std::string& target_path);

    SubmitLlmFn submit_llm_fn_;
    SubmitTaskFn submit_task_fn_;
    std::shared_ptr<MemoryManager> memory_mgr_;
    mutable std::mutex result_mutex_;
    std::string last_result_;
};

} // namespace aios
