#pragma once

#include "aios/llm_adapter.h"
#include "aios/vfs_node.h"

#include <mutex>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

namespace aios {

struct Edge {
    std::string relation;
    std::string target;
};

struct GraphEntity {
    std::string name;
    std::vector<Edge> outgoing;
};

class GraphNode : public VfsNode {
public:
    explicit GraphNode(const std::string& path, std::shared_ptr<LlmAdapter> llm = nullptr,
                       int owner_uid = 0, int permissions = 0644);

    bool write(const std::string& data) override;
    bool write_as(const std::string& data, int caller_uid);
    std::string read() const override;
    std::string read_as(int caller_uid) const;

    std::string query_subgraph(const std::string& entity, int depth = 2);

    size_t entity_count() const;
    size_t edge_count() const;

private:
    void extract_triples(const std::string& text);
    void insert_triple(const std::string& subject, const std::string& relation, const std::string& object);
    GraphEntity* get_or_create_entity(const std::string& name);

    std::string serialize_graph() const;

    std::shared_ptr<LlmAdapter> llm_;
    std::unordered_map<std::string, GraphEntity> entities_;
    mutable std::mutex mutex_;
};

} // namespace aios
