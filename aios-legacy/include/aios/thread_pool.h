#pragma once

#include <atomic>
#include <condition_variable>
#include <functional>
#include <future>
#include <mutex>
#include <queue>
#include <thread>
#include <vector>

namespace aios {

class ThreadPool {
public:
    explicit ThreadPool(size_t num_threads);
    ~ThreadPool();

    ThreadPool(const ThreadPool&) = delete;
    ThreadPool& operator=(const ThreadPool&) = delete;

    void start();
    void shutdown();
    void wait_all();

    template<typename F>
    auto submit(F&& f) -> std::future<decltype(f())> {
        using ReturnType = decltype(f());
        auto task = std::make_shared<std::packaged_task<ReturnType()>>(
            std::forward<F>(f));
        auto future = task->get_future();
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (!running_.load()) {
                throw std::runtime_error("ThreadPool: submit on stopped pool");
            }
            tasks_.push([task]() { (*task)(); });
            ++active_count_;
        }
        cv_.notify_one();
        return future;
    }

    size_t pending_count() const;
    size_t worker_count() const;
    bool is_running() const;

private:
    void worker_loop();

    std::vector<std::thread> workers_;
    std::queue<std::function<void()>> tasks_;
    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::condition_variable done_cv_;
    std::atomic<bool> running_{false};
    std::atomic<size_t> active_count_{0};
    size_t num_threads_;
};

} // namespace aios
