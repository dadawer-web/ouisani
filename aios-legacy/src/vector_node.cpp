#include "aios/vector_node.h"

#include "aios/event_bus.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <nlohmann/json.hpp>
#include <queue>
#include <string>
#include <vector>

namespace aios {

VectorNode::VectorNode(const std::string& path, std::shared_ptr<LlmAdapter> llm,
                       int owner_uid, int permissions)
    : VfsNode(VfsNodeType::VECTOR, path, owner_uid, permissions), llm_(std::move(llm)) {}

std::vector<float> VectorNode::mock_embedding(const std::string& text) const {
    std::vector<float> emb(MOCK_DIM, 0.0f);

    if (text.empty()) return emb;

    for (size_t i = 0; i < text.size(); ++i) {
        unsigned char c = static_cast<unsigned char>(text[i]);
        int slot = c % MOCK_DIM;
        emb[slot] += 1.0f;

        if (i > 0) {
            unsigned char prev = static_cast<unsigned char>(text[i - 1]);
            int bigram_slot = (prev * 31 + c) % MOCK_DIM;
            emb[bigram_slot] += 0.8f;
        }

        int pos_slot = (c + static_cast<int>(i % MOCK_DIM)) % MOCK_DIM;
        emb[pos_slot] += 0.3f;
    }

    float norm = 0.0f;
    for (float v : emb) {
        norm += v * v;
    }
    norm = std::sqrt(norm);
    if (norm > 1e-8f) {
        for (float& v : emb) {
            v /= norm;
        }
    }

    return emb;
}

std::vector<float> VectorNode::generate_embedding(const std::string& text) const {
    if (llm_ && llm_->has_embedding_config()) {
        try {
            auto emb = llm_->get_embedding(text);
            if (!emb.empty()) {
                std::printf("[VectorNode] Real embedding API | dim=%zu | text=\"%s\"\n",
                            emb.size(),
                            text.size() > 40 ? (text.substr(0, 40) + "...").c_str() : text.c_str());
                return emb;
            }
        } catch (const std::exception& e) {
            std::fprintf(stderr, "[VectorNode] Embedding API failed, fallback to mock: %s\n", e.what());
        }
    }

    std::printf("[VectorNode] Mock embedding | dim=%d | text=\"%s\"\n",
                MOCK_DIM,
                text.size() > 40 ? (text.substr(0, 40) + "...").c_str() : text.c_str());
    return mock_embedding(text);
}

bool VectorNode::write(const std::string& data) {
    return write_as(data, 0);
}

bool VectorNode::write_as(const std::string& data, int caller_uid) {
    if (data.empty()) return false;

    if (!check_write(caller_uid)) {
        std::printf("[VectorNode] WRITE DENIED %s | uid=%d is not owner (owner=%d, perm=%04o)\n",
                    path_.c_str(), caller_uid, owner_uid_, permissions_);
        return false;
    }

    auto embedding = generate_embedding(data);

    std::lock_guard<std::mutex> lock(mutex_);
    memories_.push_back({data, std::move(embedding)});

    std::printf("[VectorNode] WRITE %s | uid=%d | memories=%zu | text=\"%s\"\n",
                path_.c_str(), caller_uid, memories_.size(),
                data.size() > 60 ? (data.substr(0, 60) + "...").c_str() : data.c_str());

    EventBus::instance().publish(EventType::VFS_WRITE, "VectorNode",
        path_ + " | memories=" + std::to_string(memories_.size()));

    return true;
}

std::string VectorNode::read() const {
    return read_as(0);
}

std::string VectorNode::read_as(int caller_uid) const {
    if (!check_read(caller_uid)) {
        std::printf("[VectorNode] READ DENIED %s | uid=%d is not owner (owner=%d, perm=%04o)\n",
                    path_.c_str(), caller_uid, owner_uid_, permissions_);
        return "[PermissionDenied] uid " + std::to_string(caller_uid) +
               " cannot read " + path_;
    }

    std::lock_guard<std::mutex> lock(mutex_);
    nlohmann::json arr = nlohmann::json::array();
    for (const auto& m : memories_) {
        nlohmann::json obj;
        obj["text"] = m.text;
        obj["dim"] = static_cast<int>(m.embedding.size());
        arr.push_back(obj);
    }
    return arr.dump();
}

std::string VectorNode::search(const std::string& query, int top_k) {
    auto query_emb = generate_embedding(query);

    std::lock_guard<std::mutex> lock(mutex_);

    if (memories_.empty()) {
        return nlohmann::json::array().dump();
    }

    struct ScoredEntry {
        float score;
        std::string text;
    };

    auto cmp = [](const ScoredEntry& a, const ScoredEntry& b) {
        return a.score > b.score;
    };
    std::priority_queue<ScoredEntry, std::vector<ScoredEntry>, decltype(cmp)> min_heap(cmp);

    for (const auto& m : memories_) {
        float score = cosine_similarity(query_emb, m.embedding);
        ScoredEntry se{score, m.text};

        if (static_cast<int>(min_heap.size()) < top_k) {
            min_heap.push(std::move(se));
        } else if (score > min_heap.top().score) {
            min_heap.pop();
            min_heap.push(std::move(se));
        }
    }

    std::vector<ScoredEntry> results;
    while (!min_heap.empty()) {
        results.push_back(std::move(const_cast<ScoredEntry&>(min_heap.top())));
        min_heap.pop();
    }
    std::reverse(results.begin(), results.end());

    nlohmann::json arr = nlohmann::json::array();
    for (const auto& r : results) {
        nlohmann::json obj;
        obj["text"] = r.text;
        obj["score"] = std::round(r.score * 10000.0f) / 10000.0f;
        arr.push_back(obj);
    }

    std::printf("[VectorNode] SEARCH %s | query=\"%s\" | top_k=%d | results=%zu\n",
                path_.c_str(),
                query.size() > 40 ? (query.substr(0, 40) + "...").c_str() : query.c_str(),
                top_k, arr.size());

    return arr.dump();
}

} // namespace aios
