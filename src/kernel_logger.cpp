#include "aios/kernel_logger.h"

#include <chrono>
#include <cstdio>
#include <iomanip>
#include <sstream>

namespace aios {

void KernelLogger::log(const std::string& msg) {
    auto now = std::chrono::system_clock::now();
    auto time_t_now = std::chrono::system_clock::to_time_t(now);
    auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(
        now.time_since_epoch()) % 1000;

    std::ostringstream oss;
    oss << std::put_time(std::localtime(&time_t_now), "%Y-%m-%d %H:%M:%S")
        << "." << std::setfill('0') << std::setw(3) << ms.count()
        << " " << msg;

    std::string formatted = oss.str();

    std::printf("%s\n", formatted.c_str());

    std::lock_guard<std::mutex> lock(mutex_);
    log_buffer_.push_back(std::move(formatted));
    while (log_buffer_.size() > MAX_LOG_ENTRIES) {
        log_buffer_.pop_front();
    }
}

std::string KernelLogger::dump_logs() {
    std::lock_guard<std::mutex> lock(mutex_);
    std::string result;
    for (const auto& entry : log_buffer_) {
        result += entry + "\n";
    }
    return result;
}

} // namespace aios
