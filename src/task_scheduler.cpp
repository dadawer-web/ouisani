#include "aios/task_scheduler.h"
#include "aios/agent_registry.h"
#include "aios/cache_manager.h"
#include "aios/compiler_bridge.h"
#include "aios/security_guard.h"
#include "aios/vfs_manager.h"
#include "aios/vfs_node.h"
#include "aios/wasm_node.h"

#include <chrono>
#include <cstdio>
#include <nlohmann/json.hpp>

namespace aios {

TaskScheduler::TaskScheduler(size_t dispatch_threads,
                             size_t io_threads,
                             std::shared_ptr<LlmAdapter> llm,
                             std::shared_ptr<MemoryManager> memory_mgr)
    : dispatch_pool_(dispatch_threads > 0 ? dispatch_threads : 2)
    , io_pool_(io_threads > 0 ? io_threads : 4)
    , wasm_pool_(std::make_unique<ThreadPool>(2))
    , llm_(std::move(llm))
    , memory_mgr_(std::move(memory_mgr))
{}

TaskScheduler::~TaskScheduler() {
    shutdown();
}

void TaskScheduler::submit(std::shared_ptr<AgentTask> task) {
    if (!task) return;
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        task->status = TaskStatus::READY;

        int qidx = 1;
        if (task->type == TaskType::PROCESS_CTRL) {
            qidx = 0;
        } else if (task->type == TaskType::VFS_CALL && task->tool_name == "COMPILE_AND_EXECUTE") {
            qidx = 2;
        }

        const char* qnames[] = {"Q0-CTRL", "Q1-IO", "Q2-CPU"};
        std::printf("[Scheduler] Task routed to %s | agent=%d | type=%d\n",
                    qnames[qidx], task->agent_id, static_cast<int>(task->type));

        queues_[qidx].push(std::move(task));
    }
    queue_cv_.notify_one();
}

void TaskScheduler::cancel_agent(int agent_id) {
    std::printf("[Interrupt] CANCEL_TASK received for agent=%d\n", agent_id);

    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        int cancelled_count = 0;
        for (int i = 0; i < PRIORITY_QUEUE_COUNT; ++i) {
            std::queue<std::shared_ptr<AgentTask>> new_queue;
            while (!queues_[i].empty()) {
                auto t = queues_[i].front();
                queues_[i].pop();
                if (t->agent_id == agent_id) {
                    t->cancel();
                    t->status = TaskStatus::CANCELLED;
                    ++cancelled_count;
                } else {
                    new_queue.push(std::move(t));
                }
            }
            queues_[i] = std::move(new_queue);
        }
        if (cancelled_count > 0) {
            std::printf("[Interrupt] Cancelled %d queued tasks for agent=%d\n",
                        cancelled_count, agent_id);
        }
    }

    {
        std::lock_guard<std::mutex> lock(active_mutex_);
        auto it = active_tasks_.find(agent_id);
        if (it != active_tasks_.end()) {
            for (auto& t : it->second) {
                t->cancel();
                std::printf("[Interrupt] Active task for agent=%d flagged for cancellation\n", agent_id);
            }
        }
    }
}

void TaskScheduler::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        return;
    }

    dispatch_pool_.start();
    io_pool_.start();
    wasm_pool_->start();

    for (size_t i = 0; i < dispatch_pool_.worker_count(); ++i) {
        dispatch_pool_.submit([this]() { dispatch_loop(); });
    }

    kswapd_thread_ = std::thread(&TaskScheduler::kswapd_loop, this);

    std::printf("[Scheduler] Started: dispatch=%zu, io=%zu, wasm=%zu, drivers=%zu, embedding=%s\n",
                dispatch_pool_.worker_count(), io_pool_.worker_count(),
                wasm_pool_->worker_count(), drivers_.size(),
                llm_->has_embedding_config() ? "ON" : "OFF");
    std::printf("[Scheduler] kswapd daemon launched | MAX_WASM_VMS=%d\n", MAX_WASM_VMS);
}

void TaskScheduler::shutdown() {
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        if (!running_.load()) return;
        running_.store(false);
    }
    queue_cv_.notify_all();

    io_pool_.shutdown();
    wasm_pool_->shutdown();
    dispatch_pool_.shutdown();

    if (kswapd_thread_.joinable()) {
        kswapd_thread_.join();
    }

    while (true) {
        bool any = false;
        for (int i = 0; i < PRIORITY_QUEUE_COUNT; ++i) {
            if (!queues_[i].empty()) {
                queues_[i].pop();
                any = true;
            }
        }
        if (!any) break;
    }

    std::printf("[Scheduler] Shutdown complete\n");
}

void TaskScheduler::register_driver(const std::string& name, std::shared_ptr<DeviceDriver> driver) {
    drivers_[name] = std::move(driver);
    std::printf("[Scheduler] Registered driver: %s\n", name.c_str());
}

void TaskScheduler::set_response_callback(ResponseCallback cb) {
    response_cb_ = std::move(cb);
}

size_t TaskScheduler::pending_count() const {
    std::lock_guard<std::mutex> lock(queue_mutex_);
    size_t total = 0;
    for (int i = 0; i < PRIORITY_QUEUE_COUNT; ++i) {
        total += queues_[i].size();
    }
    return total;
}

size_t TaskScheduler::active_io_count() const {
    return active_io_tasks_.load();
}

std::string TaskScheduler::GetSystemStat() const {
    size_t q0 = 0, q1 = 0, q2 = 0;
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        q0 = queues_[0].size();
        q1 = queues_[1].size();
        q2 = queues_[2].size();
    }

    nlohmann::json j;
    j["q0_len"] = q0;
    j["q1_len"] = q1;
    j["q2_len"] = q2;
    j["active_vms"] = active_wasm_vms_.load();
    j["max_vms"] = MAX_WASM_VMS;
    j["page_faults"] = total_page_faults_.load();
    j["total_tasks"] = total_tasks_executed_.load();
    j["active_io"] = active_io_tasks_.load();
    return j.dump();
}

std::string TaskScheduler::make_response(bool ok, const std::string& message,
                                          const std::string& data) {
    nlohmann::json j;
    j["status"] = ok ? "ok" : "error";
    j["message"] = message;
    if (!data.empty()) {
        auto parsed = nlohmann::json::parse(data, nullptr, false);
        if (!parsed.is_discarded()) {
            j["data"] = parsed;
        } else {
            j["data"] = data;
        }
    }
    return j.dump() + "\n";
}

std::vector<ChatMessage> TaskScheduler::build_messages(int agent_id, const std::string& current_payload) {
    std::vector<float> query_emb;
    if (llm_->has_embedding_config()) {
        query_emb = llm_->get_embedding(current_payload);
    }

    std::vector<MemoryPage> pages;
    if (!query_emb.empty()) {
        pages = memory_mgr_->read_pages_semantic(agent_id, query_emb);
    } else {
        pages = memory_mgr_->read_pages(agent_id);
    }

    std::string context_block;
    for (const auto& p : pages) {
        context_block += "[" + p.role + "]: " + p.content + "\n";
    }

    std::vector<ChatMessage> messages;

    if (!context_block.empty()) {
        std::string context_msg =
            "=== 以下是你的历史记忆上下文（由 AIOS 语义 MMU 检索提供）===\n"
            "请严格根据这些记忆来回答用户的问题。如果记忆中包含相关信息，必须使用它来回答。\n\n"
            + context_block +
            "\n=== 历史记忆上下文结束 ===";
        messages.push_back({"system", context_msg});
    }

    messages.push_back({"user", current_payload});
    return messages;
}

void TaskScheduler::try_compress(int agent_id) {
    if (!memory_mgr_->should_compress(agent_id)) return;
    if (!memory_mgr_->begin_compress(agent_id)) return;

    std::printf("[MMU] *** HIGH WATERMARK REACHED | agent=%d | triggering background compression ***\n",
                agent_id);

    auto oldest = memory_mgr_->extract_oldest_pages(agent_id, 5);
    if (oldest.size() < 2) {
        memory_mgr_->end_compress(agent_id);
        return;
    }

    size_t oldest_count = oldest.size();
    std::vector<std::string> page_ids;
    std::string combined;
    for (const auto& p : oldest) {
        page_ids.push_back(p.page_id);
        combined += "[" + p.role + "] " + p.content + "\n";
    }

    memory_mgr_->remove_pages(agent_id, page_ids);

    auto llm = llm_;
    auto mmgr = memory_mgr_;

    try {
        io_pool_.submit([llm, mmgr, agent_id, oldest_count, combined = std::move(combined)]() {
            std::printf("[Compress] Agent=%d | LLM summarizing %zu bytes of old memory...\n",
                        agent_id, combined.size());

            std::string compress_prompt =
                "You are a kernel memory compressor. Summarize the following conversation "
                "into a concise statement preserving all key entities, facts, and decisions. "
                "Remove greetings and filler. Output ONLY the summary:\n\n" + combined;

            std::string summary = llm->generate("", compress_prompt);

            std::printf("[Compress] Agent=%d | Summary: \"%s\"\n",
                        agent_id,
                        summary.size() > 200 ? (summary.substr(0, 200) + "...").c_str() : summary.c_str());

            MemoryPage compressed;
            compressed.agent_id = agent_id;
            compressed.role = "system";
            compressed.content = "[Compressed Memory] " + summary;
            std::string page_id = mmgr->write_page(compressed);

            if (llm->has_embedding_config() && !summary.empty()) {
                auto emb = llm->get_embedding(compressed.content);
                if (!emb.empty()) {
                    mmgr->update_embedding(page_id, agent_id, std::move(emb));
                }
            }

            mmgr->end_compress(agent_id);

            std::printf("[Compress] Agent=%d | Compression complete | %zu pages -> 1 compressed page\n",
                        agent_id, oldest_count);
        });
    } catch (const std::exception& e) {
        memory_mgr_->end_compress(agent_id);
        std::printf("[Compress] Agent=%d | Failed to submit: %s\n", agent_id, e.what());
    }
}

void TaskScheduler::kswapd_loop() {
    std::printf("[Ring 0 | kswapd] Daemon thread started\n");

    while (running_.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(500));

        int current_vms = active_wasm_vms_.load();

        if (current_vms <= MAX_WASM_VMS) {
            continue;
        }

        std::printf("[Ring 0 | kswapd] Scan | active_vms=%d / max=%d\n",
                    current_vms, MAX_WASM_VMS);

        int victim_agent = -1;
        {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            for (auto& [aid, swapped] : swapped_out_agents_) {
                if (!swapped) {
                    victim_agent = aid;
                    break;
                }
            }
        }

        if (victim_agent < 0) continue;

        std::printf("[Ring 0 | kswapd] 物理内存不足！正在触发 Swap Out... 将 Agent %d 核心转储到磁盘。\n",
                    victim_agent);

        WasmNode::SendSignal(victim_agent, 19);

        {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            swapped_out_agents_[victim_agent] = true;
        }
        active_wasm_vms_.fetch_sub(1);

        std::printf("[Ring 0 | kswapd] Agent %d 已换出至 Swap 分区 | active_vms=%d\n",
                    victim_agent, active_wasm_vms_.load());
    }

    std::printf("[Ring 0 | kswapd] Daemon thread exiting\n");
}

void TaskScheduler::dispatch_loop() {
    while (running_.load()) {
        std::shared_ptr<AgentTask> task;

        {
            std::unique_lock<std::mutex> lock(queue_mutex_);
            bool ok = queue_cv_.wait_for(lock, std::chrono::milliseconds(200), [this] {
                for (int i = 0; i < PRIORITY_QUEUE_COUNT; ++i) {
                    if (!queues_[i].empty()) return true;
                }
                return !running_.load();
            });

            bool all_empty = true;
            for (int i = 0; i < PRIORITY_QUEUE_COUNT; ++i) {
                if (!queues_[i].empty()) { all_empty = false; break; }
            }

            if (!running_.load() && all_empty) {
                return;
            }

            if (!ok || all_empty) {
                continue;
            }

            for (int i = 0; i < PRIORITY_QUEUE_COUNT; ++i) {
                if (!queues_[i].empty()) {
                    task = queues_[i].front();
                    queues_[i].pop();
                    break;
                }
            }
        }

        if (!task) continue;

        if (task->cancelled()) {
            std::printf("[Interrupt] Agent=%d task discarded (cancelled before dispatch)\n",
                        task->agent_id);
            continue;
        }

        task->status = TaskStatus::RUNNING;
        total_tasks_executed_.fetch_add(1);

        {
            std::lock_guard<std::mutex> lock(active_mutex_);
            active_tasks_[task->agent_id].push_back(task);
        }

        switch (task->type) {
            case TaskType::WRITE_MEMORY:
                handle_write_memory(std::move(task));
                break;
            case TaskType::READ_MEMORY:
                handle_read_memory(std::move(task));
                break;
            case TaskType::LLM_CHAT:
                dispatch_llm_task(std::move(task));
                break;
            case TaskType::TOOL_CALL:
                dispatch_tool_task(std::move(task));
                break;
            case TaskType::VFS_CALL:
                dispatch_vfs_task(std::move(task));
                break;
            case TaskType::PROCESS_CTRL:
                dispatch_process_ctrl(std::move(task));
                break;
            case TaskType::CANCEL_TASK:
                break;
        }
    }
}

void TaskScheduler::handle_write_memory(std::shared_ptr<AgentTask> task) {
    std::printf("[Dispatch] WRITE_MEMORY | agent=%d | role=%s\n",
                task->agent_id, task->role.c_str());

    if (task->cancelled()) {
        std::printf("[Interrupt] Agent=%d WRITE_MEMORY cancelled before execution\n",
                    task->agent_id);
        return;
    }

    MemoryPage page;
    page.agent_id = task->agent_id;
    page.role = task->role;
    page.content = task->content;

    std::string page_id = memory_mgr_->write_page(page);

    nlohmann::json resp_data;
    resp_data["page_id"] = page_id;

    if (response_cb_) {
        response_cb_(task->client_fd,
            make_response(true, "memory written for agent " + std::to_string(task->agent_id),
                          resp_data.dump()));
    }

    if (llm_->has_embedding_config() && !task->content.empty()) {
        auto content_copy = task->content;
        int agent_id = task->agent_id;
        auto llm = llm_;
        auto mmgr = memory_mgr_;

        try {
            io_pool_.submit([llm, mmgr, page_id, agent_id, content_copy]() {
                std::printf("[IOPool] Async embedding for page %s...\n", page_id.c_str());
                auto emb = llm->get_embedding(content_copy);
                if (!emb.empty()) {
                    mmgr->update_embedding(page_id, agent_id, std::move(emb));
                }
            });
        } catch (const std::exception& e) {
            std::printf("[Dispatch] Failed to submit embedding task: %s\n", e.what());
        }
    }

    try_compress(task->agent_id);

    {
        std::lock_guard<std::mutex> lock(active_mutex_);
        auto& vec = active_tasks_[task->agent_id];
        vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
    }
}

void TaskScheduler::handle_read_memory(std::shared_ptr<AgentTask> task) {
    std::printf("[Dispatch] READ_MEMORY | agent=%d | query=\"%s\"\n",
                task->agent_id, task->keyword.c_str());

    if (task->cancelled()) {
        std::printf("[Interrupt] Agent=%d READ_MEMORY cancelled\n", task->agent_id);
        return;
    }

    if (task->keyword.empty()) {
        auto pages = memory_mgr_->read_pages(task->agent_id);
        nlohmann::json arr = nlohmann::json::array();
        for (const auto& p : pages) {
            nlohmann::json obj;
            obj["page_id"] = p.page_id;
            obj["role"] = p.role;
            obj["content"] = p.content;
            arr.push_back(obj);
        }

        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(true, "memory read for agent " + std::to_string(task->agent_id),
                              arr.dump()));
        }
        return;
    }

    auto page = memory_mgr_->read_page_by_keyword(task->agent_id, task->keyword);
    if (page.page_id.empty()) {
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "page not found for keyword: " + task->keyword));
        }
        return;
    }

    nlohmann::json obj;
    obj["page_id"] = page.page_id;
    obj["role"] = page.role;
    obj["content"] = page.content;

    if (response_cb_) {
        response_cb_(task->client_fd,
            make_response(true, "memory read for agent " + std::to_string(task->agent_id),
                          obj.dump()));
    }

    {
        std::lock_guard<std::mutex> lock(active_mutex_);
        auto& vec = active_tasks_[task->agent_id];
        vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
    }
}

void TaskScheduler::dispatch_llm_task(std::shared_ptr<AgentTask> task) {
    std::printf("[Dispatch] Agent#%d LLM CHAT | payload=\"%s\"\n",
                task->agent_id,
                task->task_payload.size() > 80 ? (task->task_payload.substr(0, 80) + "...").c_str() : task->task_payload.c_str());

    if (task->cancelled()) {
        std::printf("[Interrupt] Agent=%d LLM task cancelled before dispatch\n", task->agent_id);
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "task cancelled for agent " + std::to_string(task->agent_id)));
        }
        return;
    }

    auto messages = build_messages(task->agent_id, task->task_payload);
    std::string system_prompt =
        "你是一个拥有长期记忆的 AI 助手，运行在 AIOS Core 操作系统内。"
        "系统会通过语义检索为你提供相关的历史记忆上下文。"
        "你必须严格根据提供的记忆上下文来回答用户问题。"
        "如果记忆中包含答案，直接引用；回答要简洁准确。";

    std::printf("[LLM Prompt] System: %s\n", system_prompt.c_str());
    for (const auto& m : messages) {
        std::string preview = m.content.size() > 120 ? m.content.substr(0, 120) + "..." : m.content;
        std::printf("[LLM Prompt] [%s]: %s\n", m.role.c_str(), preview.c_str());
    }

    auto& cache_mgr = CacheManager::instance();
    std::vector<float> query_embedding;

    if (llm_->has_embedding_config()) {
        query_embedding = llm_->get_embedding(task->task_payload);

        if (!query_embedding.empty()) {
            std::string cached_response = cache_mgr.check_cache(query_embedding, task->task_payload);
            if (!cached_response.empty()) {
                MemoryPage page;
                page.agent_id = task->agent_id;
                page.role = "assistant";
                page.content = cached_response;
                std::string page_id = memory_mgr_->write_page(page);

                if (llm_->has_embedding_config()) {
                    auto emb = llm_->get_embedding(cached_response);
                    if (!emb.empty()) {
                        memory_mgr_->update_embedding(page_id, task->agent_id, std::move(emb));
                    }
                }

                if (response_cb_) {
                    nlohmann::json resp_data;
                    resp_data["response"] = cached_response;
                    resp_data["cache_hit"] = true;
                    response_cb_(task->client_fd,
                        make_response(true,
                            "LLM task completed (cache hit) for agent " + std::to_string(task->agent_id),
                            resp_data.dump()));
                }

                {
                    std::lock_guard<std::mutex> lock(active_mutex_);
                    auto& vec = active_tasks_[task->agent_id];
                    vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
                }
                return;
            }
        }
    }

    active_io_tasks_.fetch_add(1);

    try {
        io_pool_.submit([this, task, messages = std::move(messages),
                         system_prompt = std::move(system_prompt),
                         query_embedding = std::move(query_embedding)]() {
            if (task->cancelled()) {
                std::printf("[Interrupt] Agent=%d LLM task cancelled, skipping HTTP call\n",
                            task->agent_id);
                if (response_cb_) {
                    response_cb_(task->client_fd,
                        make_response(false, "task cancelled for agent " + std::to_string(task->agent_id)));
                }
                active_io_tasks_.fetch_sub(1);
                return;
            }

            std::printf("[IOPool] Agent#%d LLM HTTP request started\n", task->agent_id);

            std::string llm_response;
            try {
                llm_response = llm_->generate(system_prompt, messages);
            } catch (const std::exception& e) {
                std::printf("[IOPool] Agent#%d LLM call exception: %s\n",
                            task->agent_id, e.what());
                llm_response = "[System Error: LLM call failed - " + std::string(e.what()) + "]";
            }

            if (llm_response.find("[LLM ERROR]") != std::string::npos) {
                std::printf("[IOPool] Agent#%d LLM returned error response\n", task->agent_id);

                MemoryPage err_page;
                err_page.agent_id = task->agent_id;
                err_page.role = "system";
                err_page.content = "[System Error: LLM call failed, response timeout or error]";
                memory_mgr_->write_page(err_page);

                if (response_cb_) {
                    response_cb_(task->client_fd,
                        make_response(false, "LLM call failed for agent " + std::to_string(task->agent_id)));
                }
                active_io_tasks_.fetch_sub(1);
                return;
            }

            if (task->cancelled()) {
                std::printf("[Interrupt] Agent=%d LLM task cancelled after HTTP response, discarding\n",
                            task->agent_id);
                active_io_tasks_.fetch_sub(1);
                return;
            }

            std::printf("[IOPool] Agent#%d LLM HTTP response received (%zu chars)\n",
                        task->agent_id, llm_response.size());

            if (!query_embedding.empty() && !llm_response.empty()) {
                CacheManager::instance().add_cache(query_embedding, task->task_payload, llm_response);
            }

            MemoryPage page;
            page.agent_id = task->agent_id;
            page.role = "assistant";
            page.content = llm_response;
            std::string page_id = memory_mgr_->write_page(page);

            if (llm_->has_embedding_config() && !llm_response.empty()) {
                auto emb = llm_->get_embedding(llm_response);
                if (!emb.empty()) {
                    memory_mgr_->update_embedding(page_id, task->agent_id, std::move(emb));
                }
            }

            if (response_cb_) {
                nlohmann::json resp_data;
                resp_data["response"] = llm_response;
                response_cb_(task->client_fd,
                    make_response(true,
                        "LLM task completed for agent " + std::to_string(task->agent_id),
                        resp_data.dump()));
            }

            active_io_tasks_.fetch_sub(1);
            std::printf("[IOPool] Agent#%d LLM pipeline complete\n", task->agent_id);

            {
                std::lock_guard<std::mutex> lock(active_mutex_);
                auto& vec = active_tasks_[task->agent_id];
                vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
            }
        });
    } catch (const std::exception& e) {
        active_io_tasks_.fetch_sub(1);
        std::printf("[Dispatch] Agent#%d failed to submit to io_pool: %s\n",
                    task->agent_id, e.what());
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "failed to dispatch LLM task: " + std::string(e.what())));
        }
    }
}

void TaskScheduler::dispatch_tool_task(std::shared_ptr<AgentTask> task) {
    std::printf("[Dispatch] Agent#%d TOOL CALL | tool=%s | code=%zu bytes\n",
                task->agent_id, task->tool_name.c_str(), task->tool_code.size());

    if (task->cancelled()) {
        std::printf("[Interrupt] Agent=%d TOOL task cancelled before dispatch\n", task->agent_id);
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "task cancelled for agent " + std::to_string(task->agent_id)));
        }
        return;
    }

    auto& registry = AgentRegistry::instance();
    auto& guard = SecurityGuard::instance();
    PrivilegeLevel level = registry.get_level(task->agent_id);

    if (!guard.is_code_safe(task->tool_code, level)) {
        std::printf("[SecurityGuard] Agent#%d (%s) code REJECTED | tool=%s\n",
                    task->agent_id, privilege_str(level), task->tool_name.c_str());

        MemoryPage sec_page;
        sec_page.agent_id = task->agent_id;
        sec_page.role = "system";
        sec_page.content = "[System Action] 执行失败，由于安全限制，Ring 3 环境禁止执行涉及 OS 或系统调用的危险代码。";
        memory_mgr_->write_page(sec_page);

        if (response_cb_) {
            nlohmann::json resp_data;
            resp_data["blocked"] = true;
            resp_data["reason"] = "SecurityGuard: dangerous code pattern detected for Ring 3 agent";
            response_cb_(task->client_fd,
                make_response(false,
                    "code blocked by SecurityGuard for agent " + std::to_string(task->agent_id),
                    resp_data.dump()));
        }

        {
            std::lock_guard<std::mutex> lock(active_mutex_);
            auto& vec = active_tasks_[task->agent_id];
            vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
        }
        return;
    }

    auto it = drivers_.find(task->tool_name);
    if (it == drivers_.end()) {
        std::printf("[Dispatch] ERROR: No driver for tool '%s'\n", task->tool_name.c_str());

        MemoryPage page;
        page.agent_id = task->agent_id;
        page.role = "tool";
        page.content = "[ERROR] No driver registered for tool: " + task->tool_name;
        memory_mgr_->write_page(page);

        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "No driver for tool: " + task->tool_name));
        }
        return;
    }

    auto driver = it->second;
    active_io_tasks_.fetch_add(1);

    try {
        io_pool_.submit([this, task, driver]() {
            if (task->cancelled()) {
                std::printf("[Interrupt] Agent=%d TOOL task cancelled, skipping execution\n",
                            task->agent_id);
                if (response_cb_) {
                    response_cb_(task->client_fd,
                        make_response(false, "task cancelled for agent " + std::to_string(task->agent_id)));
                }
                active_io_tasks_.fetch_sub(1);
                return;
            }

            std::printf("[IOPool] Agent#%d executing %s\n", task->agent_id, task->tool_name.c_str());

            std::string output;
            bool timeout = false;
            try {
                output = driver->execute(task->tool_code);
            } catch (const std::exception& e) {
                std::printf("[IOPool] Agent#%d tool execution exception: %s\n",
                            task->agent_id, e.what());
                timeout = true;
            }

            if (!timeout && output.find("[SANDBOX ERROR] Request failed") != std::string::npos) {
                timeout = true;
            }

            if (timeout) {
                std::printf("[IOPool] Agent#%d TIMEOUT | tool=%s | writing system error memory\n",
                            task->agent_id, task->tool_name.c_str());

                MemoryPage err_page;
                err_page.agent_id = task->agent_id;
                err_page.role = "system";
                err_page.content = "[System Error: " + task->tool_name +
                    " execution timeout, killed by kernel watchdog]";
                memory_mgr_->write_page(err_page);

                if (response_cb_) {
                    response_cb_(task->client_fd,
                        make_response(false,
                            task->tool_name + " execution timeout for agent " +
                            std::to_string(task->agent_id)));
                }
                active_io_tasks_.fetch_sub(1);
                return;
            }

            if (task->cancelled()) {
                std::printf("[Interrupt] Agent=%d TOOL task cancelled after execution, discarding\n",
                            task->agent_id);
                active_io_tasks_.fetch_sub(1);
                return;
            }

            std::printf("[IOPool] Agent#%d tool output: \"%s\"\n",
                        task->agent_id,
                        output.size() > 200 ? (output.substr(0, 200) + "...").c_str() : output.c_str());

            MemoryPage page;
            page.agent_id = task->agent_id;
            page.role = "tool";
            page.content = "[Tool: " + task->tool_name + "]\n" + output;
            std::string page_id = memory_mgr_->write_page(page);

            if (llm_->has_embedding_config() && !output.empty()) {
                auto emb = llm_->get_embedding(output);
                if (!emb.empty()) {
                    memory_mgr_->update_embedding(page_id, task->agent_id, std::move(emb));
                }
            }

            if (response_cb_) {
                nlohmann::json resp_data;
                resp_data["tool"] = task->tool_name;
                resp_data["output"] = output;
                response_cb_(task->client_fd,
                    make_response(true,
                        "tool execution completed for agent " + std::to_string(task->agent_id),
                        resp_data.dump()));
            }

            active_io_tasks_.fetch_sub(1);
            std::printf("[IOPool] Agent#%d tool pipeline complete\n", task->agent_id);

            {
                std::lock_guard<std::mutex> lock(active_mutex_);
                auto& vec = active_tasks_[task->agent_id];
                vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
            }
        });
    } catch (const std::exception& e) {
        active_io_tasks_.fetch_sub(1);
        std::printf("[Dispatch] Agent#%d failed to submit to io_pool: %s\n",
                    task->agent_id, e.what());
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "failed to dispatch tool task: " + std::string(e.what())));
        }
    }
}

void TaskScheduler::dispatch_vfs_task(std::shared_ptr<AgentTask> task) {
    std::string action = task->tool_name;
    std::string vfs_path = task->tool_code;
    std::string payload = task->task_payload;

    std::printf("[VFS] Agent#%d | action=%s | path=%s | payload=%zu bytes\n",
                task->agent_id, action.c_str(), vfs_path.c_str(), payload.size());

    if (task->cancelled()) {
        std::printf("[Interrupt] Agent=%d VFS task cancelled\n", task->agent_id);
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "VFS task cancelled for agent " + std::to_string(task->agent_id)));
        }
        return;
    }

    auto& vfs = VfsManager::instance();
    auto& registry = AgentRegistry::instance();
    PrivilegeLevel level = registry.get_level(task->agent_id);

    if (action == "COMPILE_AND_EXECUTE") {
        std::string c_code = payload;
        std::string wasm_func = "_start";
        std::string wasm_stdin;
        std::vector<int32_t> wasm_args;
        int restore_agent_id = -1;
        std::string precompiled_wasm;

        nlohmann::json j;
        if (!payload.empty()) {
            auto parsed = nlohmann::json::parse(payload, nullptr, false);
            if (!parsed.is_discarded() && parsed.is_object()) {
                if (parsed.contains("code") && parsed["code"].is_string()) {
                    c_code = parsed["code"].get<std::string>();
                }
                if (parsed.contains("func") && parsed["func"].is_string()) {
                    wasm_func = parsed["func"].get<std::string>();
                }
                if (parsed.contains("stdin") && parsed["stdin"].is_string()) {
                    wasm_stdin = parsed["stdin"].get<std::string>();
                }
                if (parsed.contains("args") && parsed["args"].is_array()) {
                    for (const auto& arg : parsed["args"]) {
                        if (arg.is_number_integer()) {
                            wasm_args.push_back(arg.get<int32_t>());
                        }
                    }
                }
                if (parsed.contains("restore_agent_id") && parsed["restore_agent_id"].is_number_integer()) {
                    restore_agent_id = parsed["restore_agent_id"].get<int>();
                }
                if (parsed.contains("file") && parsed["file"].is_string()) {
                    precompiled_wasm = parsed["file"].get<std::string>();
                }
            }
        }

        std::printf("[VFS-COMPILE] Agent#%d | C source: %zu bytes | func=%s | stdin=%zu bytes\n",
                    task->agent_id, c_code.size(), wasm_func.c_str(), wasm_stdin.size());

        active_io_tasks_.fetch_add(1);
        active_wasm_vms_.fetch_add(1);
        {
            std::lock_guard<std::mutex> lock(queue_mutex_);
            if (swapped_out_agents_.find(task->agent_id) == swapped_out_agents_.end()) {
                swapped_out_agents_[task->agent_id] = false;
            }
        }
        try {
            wasm_pool_->submit([this, task, c_code = std::move(c_code),
                             wasm_func = std::move(wasm_func),
                             wasm_args = std::move(wasm_args),
                             wasm_stdin = std::move(wasm_stdin),
                             restore_agent_id,
                             precompiled_wasm = std::move(precompiled_wasm)]() mutable {
                bool need_swap_in = false;
                {
                    std::lock_guard<std::mutex> lock(queue_mutex_);
                    auto it = swapped_out_agents_.find(task->agent_id);
                    if (it != swapped_out_agents_.end() && it->second) {
                        need_swap_in = true;
                    }
                }

                if (need_swap_in) {
                    std::printf("[Ring 0 | MMU] 触发缺页中断 (Page Fault)！正在将 Agent %d 从 Swap 分区拉回物理内存...\n",
                                task->agent_id);
                    restore_agent_id = task->agent_id;
                    total_page_faults_.fetch_add(1);
                    {
                        std::lock_guard<std::mutex> lock(queue_mutex_);
                        swapped_out_agents_[task->agent_id] = false;
                    }
                }

                CompileResult compile_res;
                if (!precompiled_wasm.empty()) {
                    compile_res.success = true;
                    compile_res.wasm_path = precompiled_wasm;
                    compile_res.exit_code = 0;
                    std::printf("[VFS-COMPILE] Agent#%d | Using precompiled WASM: %s\n",
                                task->agent_id, precompiled_wasm.c_str());
                } else {
                    compile_res = CompilerBridge::CompileToWasm(
                        task->agent_id, c_code);
                }

                if (!compile_res.success) {
                    std::printf("[VFS-COMPILE] Agent#%d | Compile FAILED: %s\n",
                                task->agent_id, compile_res.error_msg.c_str());

                    if (response_cb_) {
                        nlohmann::json resp_data;
                        resp_data["stage"] = "compile";
                        resp_data["success"] = false;
                        resp_data["error"] = compile_res.error_msg;
                        resp_data["exit_code"] = compile_res.exit_code;
                        response_cb_(task->client_fd,
                            make_response(false, "Compilation failed", resp_data.dump()));
                    }
                    active_io_tasks_.fetch_sub(1);
                    active_wasm_vms_.fetch_sub(1);

                    {
                        std::lock_guard<std::mutex> lock(active_mutex_);
                        auto& vec = active_tasks_[task->agent_id];
                        vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
                    }
                    return;
                }

                std::printf("[VFS-COMPILE] Agent#%d | Compile OK -> %s\n",
                            task->agent_id, compile_res.wasm_path.c_str());

                std::string mount_path = "/bin/wasm_agent_" + std::to_string(task->agent_id);
                auto& vfs = VfsManager::instance();
                auto existing = vfs.resolve_path(mount_path);

                std::shared_ptr<WasmNode> wasm_node;
                if (existing && existing->node_type() == VfsNodeType::WASM) {
                    wasm_node = std::dynamic_pointer_cast<WasmNode>(existing);
                    if (wasm_node) {
                        wasm_node->set_wasm_file_path(compile_res.wasm_path);
                        std::printf("[VFS-COMPILE] Agent#%d | Reusing WasmNode: %s -> %s\n",
                                    task->agent_id, mount_path.c_str(), compile_res.wasm_path.c_str());
                    }
                }

                if (!wasm_node) {
                    wasm_node = std::make_shared<WasmNode>(mount_path, compile_res.wasm_path);
                    vfs.mount("/bin", "wasm_agent_" + std::to_string(task->agent_id), wasm_node);
                    std::printf("[VFS-COMPILE] Agent#%d | Dynamic WasmNode mounted: %s\n",
                                task->agent_id, mount_path.c_str());
                }

                nlohmann::json exec_payload;
                exec_payload["file"] = compile_res.wasm_path;
                exec_payload["func"] = wasm_func;
                if (!wasm_args.empty()) {
                    exec_payload["args"] = wasm_args;
                }
                if (!wasm_stdin.empty()) {
                    exec_payload["stdin"] = wasm_stdin;
                }
                if (restore_agent_id > 0) {
                    exec_payload["restore_agent_id"] = restore_agent_id;
                }

                std::string output = wasm_node->execute(exec_payload.dump());

                std::printf("[VFS-COMPILE] Agent#%d | Execute result: %s\n",
                            task->agent_id, output.c_str());

                if (response_cb_) {
                    nlohmann::json resp_data;
                    resp_data["stage"] = "complete";
                    resp_data["compile"] = true;
                    resp_data["wasm_path"] = compile_res.wasm_path;
                    resp_data["mount"] = mount_path;
                    resp_data["output"] = output;
                    response_cb_(task->client_fd,
                        make_response(true, "Compile & Execute completed", resp_data.dump()));
                }

                active_io_tasks_.fetch_sub(1);
                active_wasm_vms_.fetch_sub(1);

                {
                    std::lock_guard<std::mutex> lock(active_mutex_);
                    auto& vec = active_tasks_[task->agent_id];
                    vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
                }
            });
        } catch (const std::exception& e) {
            active_io_tasks_.fetch_sub(1);
            active_wasm_vms_.fetch_sub(1);
            std::printf("[VFS-COMPILE] Agent#%d dispatch failed: %s\n",
                        task->agent_id, e.what());
            if (response_cb_) {
                response_cb_(task->client_fd,
                    make_response(false, "COMPILE_AND_EXECUTE dispatch failed: " + std::string(e.what())));
            }
        }
        return;
    }

    auto node = vfs.resolve_path(vfs_path);

    if (!node) {
        node = vfs.resolve_or_create_mem(vfs_path, memory_mgr_);
    }

    if (!node) {
        std::printf("[VFS] Path not found: %s\n", vfs_path.c_str());
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "VFS path not found: " + vfs_path));
        }
        {
            std::lock_guard<std::mutex> lock(active_mutex_);
            auto& vec = active_tasks_[task->agent_id];
            vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
        }
        return;
    }

    if (action == "READ") {
        if (node->node_type() == VfsNodeType::PIPE) {
            auto pipe = std::dynamic_pointer_cast<PipeNode>(node);
            if (!pipe) {
                if (response_cb_) {
                    response_cb_(task->client_fd,
                        make_response(false, "VFS path is not a valid pipe: " + vfs_path));
                }
                {
                    std::lock_guard<std::mutex> lock(active_mutex_);
                    auto& vec = active_tasks_[task->agent_id];
                    vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
                }
                return;
            }

            active_io_tasks_.fetch_add(1);
            try {
                io_pool_.submit([this, task, pipe, vfs_path]() {
                    std::printf("[Pipe-IOPool] Agent#%d blocking READ on %s\n",
                                task->agent_id, vfs_path.c_str());

                    std::string data = pipe->read_blocking();

                    std::printf("[Pipe-IOPool] Agent#%d received from %s: \"%s\"\n",
                                task->agent_id, vfs_path.c_str(),
                                data.size() > 80 ? (data.substr(0, 80) + "...").c_str() : data.c_str());

                    MemoryPage page;
                    page.agent_id = task->agent_id;
                    page.role = "pipe";
                    page.content = "[Pipe: " + vfs_path + "] " + data;
                    memory_mgr_->write_page(page);

                    if (response_cb_) {
                        nlohmann::json resp_data;
                        resp_data["path"] = vfs_path;
                        resp_data["content"] = data;
                        resp_data["type"] = "PIPE";
                        response_cb_(task->client_fd,
                            make_response(true, "VFS PIPE READ completed", resp_data.dump()));
                    }

                    active_io_tasks_.fetch_sub(1);

                    {
                        std::lock_guard<std::mutex> lock(active_mutex_);
                        auto& vec = active_tasks_[task->agent_id];
                        vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
                    }
                });
            } catch (const std::exception& e) {
                active_io_tasks_.fetch_sub(1);
                std::printf("[VFS] Agent#%d failed to submit pipe read: %s\n",
                            task->agent_id, e.what());
                if (response_cb_) {
                    response_cb_(task->client_fd,
                        make_response(false, "pipe read dispatch failed: " + std::string(e.what())));
                }
            }
            return;
        }

        if (node->node_type() == VfsNodeType::DEVICE) {
            auto mem_dev = std::dynamic_pointer_cast<MemoryDeviceNode>(node);
            if (mem_dev && !registry.can_access(task->agent_id, mem_dev->agent_id())) {
                std::printf("[Security] BLOCKED | Agent#%d (%s) -> VFS READ /dev/mem/%d | Cross-agent memory access denied\n",
                            task->agent_id, privilege_str(level), mem_dev->agent_id());
                if (response_cb_) {
                    nlohmann::json err;
                    err["status"] = "error";
                    err["message"] = "[Security Fault] Ring 3 Agent 无权越权访问";
                    response_cb_(task->client_fd, err.dump() + "\n");
                }
                {
                    std::lock_guard<std::mutex> lock(active_mutex_);
                    auto& vec = active_tasks_[task->agent_id];
                    vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
                }
                return;
            }
        }

        std::string content = node->read();
        std::printf("[VFS] READ %s -> %zu bytes\n", vfs_path.c_str(), content.size());
        if (response_cb_) {
            nlohmann::json resp_data;
            resp_data["path"] = vfs_path;
            resp_data["content"] = content;
            resp_data["type"] = node_type_str(node->node_type());
            response_cb_(task->client_fd,
                make_response(true, "VFS READ completed", resp_data.dump()));
        }
    } else if (action == "WRITE") {
        if (node->node_type() == VfsNodeType::PIPE) {
            auto pipe = std::dynamic_pointer_cast<PipeNode>(node);
            if (pipe) {
                bool ok = pipe->write(payload);
                if (response_cb_) {
                    nlohmann::json resp_data;
                    resp_data["path"] = vfs_path;
                    resp_data["written"] = ok;
                    resp_data["type"] = "PIPE";
                    response_cb_(task->client_fd,
                        make_response(ok, ok ? "VFS PIPE WRITE completed (receiver notified)" : "VFS PIPE WRITE failed", resp_data.dump()));
                }
                {
                    std::lock_guard<std::mutex> lock(active_mutex_);
                    auto& vec = active_tasks_[task->agent_id];
                    vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
                }
                return;
            }
        }

        bool ok = node->write(payload);
        std::printf("[VFS] WRITE %s -> %s\n", vfs_path.c_str(), ok ? "ok" : "failed");
        if (response_cb_) {
            nlohmann::json resp_data;
            resp_data["path"] = vfs_path;
            resp_data["written"] = ok;
            response_cb_(task->client_fd,
                make_response(ok, ok ? "VFS WRITE completed" : "VFS WRITE failed", resp_data.dump()));
        }
    } else if (action == "EXECUTE") {
        if (node->node_type() != VfsNodeType::EXECUTABLE &&
            node->node_type() != VfsNodeType::WASM) {
            std::printf("[VFS] EXECUTE failed: %s is not executable [%s]\n",
                        vfs_path.c_str(), node_type_str(node->node_type()));
            if (response_cb_) {
                response_cb_(task->client_fd,
                    make_response(false, "VFS node is not executable: " + vfs_path));
            }
        } else {
            if (node->node_type() == VfsNodeType::WASM) {
                active_io_tasks_.fetch_add(1);
                active_wasm_vms_.fetch_add(1);
                {
                    std::lock_guard<std::mutex> lock(queue_mutex_);
                    if (swapped_out_agents_.find(task->agent_id) == swapped_out_agents_.end()) {
                        swapped_out_agents_[task->agent_id] = false;
                    }
                }
                try {
                    wasm_pool_->submit([this, task, node, payload, vfs_path]() {
                        std::printf("[WasmPool] Executing %s for Agent#%d\n",
                                    vfs_path.c_str(), task->agent_id);

                        std::string output = node->execute(payload);

                        std::printf("[WasmPool] %s output: \"%s\"\n",
                                    vfs_path.c_str(), output.c_str());

                        if (response_cb_) {
                            nlohmann::json resp_data;
                            resp_data["path"] = vfs_path;
                            resp_data["output"] = output;
                            response_cb_(task->client_fd,
                                make_response(true, "WASM execution completed", resp_data.dump()));
                        }

                        active_io_tasks_.fetch_sub(1);
                        active_wasm_vms_.fetch_sub(1);
                    });
                } catch (...) {
                    active_io_tasks_.fetch_sub(1);
                    active_wasm_vms_.fetch_sub(1);
                }
                return;
            }

            auto& guard = SecurityGuard::instance();

            if (!guard.is_code_safe(payload, level)) {
                std::printf("[VFS+SecurityGuard] Agent#%d (%s) code REJECTED at %s\n",
                            task->agent_id, privilege_str(level), vfs_path.c_str());

                MemoryPage sec_page;
                sec_page.agent_id = task->agent_id;
                sec_page.role = "system";
                sec_page.content = "[System Action] 执行失败，由于安全限制，Ring 3 环境禁止执行涉及 OS 或系统调用的危险代码。";
                memory_mgr_->write_page(sec_page);

                if (response_cb_) {
                    nlohmann::json resp_data;
                    resp_data["blocked"] = true;
                    resp_data["reason"] = "SecurityGuard: dangerous code pattern detected for Ring 3 agent";
                    response_cb_(task->client_fd,
                        make_response(false, "VFS EXECUTE blocked by SecurityGuard", resp_data.dump()));
                }
            } else {
                active_io_tasks_.fetch_add(1);
                try {
                    io_pool_.submit([this, task, node, payload, vfs_path]() {
                        std::printf("[VFS-IOPool] Executing %s for Agent#%d\n",
                                    vfs_path.c_str(), task->agent_id);

                        std::string output = node->execute(payload);

                        std::printf("[VFS-IOPool] %s output: \"%s\"\n",
                                    vfs_path.c_str(),
                                    output.size() > 200 ? (output.substr(0, 200) + "...").c_str() : output.c_str());

                        MemoryPage page;
                        page.agent_id = task->agent_id;
                        page.role = "tool";
                        page.content = "[VFS: " + vfs_path + "]\n" + output;
                        std::string page_id = memory_mgr_->write_page(page);

                        if (llm_->has_embedding_config() && !output.empty()) {
                            auto emb = llm_->get_embedding(output);
                            if (!emb.empty()) {
                                memory_mgr_->update_embedding(page_id, task->agent_id, std::move(emb));
                            }
                        }

                        if (response_cb_) {
                            nlohmann::json resp_data;
                            resp_data["path"] = vfs_path;
                            resp_data["output"] = output;
                            response_cb_(task->client_fd,
                                make_response(true, "VFS EXECUTE completed", resp_data.dump()));
                        }

                        active_io_tasks_.fetch_sub(1);

                        {
                            std::lock_guard<std::mutex> lock(active_mutex_);
                            auto& vec = active_tasks_[task->agent_id];
                            vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
                        }
                    });
                } catch (const std::exception& e) {
                    active_io_tasks_.fetch_sub(1);
                    std::printf("[VFS] Agent#%d failed to submit to io_pool: %s\n",
                                task->agent_id, e.what());
                    if (response_cb_) {
                        response_cb_(task->client_fd,
                            make_response(false, "VFS EXECUTE dispatch failed: " + std::string(e.what())));
                    }
                }
                return;
            }
        }
    } else {
        std::printf("[VFS] Unknown action: %s\n", action.c_str());
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "Unknown VFS action: " + action));
        }
    }

    {
        std::lock_guard<std::mutex> lock(active_mutex_);
        auto& vec = active_tasks_[task->agent_id];
        vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
    }
}

void TaskScheduler::dispatch_process_ctrl(std::shared_ptr<AgentTask> task) {
    std::string action = task->tool_name;
    int agent_id = task->agent_id;

    std::printf("[ProcessCtrl] Agent#%d | action=%s\n", agent_id, action.c_str());

    if (action == "SNAPSHOT") {
        std::string filepath = "./snapshots/agent_" + std::to_string(agent_id) + ".snapshot.json";
        bool ok = memory_mgr_->create_snapshot(agent_id, filepath);

        if (response_cb_) {
            nlohmann::json resp_data;
            resp_data["action"] = "SNAPSHOT";
            resp_data["agent_id"] = agent_id;
            resp_data["filepath"] = filepath;
            response_cb_(task->client_fd,
                make_response(ok,
                    ok ? "Agent#" + std::to_string(agent_id) + " snapshot created"
                       : "Agent#" + std::to_string(agent_id) + " snapshot failed",
                    resp_data.dump()));
        }
    } else if (action == "RESTORE") {
        std::string filepath = "./snapshots/agent_" + std::to_string(agent_id) + ".snapshot.json";
        bool ok = memory_mgr_->restore_snapshot(agent_id, filepath);

        if (response_cb_) {
            nlohmann::json resp_data;
            resp_data["action"] = "RESTORE";
            resp_data["agent_id"] = agent_id;
            resp_data["filepath"] = filepath;
            response_cb_(task->client_fd,
                make_response(ok,
                    ok ? "Agent#" + std::to_string(agent_id) + " restored from snapshot"
                       : "Agent#" + std::to_string(agent_id) + " restore failed",
                    resp_data.dump()));
        }
    } else if (action == "PURGE") {
        bool ok = memory_mgr_->purge_agent(agent_id);

        if (response_cb_) {
            nlohmann::json resp_data;
            resp_data["action"] = "PURGE";
            resp_data["agent_id"] = agent_id;
            response_cb_(task->client_fd,
                make_response(ok,
                    ok ? "Agent#" + std::to_string(agent_id) + " memory purged"
                       : "Agent#" + std::to_string(agent_id) + " purge failed",
                    resp_data.dump()));
        }
    } else {
        std::printf("[ProcessCtrl] Unknown action: %s\n", action.c_str());
        if (response_cb_) {
            response_cb_(task->client_fd,
                make_response(false, "Unknown PROCESS_CTRL action: " + action));
        }
    }

    {
        std::lock_guard<std::mutex> lock(active_mutex_);
        auto& vec = active_tasks_[task->agent_id];
        vec.erase(std::remove(vec.begin(), vec.end(), task), vec.end());
    }
}

} // namespace aios
