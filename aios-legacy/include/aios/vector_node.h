#pragma once

#include "aios/llm_adapter.h"
#include "aios/vfs_node.h"
#include "aios/vector_math.h"

#include <mutex>
#include <string>
#include <vector>

namespace aios {

struct MemoryEntry {
    std::string text;
    std::vector<float> embedding;
};

class VectorNode : public VfsNode {
public:
    explicit VectorNode(const std::string& path, std::shared_ptr<LlmAdapter> llm = nullptr,
                        int owner_uid = 0, int permissions = 0644);

    bool write(const std::string& data) override;
    bool write_as(const std::string& data, int caller_uid);
    std::string read() const override;
    std::string read_as(int caller_uid) const;

    std::string search(const std::string& query, int top_k = 3);

private:
    std::vector<float> generate_embedding(const std::string& text) const;
    std::vector<float> mock_embedding(const std::string& text) const;

    std::shared_ptr<LlmAdapter> llm_;
    std::vector<MemoryEntry> memories_;
    mutable std::mutex mutex_;

    static constexpr int MOCK_DIM = 128;
};

} // namespace aios
