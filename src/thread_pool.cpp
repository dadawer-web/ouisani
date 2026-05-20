#include "aios/thread_pool.h"

#include <cstdio>

namespace aios {

ThreadPool::ThreadPool(size_t num_threads)
    : num_threads_(num_threads > 0 ? num_threads : 1)
{}

ThreadPool::~ThreadPool() {
    shutdown();
}

void ThreadPool::start() {
    bool expected = false;
    if (!running_.compare_exchange_strong(expected, true)) {
        return;
    }

    for (size_t i = 0; i < num_threads_; ++i) {
        workers_.emplace_back(&ThreadPool::worker_loop, this);
    }

    std::printf("[ThreadPool] Started with %zu workers\n", num_threads_);
}

void ThreadPool::shutdown() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!running_.load()) return;
        running_.store(false);
    }
    cv_.notify_all();

    for (auto& w : workers_) {
        if (w.joinable()) {
            w.join();
        }
    }
    workers_.clear();

    while (!tasks_.empty()) {
        tasks_.pop();
    }

    std::printf("[ThreadPool] Shutdown complete (%zu)\n", num_threads_);
}

void ThreadPool::wait_all() {
    std::unique_lock<std::mutex> lock(mutex_);
    done_cv_.wait(lock, [this] {
        return tasks_.empty() && active_count_.load() == 0;
    });
}

size_t ThreadPool::pending_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return tasks_.size();
}

size_t ThreadPool::worker_count() const {
    return num_threads_;
}

bool ThreadPool::is_running() const {
    return running_.load();
}

void ThreadPool::worker_loop() {
    while (true) {
        std::function<void()> task;

        {
            std::unique_lock<std::mutex> lock(mutex_);
            cv_.wait(lock, [this] {
                return !tasks_.empty() || !running_.load();
            });

            if (!running_.load() && tasks_.empty()) {
                return;
            }

            if (!tasks_.empty()) {
                task = std::move(tasks_.front());
                tasks_.pop();
            }
        }

        if (task) {
            task();
            --active_count_;
            done_cv_.notify_all();
        }
    }
}

} // namespace aios
