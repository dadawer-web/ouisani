#pragma once

#include "aios/device_driver.h"

#include <future>
#include <string>

namespace aios {

class SandboxDriver : public DeviceDriver {
public:
    explicit SandboxDriver(const std::string& sandbox_url = "http://127.0.0.1:5000",
                           int timeout_seconds = 30);

    std::string execute(const std::string& code) override;
    std::string name() const override { return "python_sandbox"; }

    std::future<std::string> execute_async(const std::string& code);

private:
    std::string sandbox_url_;
    int timeout_seconds_;
};

} // namespace aios
