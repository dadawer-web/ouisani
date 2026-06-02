#pragma once

#include "aios/graph_node.h"
#include "aios/vector_node.h"
#include "aios/vfs_manager.h"

#include <cstdio>
#include <string>

#include <nlohmann/json.hpp>

namespace aios {

inline std::string build_rich_context(const std::string& keywords, int top_k = 3, int graph_depth = 2) {
    std::string vec_fragments;
    std::string graph_triples;

    auto vec_node_raw = VfsManager::instance().resolve_path("/dev/vec_mem_101");
    if (vec_node_raw && vec_node_raw->node_type() == VfsNodeType::VECTOR) {
        auto vec_node = std::dynamic_pointer_cast<VectorNode>(vec_node_raw);
        if (vec_node) {
            std::string search_results = vec_node->search(keywords, top_k);
            std::printf("[GraphRAG] VectorNode SEARCH | keywords='%s' | raw=%zu bytes\n",
                        keywords.c_str(), search_results.size());
            try {
                auto arr = nlohmann::json::parse(search_results);
                if (arr.is_array() && !arr.empty()) {
                    for (const auto& item : arr) {
                        if (item.contains("text")) {
                            vec_fragments += "  - " + item["text"].get<std::string>() + "\n";
                        }
                    }
                }
            } catch (const nlohmann::json::exception& e) {
                std::printf("[GraphRAG] VectorNode parse error: %s\n", e.what());
            }
        }
    }

    auto graph_node_raw = VfsManager::instance().resolve_path("/dev/graph0");
    if (graph_node_raw && graph_node_raw->node_type() == VfsNodeType::GRAPH) {
        auto graph_node = std::dynamic_pointer_cast<GraphNode>(graph_node_raw);
        if (graph_node) {
            std::string subgraph = graph_node->query_subgraph(keywords, graph_depth);
            std::printf("[GraphRAG] GraphNode QUERY_SUBGRAPH | entity='%s' | depth=%d\n",
                        keywords.c_str(), graph_depth);
            try {
                auto sg = nlohmann::json::parse(subgraph);
                if (sg.contains("edges") && sg["edges"].is_array() && !sg["edges"].empty()) {
                    graph_triples += "  Entities reached: " +
                                     std::to_string(sg.value("entities_reached", 0)) + "\n";
                    for (const auto& edge : sg["edges"]) {
                        graph_triples += "  " + edge["source"].get<std::string>() +
                                         " --[" + edge["relation"].get<std::string>() +
                                         "]--> " + edge["target"].get<std::string>() + "\n";
                    }
                }
                if (sg.contains("description") && sg["description"].is_string()) {
                    // use the human-readable description as fallback supplement
                }
            } catch (const nlohmann::json::exception& e) {
                std::printf("[GraphRAG] GraphNode parse error: %s\n", e.what());
            }
        }
    }

    if (vec_fragments.empty() && graph_triples.empty()) {
        std::printf("[GraphRAG] No knowledge found, using keywords as hint\n");
        return "\n\n[System Page Loaded: No stored knowledge matched. "
               "The user may be asking about: " + keywords + ". "
               "Try your best to answer. Do NOT mention this system message.]\n";
    }

    std::string rich_context =
        "\n\n[System Page Loaded: Knowledge was automatically retrieved to resolve a knowledge gap. "
        "Use this information to answer the original question. Do NOT mention this retrieval to the user.]\n";

    if (!vec_fragments.empty()) {
        rich_context += "\n--- Semantic Fragments (from VectorDB) ---\n" + vec_fragments;
    }

    if (!graph_triples.empty()) {
        rich_context += "\n--- Knowledge Graph Triples (from GraphFS) ---\n" + graph_triples;
    }

    rich_context += "\n[End System Page]\n";

    std::printf("[GraphRAG] Rich context built | vec=%s graph=%s | total=%zu bytes\n",
                vec_fragments.empty() ? "MISS" : "HIT",
                graph_triples.empty() ? "MISS" : "HIT",
                rich_context.size());

    return rich_context;
}

} // namespace aios
