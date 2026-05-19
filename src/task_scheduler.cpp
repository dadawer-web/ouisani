#include "aios/task_scheduler.h"

#include <chrono>
#include <cstdio>
#include <sstream>

namespace aios {

TaskScheduler::TaskScheduler(size_t worker_count,
                             std::shared_ptr<LlmAdapter> llm,
                             std::shared_ptr<MemoryManager> memory_mgr)
    : worker_count_(worker_count > 0 ? worker_count : 1)
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
        ready_queue_.push(std::move(task));
    }
    queue_cv_.notify_one();
}

void TaskScheduler::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        return;
    }

    for (size_t i = 0; i < worker_count_; ++i) {
        workers_.emplace_back(&TaskScheduler::worker_loop, this);
    }

    std::printf("[Scheduler] Started with %zu worker threads (LLM pipeline enabled)\n", worker_count_);
}

void TaskScheduler::shutdown() {
    {
        std::lock_guard<std::mutex> lock(queue_mutex_);
        if (!running_.load()) return;
        running_.store(false);
    }
    queue_cv_.notify_all();

    for (auto& w : workers_) {
        if (w.joinable()) {
            w.join();
        }
    }
    workers_.clear();

    std::printf("[Scheduler] Shutdown complete. Pending tasks discarded: %zu\n",
                ready_queue_.size());

    while (!ready_queue_.empty()) {
        ready_queue_.pop();
    }
}

std::string TaskScheduler::build_prompt(int agent_id, const std::string& current_payload) {
    auto pages = memory_mgr_->read_pages(agent_id);

    std::ostringstream oss;

    if (!pages.empty()) {
        oss << "[Conversation History]\n";
        for (const auto& p : pages) {
            oss << p.role << ": " << p.content << "\n";
        }
        oss << "\n";
    }

    oss << "[Current Request]\n";
    oss << "user: " << current_payload << "\n";

    return oss.str();
}

void TaskScheduler::worker_loop() {
    size_t worker_id = std::hash<std::thread::id>{}(std::this_thread::get_id()) % worker_count_;

    while (true) {
        std::shared_ptr<AgentTask> task;

        {
            std::unique_lock<std::mutex> lock(queue_mutex_);
            queue_cv_.wait(lock, [this] {
                return !ready_queue_.empty() || !running_.load();
            });

            if (!running_.load() && ready_queue_.empty()) {
                return;
            }

            if (!ready_queue_.empty()) {
                task = ready_queue_.top();
                ready_queue_.pop();
            }
        }

        if (!task) continue;

        task->status = TaskStatus::RUNNING;
        active_tasks_.fetch_add(1);

        std::printf("\n[Worker %zu] ========== Agent#%d (priority=%d) PIPELINE START ==========\n",
                    worker_id, task->agent_id, task->priority);

        std::printf("[Worker %zu] Step 1: Context Injection | Reading memory for Agent#%d\n",
                    worker_id, task->agent_id);

        std::printf("[Worker %zu] Step 2: Prompt Formatting | Assembling context + payload\n",
                    worker_id);
        std::string prompt = build_prompt(task->agent_id, task->task_payload);

        std::printf("[Worker %zu] Step 3: LLM Execution | Sending to %s (model=%s)\n",
                    worker_id, llm_->base_url().c_str(), llm_->model().c_str());

        std::string llm_response = llm_->generate(prompt);

        std::printf("[Worker %zu] Step 4: Memory Update | Writing assistant response to Agent#%d memory\n",
                    worker_id, task->agent_id);

        MemoryPage assistant_page;
        assistant_page.agent_id = task->agent_id;
        assistant_page.role = "assistant";
        assistant_page.content = llm_response;
        memory_mgr_->write_page(assistant_page);

        task->status = TaskStatus::SUSPENDED;

        std::printf("[Worker %zu] ========== Agent#%d PIPELINE COMPLETE ==========\n",
                    worker_id, task->agent_id);
        std::printf("[Worker %zu] Agent#%d LLM Response: \"%s\"\n",
                    worker_id, task->agent_id,
                    llm_response.size() > 200 ? (llm_response.substr(0, 200) + "...").c_str() : llm_response.c_str());

        active_tasks_.fetch_sub(1);
    }
}

size_t TaskScheduler::pending_count() const {
    std::lock_guard<std::mutex> lock(queue_mutex_);
    return ready_queue_.size();
}

} // namespace aios
