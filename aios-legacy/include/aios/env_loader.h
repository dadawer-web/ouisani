#pragma once

#include <string>
#include <unordered_map>

namespace aios {

class EnvLoader {
public:
    static std::unordered_map<std::string, std::string> load(const std::string& path);

    static std::string get(const std::unordered_map<std::string, std::string>& env,
                           const std::string& key,
                           const std::string& default_val = "");
};

} // namespace aios
