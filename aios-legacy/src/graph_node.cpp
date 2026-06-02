#include "aios/graph_node.h"

#include "aios/event_bus.h"

#include <algorithm>
#include <cstdio>
#include <queue>
#include <regex>
#include <sstream>
#include <unordered_set>

#include <nlohmann/json.hpp>

namespace aios {

GraphNode::GraphNode(const std::string& path, std::shared_ptr<LlmAdapter> llm,
                     int owner_uid, int permissions)
    : VfsNode(VfsNodeType::GRAPH, path, owner_uid, permissions), llm_(std::move(llm)) {}

GraphEntity* GraphNode::get_or_create_entity(const std::string& name) {
    auto it = entities_.find(name);
    if (it != entities_.end()) return &it->second;
    auto& ent = entities_[name];
    ent.name = name;
    return &ent;
}

void GraphNode::insert_triple(const std::string& subject, const std::string& relation,
                              const std::string& object) {
    auto* src = get_or_create_entity(subject);
    get_or_create_entity(object);

    for (const auto& e : src->outgoing) {
        if (e.relation == relation && e.target == object) return;
    }

    src->outgoing.push_back({relation, object});

    std::printf("[GraphNode] +TRIPLE [%s | %s | %s] | entities=%zu\n",
                subject.c_str(), relation.c_str(), object.c_str(), entities_.size());
}

void GraphNode::extract_triples(const std::string& text) {
    if (!llm_ || !llm_->has_api_key()) {
        std::printf("[GraphNode] No LLM available, skipping triple extraction\n");
        return;
    }

    static const char* kExtractPrompt =
        "You are a knowledge graph construction engine. Your task is to extract core knowledge triples "
        "from the given text. Each triple must follow the exact format: [Subject|Relation|Object]\n\n"
        "Rules:\n"
        "1. Extract ONLY factual, concrete relationships from the text.\n"
        "2. Subject and Object must be specific entities (people, organizations, technologies, concepts, etc.).\n"
        "3. Relation must be a short verb or noun phrase describing the relationship.\n"
        "4. Output ONLY the triples, one per line. No explanations, no markdown, no extra text.\n"
        "5. Example output:\n"
        "[Spring WebFlux|is_framework_for|reactive_programming]\n"
        "[Redis Streams|provides|distributed_event_sourcing]\n\n"
        "Text to analyze:\n";

    std::string full_prompt = kExtractPrompt + text;

    std::string response;
    try {
        response = llm_->generate("", full_prompt);
    } catch (const std::exception& e) {
        std::fprintf(stderr, "[GraphNode] LLM triple extraction failed: %s\n", e.what());
        return;
    }

    if (response.empty()) {
        std::printf("[GraphNode] LLM returned empty response for triple extraction\n");
        return;
    }

    std::regex triple_re(R"(\[([^\]|]+)\|([^\]|]+)\|([^\]|]+)\])");
    auto begin = std::sregex_iterator(response.begin(), response.end(), triple_re);
    auto end = std::sregex_iterator();

    int count = 0;
    for (auto it = begin; it != end; ++it) {
        std::string subject = (*it)[1].str();
        std::string relation = (*it)[2].str();
        std::string object = (*it)[3].str();

        auto trim = [](std::string& s) {
            size_t start = s.find_first_not_of(" \t\r\n");
            size_t end_pos = s.find_last_not_of(" \t\r\n");
            if (start == std::string::npos) { s.clear(); return; }
            s = s.substr(start, end_pos - start + 1);
        };
        trim(subject);
        trim(relation);
        trim(object);

        if (subject.empty() || relation.empty() || object.empty()) continue;

        insert_triple(subject, relation, object);
        count++;
    }

    std::printf("[GraphNode] Extracted %d triples from %zu-char text\n", count, text.size());
}

bool GraphNode::write(const std::string& data) {
    return write_as(data, 0);
}

bool GraphNode::write_as(const std::string& data, int caller_uid) {
    if (data.empty()) return false;

    if (!check_write(caller_uid)) {
        std::printf("[GraphNode] WRITE DENIED %s | uid=%d\n", path_.c_str(), caller_uid);
        return false;
    }

    std::printf("[GraphNode] WRITE %s | uid=%d | text_len=%zu | extracting triples...\n",
                path_.c_str(), caller_uid, data.size());

    extract_triples(data);

    EventBus::instance().publish(EventType::VFS_WRITE, "GraphNode",
        path_ + " | entities=" + std::to_string(entities_.size()));

    return true;
}

std::string GraphNode::read() const {
    return read_as(0);
}

std::string GraphNode::read_as(int caller_uid) const {
    if (!check_read(caller_uid)) {
        return "[PermissionDenied] uid " + std::to_string(caller_uid) + " cannot read " + path_;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    return serialize_graph();
}

std::string GraphNode::serialize_graph() const {
    nlohmann::json j;
    j["type"] = "graph";
    j["path"] = path_;
    j["entity_count"] = entities_.size();

    size_t total_edges = 0;
    auto entities_arr = nlohmann::json::array();
    for (const auto& [name, ent] : entities_) {
        nlohmann::json ej;
        ej["name"] = name;
        auto edges_arr = nlohmann::json::array();
        for (const auto& e : ent.outgoing) {
            nlohmann::json edge;
            edge["relation"] = e.relation;
            edge["target"] = e.target;
            edges_arr.push_back(edge);
            total_edges++;
        }
        ej["edges"] = edges_arr;
        entities_arr.push_back(ej);
    }
    j["edge_count"] = total_edges;
    j["entities"] = entities_arr;

    return j.dump();
}

std::string GraphNode::query_subgraph(const std::string& entity, int depth) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (entities_.find(entity) == entities_.end()) {
        std::printf("[GraphNode] QUERY_SUBGRAPH %s | entity='%s' NOT FOUND\n",
                    path_.c_str(), entity.c_str());
        return nlohmann::json::array().dump();
    }

    std::unordered_set<std::string> visited;
    std::queue<std::pair<std::string, int>> q;
    q.push({entity, 0});
    visited.insert(entity);

    struct VisitedEdge {
        std::string source;
        std::string relation;
        std::string target;
    };
    std::vector<VisitedEdge> collected_edges;

    while (!q.empty()) {
        auto [current, d] = q.front();
        q.pop();

        if (d >= depth) continue;

        auto it = entities_.find(current);
        if (it == entities_.end()) continue;

        for (const auto& edge : it->second.outgoing) {
            collected_edges.push_back({current, edge.relation, edge.target});

            if (visited.find(edge.target) == visited.end()) {
                visited.insert(edge.target);
                q.push({edge.target, d + 1});
            }
        }
    }

    std::ostringstream oss;
    oss << "Subgraph around '" << entity << "' (depth=" << depth << "):\n\n";

    for (const auto& e : collected_edges) {
        oss << "  " << e.source << " --[" << e.relation << "]--> " << e.target << "\n";
    }

    oss << "\nEntities reached: " << visited.size() << "\n";
    oss << "Edges traversed: " << collected_edges.size() << "\n";

    nlohmann::json result;
    result["center"] = entity;
    result["depth"] = depth;
    result["entities_reached"] = visited.size();
    result["edges_traversed"] = collected_edges.size();

    auto edges_arr = nlohmann::json::array();
    for (const auto& e : collected_edges) {
        nlohmann::json ej;
        ej["source"] = e.source;
        ej["relation"] = e.relation;
        ej["target"] = e.target;
        edges_arr.push_back(ej);
    }
    result["edges"] = edges_arr;
    result["description"] = oss.str();

    std::printf("[GraphNode] QUERY_SUBGRAPH %s | entity='%s' | depth=%d | entities=%zu | edges=%zu\n",
                path_.c_str(), entity.c_str(), depth, visited.size(), collected_edges.size());

    return result.dump();
}

size_t GraphNode::entity_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return entities_.size();
}

size_t GraphNode::edge_count() const {
    std::lock_guard<std::mutex> lock(mutex_);
    size_t total = 0;
    for (const auto& [_, ent] : entities_) {
        total += ent.outgoing.size();
    }
    return total;
}

} // namespace aios
