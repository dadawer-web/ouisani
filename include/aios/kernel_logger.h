#pragma once

#include <deque>
#include <mutex>
#include <string>

namespace aios {

class KernelLogger {
public:
    static KernelLogger& instance() {
        static KernelLogger inst;
        return inst;
    }

    void log(const std::string& msg);

    std::string dump_logs();

private:
    KernelLogger() = default;
    KernelLogger(const KernelLogger&) = delete;
    KernelLogger& operator=(const KernelLogger&) = delete;

    std::deque<std::string> log_buffer_;
    std::mutex mutex_;
    static constexpr size_t MAX_LOG_ENTRIES = 1000;
};

} // namespace aios
