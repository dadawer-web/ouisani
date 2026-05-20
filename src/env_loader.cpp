#include "aios/env_loader.h"

#include <cstdio>
#include <fstream>
#include <sstream>

namespace aios {

std::unordered_map<std::string, std::string> EnvLoader::load(const std::string& path) {
    std::unordered_map<std::string, std::string> env;
    std::ifstream ifs(path);
    if (!ifs.is_open()) {
        std::printf("[EnvLoader] .env file not found: %s\n", path.c_str());
        return env;
    }

    std::string line;
    while (std::getline(ifs, line)) {
        if (line.empty() || line[0] == '#') continue;

        auto eq_pos = line.find('=');
        if (eq_pos == std::string::npos) continue;

        std::string key = line.substr(0, eq_pos);
        std::string val = line.substr(eq_pos + 1);

        while (!key.empty() && (key.back() == ' ' || key.back() == '\t'))
            key.pop_back();
        while (!val.empty() && (val.front() == ' ' || val.front() == '\t'))
            val.erase(val.begin());
        while (!val.empty() && (val.back() == ' ' || val.back() == '\t' || val.back() == '\r'))
            val.pop_back();

        if (val.size() >= 2 && ((val.front() == '"' && val.back() == '"') ||
                                 (val.front() == '\'' && val.back() == '\''))) {
            val = val.substr(1, val.size() - 2);
        }

        env[key] = val;
    }

    std::printf("[EnvLoader] Loaded %zu variables from %s\n", env.size(), path.c_str());
    return env;
}

std::string EnvLoader::get(const std::unordered_map<std::string, std::string>& env,
                           const std::string& key,
                           const std::string& default_val) {
    auto it = env.find(key);
    if (it != env.end()) return it->second;
    return default_val;
}

} // namespace aios
