#pragma once

#include <cstdio>
#include <mutex>
#include <sstream>
#include <string>

namespace aios {

struct FlatCommand {
    std::string action;
    std::string arg;
    bool valid = false;
};

class InstructionDecoder {
public:
    static InstructionDecoder& GetInstance() {
        static InstructionDecoder instance;
        return instance;
    }

    bool Initialize(const std::string& uds_path = "/tmp/aios_decoder.sock");

    std::string Decode(const std::string& user_intent);

    static FlatCommand ParseFlatCommand(const std::string& flat);

    bool is_ready() const { return ready_; }

    ~InstructionDecoder() = default;

private:
    InstructionDecoder() = default;
    InstructionDecoder(const InstructionDecoder&) = delete;
    InstructionDecoder& operator=(const InstructionDecoder&) = delete;

    std::string uds_path_;
    bool ready_ = false;
    std::mutex decode_mutex_;
};

} // namespace aios
