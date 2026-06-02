#pragma once

#include <string>

namespace aios {

class DeviceDriver {
public:
    virtual ~DeviceDriver() = default;
    virtual std::string execute(const std::string& command_payload) = 0;
    virtual std::string name() const = 0;
};

} // namespace aios
