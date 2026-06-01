#include "aios/wasm_node.h"
#include "aios/audio_node.h"
#include "aios/crash_analyzer.h"
#include "aios/event_bus.h"
#include "aios/kernel_logger.h"
#include "aios/llm_adapter.h"
#include "aios/vfs_manager.h"
#include "aios/vector_node.h"
#include "aios/graph_rag.h"

#include <wasmedge/wasmedge.h>

#include <csetjmp>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <httplib.h>
#include <mutex>
#include <nlohmann/json.hpp>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <unordered_map>
#include <vector>

namespace aios {

static thread_local std::jmp_buf g_wasm_timeout_jmp;
static thread_local bool g_wasm_timeout_armed = false;

static void wasm_timeout_handler(int) {
    if (g_wasm_timeout_armed) {
        std::longjmp(g_wasm_timeout_jmp, 1);
    }
}

std::shared_ptr<LlmAdapter> WasmNode::g_llm = nullptr;

void WasmNode::SetGlobalLlm(std::shared_ptr<LlmAdapter> llm) {
    g_llm = std::move(llm);
    if (g_llm) {
        std::fprintf(stderr, "[Ring 0 | NPU] Global LLM adapter mounted: %s\n", g_llm->model().c_str());
    } else {
        std::fprintf(stderr, "[Ring 0 | NPU] Global LLM adapter unmounted\n");
    }
}

static std::unordered_map<int, int> g_pending_signals;
static std::mutex g_signal_mutex;

void WasmNode::SendSignal(int agent_id, int signum) {
    std::lock_guard<std::mutex> lock(g_signal_mutex);
    g_pending_signals[agent_id] = signum;
    std::fprintf(stderr, "[Ring 0 | Signal] 向 Agent %d 投递信号 %d\n", agent_id, signum);
}

int WasmNode::CheckSignal(int agent_id) {
    std::lock_guard<std::mutex> lock(g_signal_mutex);
    auto it = g_pending_signals.find(agent_id);
    if (it != g_pending_signals.end()) {
        int signum = it->second;
        g_pending_signals.erase(it);
        return signum;
    }
    return 0;
}

static std::vector<uint8_t> g_shm_pool(1024 * 1024, 0);
static std::mutex g_shm_mutex;

static WasmEdge_Result aios_host_shm_write(void *,
                                              const WasmEdge_CallingFrameContext *CallFrameCxt,
                                              const WasmEdge_Value *In,
                                              WasmEdge_Value *) {
    uint32_t shm_offset = WasmEdge_ValueGetI32(In[0]);
    uint32_t wasm_ptr = WasmEdge_ValueGetI32(In[1]);
    uint32_t len = WasmEdge_ValueGetI32(In[2]);

    if (shm_offset + len > g_shm_pool.size() || len == 0) {
        std::fprintf(stderr, "[Ring 0 | SHM] shm_write OOB: offset=%u len=%u pool_size=%zu\n",
                     shm_offset, len, g_shm_pool.size());
        return WasmEdge_Result_Success;
    }

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (!MemCxt) return WasmEdge_Result_Success;

    uint8_t *src = WasmEdge_MemoryInstanceGetPointer(MemCxt, wasm_ptr, len);
    if (!src) {
        std::fprintf(stderr, "[Ring 0 | SHM] shm_write invalid wasm ptr: %u len=%u\n", wasm_ptr, len);
        return WasmEdge_Result_Success;
    }

    {
        std::lock_guard<std::mutex> lock(g_shm_mutex);
        std::memcpy(g_shm_pool.data() + shm_offset, src, len);
    }

    std::fprintf(stderr, "[Ring 0 | SHM] shm_write: offset=%u len=%u OK\n", shm_offset, len);
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_shm_read(void *,
                                             const WasmEdge_CallingFrameContext *CallFrameCxt,
                                             const WasmEdge_Value *In,
                                             WasmEdge_Value *) {
    uint32_t shm_offset = WasmEdge_ValueGetI32(In[0]);
    uint32_t wasm_ptr = WasmEdge_ValueGetI32(In[1]);
    uint32_t len = WasmEdge_ValueGetI32(In[2]);

    if (shm_offset + len > g_shm_pool.size() || len == 0) {
        std::fprintf(stderr, "[Ring 0 | SHM] shm_read OOB: offset=%u len=%u pool_size=%zu\n",
                     shm_offset, len, g_shm_pool.size());
        return WasmEdge_Result_Success;
    }

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (!MemCxt) return WasmEdge_Result_Success;

    uint8_t *dst = WasmEdge_MemoryInstanceGetPointer(MemCxt, wasm_ptr, len);
    if (!dst) {
        std::fprintf(stderr, "[Ring 0 | SHM] shm_read invalid wasm ptr: %u len=%u\n", wasm_ptr, len);
        return WasmEdge_Result_Success;
    }

    {
        std::lock_guard<std::mutex> lock(g_shm_mutex);
        std::memcpy(dst, g_shm_pool.data() + shm_offset, len);
    }

    std::fprintf(stderr, "[Ring 0 | SHM] shm_read: offset=%u len=%u OK\n", shm_offset, len);
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_snapshot(void *,
                                             const WasmEdge_CallingFrameContext *CallFrameCxt,
                                             const WasmEdge_Value *In,
                                             WasmEdge_Value *) {
    int32_t agent_id = WasmEdge_ValueGetI32(In[0]);
    uint32_t data_offset = WasmEdge_ValueGetI32(In[1]);
    uint32_t data_len = WasmEdge_ValueGetI32(In[2]);

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (!MemCxt) {
        std::fprintf(stderr, "[Ring 0 | Snapshot] Failed to get memory instance\n");
        return WasmEdge_Result_Success;
    }

    uint32_t page_count = WasmEdge_MemoryInstanceGetPageSize(MemCxt);
    uint32_t total_bytes = page_count * 65536;

    if (data_offset + data_len > total_bytes || data_len == 0) {
        std::fprintf(stderr, "[Ring 0 | Snapshot] Invalid range: offset=%u len=%u total=%u\n",
                     data_offset, data_len, total_bytes);
        return WasmEdge_Result_Success;
    }

    std::vector<uint8_t> mem_data(data_len, 0);
    WasmEdge_Result get_res = WasmEdge_MemoryInstanceGetData(MemCxt, mem_data.data(), data_offset, data_len);
    if (!WasmEdge_ResultOK(get_res)) {
        std::fprintf(stderr, "[Ring 0 | Snapshot] GetData failed: %s\n",
                     WasmEdge_ResultGetMessage(get_res));
        return WasmEdge_Result_Success;
    }

    mkdir("/tmp/aios_tasks", 0777);
    std::string filepath = "/tmp/aios_tasks/agent_" + std::to_string(agent_id) + ".mem";

    std::ofstream ofs(filepath, std::ios::binary | std::ios::trunc);
    if (!ofs.is_open()) {
        std::fprintf(stderr, "[Ring 0 | Snapshot] Cannot open %s for writing\n", filepath.c_str());
        return WasmEdge_Result_Success;
    }

    uint32_t header[2] = {data_offset, data_len};
    ofs.write(reinterpret_cast<const char*>(header), sizeof(header));
    ofs.write(reinterpret_cast<const char*>(mem_data.data()), data_len);
    ofs.close();

    std::fprintf(stderr, "[Ring 0] 核心转储完毕，成功保存 %d 号进程快照 (offset=%u, %u bytes -> %s)\n",
                 agent_id, data_offset, data_len, filepath.c_str());
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_npu_infer(void *,
                                              const WasmEdge_CallingFrameContext *CallFrameCxt,
                                              const WasmEdge_Value *In,
                                              WasmEdge_Value *Out) {
    uint32_t prompt_offset = WasmEdge_ValueGetI32(In[0]);
    uint32_t prompt_len = WasmEdge_ValueGetI32(In[1]);
    uint32_t resp_offset = WasmEdge_ValueGetI32(In[2]);
    uint32_t max_resp_len = WasmEdge_ValueGetI32(In[3]);

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (!MemCxt) {
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }

    std::string prompt;
    if (prompt_len > 0) {
        uint8_t *prompt_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, prompt_offset, prompt_len);
        if (!prompt_buf) {
            std::fprintf(stderr, "[Ring 0 | NPU Driver] Invalid prompt pointer\n");
            Out[0] = WasmEdge_ValueGenI32(-1);
            return WasmEdge_Result_Success;
        }
        prompt.assign(reinterpret_cast<char*>(prompt_buf), prompt_len);
    }

    std::string display = prompt.substr(0, 20);
    if (prompt.size() > 20) display += "...";
    std::fprintf(stderr, "[Ring 0 | NPU Driver] 收到沙盒推理请求: %s\n", display.c_str());

    std::string result;
    if (WasmNode::g_llm && WasmNode::g_llm->has_api_key()) {
        result = WasmNode::g_llm->generate("You are a helpful AI assistant running inside the AIOS NPU virtual device.", prompt);
        std::fprintf(stderr, "[Ring 0 | NPU Driver] Inference complete: %zu bytes\n", result.size());
    } else {
        result = "[NPU device offline] No LLM adapter available. Please configure API key.";
        std::fprintf(stderr, "[Ring 0 | NPU Driver] Device offline, returning fallback\n");
    }

    uint32_t copy_len = static_cast<uint32_t>(result.size());
    if (copy_len > max_resp_len) {
        std::fprintf(stderr, "[Ring 0 | NPU Driver] Response truncated: %u -> %u\n",
                     copy_len, max_resp_len);
        copy_len = max_resp_len;
    }

    if (copy_len > 0 && max_resp_len > 0) {
        uint8_t *resp_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, resp_offset, max_resp_len);
        if (!resp_buf) {
            std::fprintf(stderr, "[Ring 0 | NPU Driver] Invalid resp buffer pointer\n");
            Out[0] = WasmEdge_ValueGenI32(-2);
            return WasmEdge_Result_Success;
        }
        std::memcpy(resp_buf, result.data(), copy_len);
    }

    Out[0] = WasmEdge_ValueGenI32(static_cast<int32_t>(copy_len));
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_check_signal(void *,
                                                  const WasmEdge_CallingFrameContext *,
                                                  const WasmEdge_Value *In,
                                                  WasmEdge_Value *Out) {
    int32_t agent_id = WasmEdge_ValueGetI32(In[0]);
    int signum = WasmNode::CheckSignal(agent_id);
    Out[0] = WasmEdge_ValueGenI32(signum);
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_kprint(void *,
                                         const WasmEdge_CallingFrameContext *CallFrameCxt,
                                         const WasmEdge_Value *In,
                                         WasmEdge_Value *) {
    uint32_t offset = WasmEdge_ValueGetI32(In[0]);
    uint32_t len = WasmEdge_ValueGetI32(In[1]);

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (MemCxt) {
        uint8_t *Buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, offset, len);
        if (Buf) {
            std::string msg((char*)Buf, len);
            std::fprintf(stderr, "\n[Ring 0 | Host API] Wasm reverse syscall: %s\n\n", msg.c_str());
        }
    }
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_http_get(void *,
                                            const WasmEdge_CallingFrameContext *CallFrameCxt,
                                            const WasmEdge_Value *In,
                                            WasmEdge_Value *Out) {
    uint32_t host_offset = WasmEdge_ValueGetI32(In[0]);
    uint32_t host_len = WasmEdge_ValueGetI32(In[1]);
    uint32_t path_offset = WasmEdge_ValueGetI32(In[2]);
    uint32_t path_len = WasmEdge_ValueGetI32(In[3]);
    uint32_t resp_offset = WasmEdge_ValueGetI32(In[4]);
    uint32_t max_resp_len = WasmEdge_ValueGetI32(In[5]);

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (!MemCxt) {
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }

    uint8_t *host_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, host_offset, host_len);
    if (!host_buf || host_len == 0) {
        std::fprintf(stderr, "[Ring 0 | Net] Invalid host pointer\n");
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }
    std::string host((char*)host_buf, host_len);

    uint8_t *path_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, path_offset, path_len);
    if (!path_buf || path_len == 0) {
        std::fprintf(stderr, "[Ring 0 | Net] Invalid path pointer\n");
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }
    std::string path((char*)path_buf, path_len);

    std::fprintf(stderr, "\n[Ring 0 | Net] Wasm 发起网络请求: host=%s path=%s\n", host.c_str(), path.c_str());

    std::string scheme = "http://";
    std::string clean_host = host;
    if (host.find("https://") == 0) {
        scheme = "https://";
        clean_host = host.substr(8);
    } else if (host.find("http://") == 0) {
        clean_host = host.substr(7);
    }

    std::string port_str;
    std::string host_only = clean_host;
    auto colon_pos = clean_host.rfind(':');
    if (colon_pos != std::string::npos) {
        host_only = clean_host.substr(0, colon_pos);
        port_str = clean_host.substr(colon_pos + 1);
    }

    int port = 80;
    bool use_ssl = (scheme == "https://");
    if (!port_str.empty()) {
        try { port = std::stoi(port_str); } catch (...) { port = use_ssl ? 443 : 80; }
    } else {
        port = use_ssl ? 443 : 80;
    }

    httplib::Client cli(host_only, port);
    cli.set_connection_timeout(5);
    cli.set_read_timeout(10);
    if (use_ssl) {
        cli.enable_server_certificate_verification(false);
    }

    auto res = cli.Get(path.c_str());

    if (!res) {
        std::string err = httplib::to_string(res.error());
        std::fprintf(stderr, "[Ring 0 | Net] HTTP request failed: %s\n", err.c_str());
        Out[0] = WasmEdge_ValueGenI32(-2);
        return WasmEdge_Result_Success;
    }

    if (res->status != 200) {
        std::fprintf(stderr, "[Ring 0 | Net] HTTP %d (expected 200)\n", res->status);
        Out[0] = WasmEdge_ValueGenI32(-3);
        return WasmEdge_Result_Success;
    }

    const std::string &body = res->body;
    uint32_t copy_len = static_cast<uint32_t>(body.size());
    if (copy_len > max_resp_len) {
        std::fprintf(stderr, "[Ring 0 | Net] Response truncated: %u -> %u\n",
                     copy_len, max_resp_len);
        copy_len = max_resp_len;
    }

    if (copy_len > 0 && max_resp_len > 0) {
        uint8_t *resp_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, resp_offset, max_resp_len);
        if (!resp_buf) {
            std::fprintf(stderr, "[Ring 0 | Net] Invalid resp buffer pointer\n");
            Out[0] = WasmEdge_ValueGenI32(-4);
            return WasmEdge_Result_Success;
        }
        std::memcpy(resp_buf, body.data(), copy_len);
    }

    std::fprintf(stderr, "[Ring 0 | Net] HTTP GET success: %u bytes written to sandbox\n", copy_len);
    Out[0] = WasmEdge_ValueGenI32(static_cast<int32_t>(copy_len));
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_vfs_read(void *,
                                               const WasmEdge_CallingFrameContext *CallFrameCxt,
                                               const WasmEdge_Value *In,
                                               WasmEdge_Value *Out) {
    uint32_t path_offset = WasmEdge_ValueGetI32(In[0]);
    uint32_t path_len = WasmEdge_ValueGetI32(In[1]);
    uint32_t resp_offset = WasmEdge_ValueGetI32(In[2]);
    uint32_t max_resp_len = WasmEdge_ValueGetI32(In[3]);

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (!MemCxt) {
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }

    if (path_len == 0 || path_len > 4096) {
        std::fprintf(stderr, "[Ring 0 | VFS_READ] Invalid path length: %u\n", path_len);
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }

    uint8_t *path_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, path_offset, path_len);
    if (!path_buf) {
        std::fprintf(stderr, "[Ring 0 | VFS_READ] Invalid path pointer: offset=%u len=%u\n",
                     path_offset, path_len);
        Out[0] = WasmEdge_ValueGenI32(-2);
        return WasmEdge_Result_Success;
    }

    std::string vfs_path(reinterpret_cast<char*>(path_buf), path_len);

    std::fprintf(stderr, "[Ring 0 | VFS_READ] Sandbox requests: %s\n", vfs_path.c_str());

    auto node = VfsManager::instance().resolve_path(vfs_path);
    std::string content;
    if (node) {
        content = node->read();
        std::fprintf(stderr, "[Ring 0 | VFS_READ] %s -> %zu bytes\n",
                     vfs_path.c_str(), content.size());
    } else {
        content = "[VFS_READ error] Path not found: " + vfs_path;
        std::fprintf(stderr, "[Ring 0 | VFS_READ] Path not found: %s\n", vfs_path.c_str());
    }

    uint32_t copy_len = static_cast<uint32_t>(content.size());
    if (copy_len > max_resp_len) {
        std::fprintf(stderr, "[Ring 0 | VFS_READ] Truncated: %u -> %u\n",
                     copy_len, max_resp_len);
        copy_len = max_resp_len;
    }

    if (copy_len > 0 && max_resp_len > 0) {
        uint8_t *resp_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, resp_offset, max_resp_len);
        if (!resp_buf) {
            std::fprintf(stderr, "[Ring 0 | VFS_READ] Invalid resp pointer: offset=%u len=%u\n",
                         resp_offset, max_resp_len);
            Out[0] = WasmEdge_ValueGenI32(-3);
            return WasmEdge_Result_Success;
        }
        std::memcpy(resp_buf, content.data(), copy_len);
    }

    Out[0] = WasmEdge_ValueGenI32(static_cast<int32_t>(copy_len));
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_think(void *,
                                            const WasmEdge_CallingFrameContext *CallFrameCxt,
                                            const WasmEdge_Value *In,
                                            WasmEdge_Value *Out) {
    uint32_t prompt_offset = WasmEdge_ValueGetI32(In[0]);
    uint32_t prompt_len = WasmEdge_ValueGetI32(In[1]);
    uint32_t resp_offset = WasmEdge_ValueGetI32(In[2]);
    uint32_t max_resp_len = WasmEdge_ValueGetI32(In[3]);

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (!MemCxt) {
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }

    if (prompt_len == 0 || prompt_len > 65536) {
        std::fprintf(stderr, "[Ring 0 | Think] Invalid prompt length: %u\n", prompt_len);
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }

    uint8_t *prompt_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, prompt_offset, prompt_len);
    if (!prompt_buf) {
        std::fprintf(stderr, "[Ring 0 | Think] Invalid prompt pointer: offset=%u len=%u\n",
                     prompt_offset, prompt_len);
        Out[0] = WasmEdge_ValueGenI32(-2);
        return WasmEdge_Result_Success;
    }

    std::string prompt(reinterpret_cast<char*>(prompt_buf), prompt_len);

    std::string display = prompt.substr(0, 40);
    if (prompt.size() > 40) display += "...";
    std::fprintf(stderr, "[Ring 0 | Think] Sandbox LLM request: %s\n", display.c_str());

    std::string result;
    if (WasmNode::g_llm && WasmNode::g_llm->has_api_key()) {
        int pf_retry = 0;
        constexpr int kMaxPfRetry = 3;

    pf_retry_think:
        try {
            result = WasmNode::g_llm->generate("", prompt);
            std::fprintf(stderr, "[Ring 0 | Think] Inference complete: %zu bytes\n", result.size());
        } catch (const SemanticPageFaultException& pf) {
            pf_retry++;
            std::fprintf(stderr, "[PageFault] WASM Think | Semantic Page Fault #%d | keywords='%s'\n",
                         pf_retry, pf.keywords().c_str());

            if (pf_retry > kMaxPfRetry) {
                result = "[System: Knowledge retrieval exhausted. Query: " + pf.keywords() + "]";
            } else {
                std::string knowledge_context = build_rich_context(pf.keywords());

                prompt = knowledge_context + prompt;
                std::fprintf(stderr, "[PageFault] WASM Think | GraphRAG context injected, retrying\n");
                goto pf_retry_think;
            }
        }
    } else {
        result = "[AIOS Think] LLM adapter offline. Configure API key to enable.";
        std::fprintf(stderr, "[Ring 0 | Think] LLM offline, returning fallback\n");
    }

    uint32_t copy_len = static_cast<uint32_t>(result.size());
    if (copy_len > max_resp_len) {
        std::fprintf(stderr, "[Ring 0 | Think] Response truncated: %u -> %u\n",
                     copy_len, max_resp_len);
        copy_len = max_resp_len;
    }

    if (copy_len > 0 && max_resp_len > 0) {
        uint8_t *resp_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, resp_offset, max_resp_len);
        if (!resp_buf) {
            std::fprintf(stderr, "[Ring 0 | Think] Invalid resp pointer: offset=%u len=%u\n",
                         resp_offset, max_resp_len);
            Out[0] = WasmEdge_ValueGenI32(-3);
            return WasmEdge_Result_Success;
        }
        std::memcpy(resp_buf, result.data(), copy_len);
    }

    Out[0] = WasmEdge_ValueGenI32(static_cast<int32_t>(copy_len));
    return WasmEdge_Result_Success;
}

static WasmEdge_Result aios_host_speak(void *,
                                           const WasmEdge_CallingFrameContext *CallFrameCxt,
                                           const WasmEdge_Value *In,
                                           WasmEdge_Value *Out) {
    uint32_t text_offset = WasmEdge_ValueGetI32(In[0]);
    uint32_t text_len = WasmEdge_ValueGetI32(In[1]);

    WasmEdge_MemoryInstanceContext *MemCxt =
        WasmEdge_CallingFrameGetMemoryInstance(CallFrameCxt, 0);
    if (!MemCxt) {
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }

    if (text_len == 0 || text_len > 65536) {
        std::fprintf(stderr, "[Ring 0 | Speak] Invalid text length: %u\n", text_len);
        Out[0] = WasmEdge_ValueGenI32(-1);
        return WasmEdge_Result_Success;
    }

    uint8_t *text_buf = WasmEdge_MemoryInstanceGetPointer(MemCxt, text_offset, text_len);
    if (!text_buf) {
        std::fprintf(stderr, "[Ring 0 | Speak] Invalid text pointer: offset=%u len=%u\n",
                     text_offset, text_len);
        Out[0] = WasmEdge_ValueGenI32(-2);
        return WasmEdge_Result_Success;
    }

    std::string text(reinterpret_cast<char*>(text_buf), text_len);

    std::string display = text.substr(0, 40);
    if (text.size() > 40) display += "...";
    std::fprintf(stderr, "[Ring 0 | Speak] TTS request: %s\n", display.c_str());

    auto& vfs = aios::VfsManager::instance();
    auto pcm_node = vfs.resolve_path("/dev/audio/pcm", 0);
    auto vis_node = vfs.resolve_path("/dev/audio/visemes", 0);

    auto pcm_audio = std::dynamic_pointer_cast<aios::AudioNode>(pcm_node);
    auto vis_audio = std::dynamic_pointer_cast<aios::AudioNode>(vis_node);

    if (!pcm_audio && !vis_audio) {
        std::fprintf(stderr, "[Ring 0 | Speak] Audio devices not available\n");
        Out[0] = WasmEdge_ValueGenI32(-3);
        return WasmEdge_Result_Success;
    }

    if (WasmNode::g_llm && WasmNode::g_llm->has_api_key()) {
        WasmNode::g_llm->stream_tts(text, pcm_audio, vis_audio);
    } else {
        std::fprintf(stderr, "[Ring 0 | Speak] LLM offline, using fallback TTS simulation\n");
        aios::LlmAdapter fallback_llm("", "", "");
        fallback_llm.stream_tts(text, pcm_audio, vis_audio);
    }

    Out[0] = WasmEdge_ValueGenI32(0);
    return WasmEdge_Result_Success;
}

WasmNode::WasmNode(const std::string& path, const std::string& wasm_file_path)
    : VfsNode(VfsNodeType::WASM, path)
    , wasm_file_path_(wasm_file_path)
{
    std::fprintf(stderr, "[WasmNode] Created: %s -> %s\n", path.c_str(), wasm_file_path.c_str());
}

std::string WasmNode::execute(const std::string& payload) {
    return execute_with_fds(payload, -1, -1);
}

std::string WasmNode::execute_with_fds(const std::string& payload,
                                        int override_stdin_fd,
                                        int override_stdout_fd) {
    std::lock_guard<std::mutex> lock(exec_mutex_);

    EventBus::instance().publish(EventType::WASM_EXEC_START, "WasmNode",
        "Executing " + wasm_file_path_);

    std::string wasm_file = wasm_file_path_;
    std::string func_name_str = "_start";
    std::string stdin_data;
    std::vector<WasmEdge_Value> params;
    int restore_agent_id = -1;

    if (!payload.empty()) {
        auto parsed = nlohmann::json::parse(payload, nullptr, false);
        if (!parsed.is_discarded() && parsed.is_object()) {
            if (parsed.contains("file") && parsed["file"].is_string())
                wasm_file = parsed["file"].get<std::string>();
            if (parsed.contains("func") && parsed["func"].is_string())
                func_name_str = parsed["func"].get<std::string>();
            if (parsed.contains("stdin") && parsed["stdin"].is_string())
                stdin_data = parsed["stdin"].get<std::string>();
            if (parsed.contains("restore_agent_id") && parsed["restore_agent_id"].is_number_integer())
                restore_agent_id = parsed["restore_agent_id"].get<int>();
            if (parsed.contains("args") && parsed["args"].is_array()) {
                for (const auto& arg : parsed["args"]) {
                    if (arg.is_number_integer()) {
                        params.push_back(WasmEdge_ValueGenI32(arg.get<int32_t>()));
                    }
                }
            }
        }
    }

    std::fprintf(stderr, "[WasmNode] Executing: %s | func=%s | stdin=%zu bytes\n",
                 wasm_file.c_str(), func_name_str.c_str(), stdin_data.size());

    FILE* tmp_in = nullptr;
    int saved_stdin = -1;

    if (override_stdin_fd != -1) {
        saved_stdin = dup(STDIN_FILENO);
        if (saved_stdin < 0) {
            nlohmann::json result_json;
            result_json["file"] = wasm_file;
            result_json["status"] = "error";
            result_json["reason"] = "dup(STDIN) failed for override fd";
            return result_json.dump();
        }
        dup2(override_stdin_fd, STDIN_FILENO);
        std::fprintf(stderr, "[WasmNode] STDIN override: fd=%d\n", override_stdin_fd);
    } else if (!stdin_data.empty()) {
        tmp_in = tmpfile();
        if (!tmp_in) {
            nlohmann::json result_json;
            result_json["file"] = wasm_file;
            result_json["status"] = "error";
            result_json["reason"] = "tmpfile() for stdin failed";
            return result_json.dump();
        }
        fwrite(stdin_data.data(), 1, stdin_data.size(), tmp_in);
        rewind(tmp_in);

        saved_stdin = dup(STDIN_FILENO);
        if (saved_stdin < 0) {
            fclose(tmp_in);
            nlohmann::json result_json;
            result_json["file"] = wasm_file;
            result_json["status"] = "error";
            result_json["reason"] = "dup(STDIN) failed";
            return result_json.dump();
        }
        dup2(fileno(tmp_in), STDIN_FILENO);
        std::fprintf(stderr, "[WasmNode] STDIN redirected: %zu bytes\n", stdin_data.size());
    }

    auto restore_stdin = [&]() {
        if (saved_stdin >= 0) {
            dup2(saved_stdin, STDIN_FILENO);
            close(saved_stdin);
            saved_stdin = -1;
        }
        if (tmp_in) {
            fclose(tmp_in);
            tmp_in = nullptr;
        }
    };

    int saved_stdout = -1;
    int saved_stderr_for_pipe = -1;
    if (override_stdout_fd != -1) {
        saved_stdout = dup(STDOUT_FILENO);
        if (saved_stdout < 0) {
            restore_stdin();
            nlohmann::json result_json;
            result_json["file"] = wasm_file;
            result_json["status"] = "error";
            result_json["reason"] = "dup(STDOUT) failed for override fd";
            return result_json.dump();
        }
        saved_stderr_for_pipe = dup(STDERR_FILENO);
        dup2(saved_stdout, STDERR_FILENO);
        dup2(override_stdout_fd, STDOUT_FILENO);
        std::fprintf(stderr, "[WasmNode] STDOUT override: fd=%d (stderr preserved)\n", override_stdout_fd);
    }

    auto restore_stdout = [&]() {
        if (saved_stdout >= 0) {
            dup2(saved_stdout, STDOUT_FILENO);
            close(saved_stdout);
            saved_stdout = -1;
        }
        if (saved_stderr_for_pipe >= 0) {
            dup2(saved_stderr_for_pipe, STDERR_FILENO);
            close(saved_stderr_for_pipe);
            saved_stderr_for_pipe = -1;
        }
    };

    WasmEdge_Result exec_res = {.Code = WasmEdge_ErrCode_RuntimeError};
    volatile int32_t wasm_return_val = 0;

    WasmEdge_ConfigureContext* conf = WasmEdge_ConfigureCreate();
    WasmEdge_ConfigureAddHostRegistration(conf, WasmEdge_HostRegistration_Wasi);
    WasmEdge_ConfigureAddProposal(conf, WasmEdge_Proposal_Threads);
    WasmEdge_ConfigureAddProposal(conf, WasmEdge_Proposal_SIMD);
    WasmEdge_ConfigureAddProposal(conf, WasmEdge_Proposal_BulkMemoryOperations);
    WasmEdge_ConfigureSetMaxMemoryPage(conf, 256);
    WasmEdge_ConfigureStatisticsSetInstructionCounting(conf, true);
    WasmEdge_ConfigureStatisticsSetCostMeasuring(conf, true);

    WasmEdge_VMContext* vm = WasmEdge_VMCreate(conf, nullptr);
    WasmEdge_ConfigureDelete(conf);

    if (!vm) {
        restore_stdout();
        restore_stdin();
        nlohmann::json result_json;
        result_json["file"] = wasm_file;
        result_json["status"] = "error";
        result_json["reason"] = "Failed to create WasmEdge VM";
        return result_json.dump();
    }

    WasmEdge_StatisticsContext* stat = WasmEdge_VMGetStatisticsContext(vm);
    if (stat) {
        WasmEdge_StatisticsSetCostLimit(stat, 50000000);
    }

    WasmEdge_String mod_name = WasmEdge_StringCreateByCString("aios");
    WasmEdge_ModuleInstanceContext* host_mod = WasmEdge_ModuleInstanceCreate(mod_name);
    WasmEdge_StringDelete(mod_name);

    WasmEdge_String kprint_name = WasmEdge_StringCreateByCString("kprint");
    enum WasmEdge_ValType param_types[] = {
        WasmEdge_ValType_I32, WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* func_type =
        WasmEdge_FunctionTypeCreate(param_types, 2, nullptr, 0);
    WasmEdge_FunctionInstanceContext* host_func =
        WasmEdge_FunctionInstanceCreate(func_type, aios_host_kprint, nullptr, 0);
    WasmEdge_FunctionTypeDelete(func_type);

    WasmEdge_ModuleInstanceAddFunction(host_mod, kprint_name, host_func);
    WasmEdge_StringDelete(kprint_name);

    WasmEdge_String http_get_name = WasmEdge_StringCreateByCString("http_get");
    enum WasmEdge_ValType http_get_params[] = {
        WasmEdge_ValType_I32, WasmEdge_ValType_I32,
        WasmEdge_ValType_I32, WasmEdge_ValType_I32,
        WasmEdge_ValType_I32, WasmEdge_ValType_I32
    };
    enum WasmEdge_ValType http_get_returns[] = {
        WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* http_get_type =
        WasmEdge_FunctionTypeCreate(http_get_params, 6, http_get_returns, 1);
    WasmEdge_FunctionInstanceContext* http_get_func =
        WasmEdge_FunctionInstanceCreate(http_get_type, aios_host_http_get, nullptr, 0);
    WasmEdge_FunctionTypeDelete(http_get_type);

    WasmEdge_ModuleInstanceAddFunction(host_mod, http_get_name, http_get_func);
    WasmEdge_StringDelete(http_get_name);

    WasmEdge_String shm_write_name = WasmEdge_StringCreateByCString("shm_write");
    enum WasmEdge_ValType shm_params[] = {
        WasmEdge_ValType_I32, WasmEdge_ValType_I32, WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* shm_write_type =
        WasmEdge_FunctionTypeCreate(shm_params, 3, nullptr, 0);
    WasmEdge_FunctionInstanceContext* shm_write_func =
        WasmEdge_FunctionInstanceCreate(shm_write_type, aios_host_shm_write, nullptr, 0);
    WasmEdge_FunctionTypeDelete(shm_write_type);
    WasmEdge_ModuleInstanceAddFunction(host_mod, shm_write_name, shm_write_func);
    WasmEdge_StringDelete(shm_write_name);

    WasmEdge_String shm_read_name = WasmEdge_StringCreateByCString("shm_read");
    WasmEdge_FunctionTypeContext* shm_read_type =
        WasmEdge_FunctionTypeCreate(shm_params, 3, nullptr, 0);
    WasmEdge_FunctionInstanceContext* shm_read_func =
        WasmEdge_FunctionInstanceCreate(shm_read_type, aios_host_shm_read, nullptr, 0);
    WasmEdge_FunctionTypeDelete(shm_read_type);
    WasmEdge_ModuleInstanceAddFunction(host_mod, shm_read_name, shm_read_func);
    WasmEdge_StringDelete(shm_read_name);

    WasmEdge_String snapshot_name = WasmEdge_StringCreateByCString("snapshot");
    enum WasmEdge_ValType snapshot_params[] = {
        WasmEdge_ValType_I32, WasmEdge_ValType_I32, WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* snapshot_type =
        WasmEdge_FunctionTypeCreate(snapshot_params, 3, nullptr, 0);
    WasmEdge_FunctionInstanceContext* snapshot_func =
        WasmEdge_FunctionInstanceCreate(snapshot_type, aios_host_snapshot, nullptr, 0);
    WasmEdge_FunctionTypeDelete(snapshot_type);
    WasmEdge_ModuleInstanceAddFunction(host_mod, snapshot_name, snapshot_func);
    WasmEdge_StringDelete(snapshot_name);

    WasmEdge_String npu_infer_name = WasmEdge_StringCreateByCString("npu_infer");
    enum WasmEdge_ValType npu_params[] = {
        WasmEdge_ValType_I32, WasmEdge_ValType_I32,
        WasmEdge_ValType_I32, WasmEdge_ValType_I32
    };
    enum WasmEdge_ValType npu_returns[] = {
        WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* npu_infer_type =
        WasmEdge_FunctionTypeCreate(npu_params, 4, npu_returns, 1);
    WasmEdge_FunctionInstanceContext* npu_infer_func =
        WasmEdge_FunctionInstanceCreate(npu_infer_type, aios_host_npu_infer, nullptr, 0);
    WasmEdge_FunctionTypeDelete(npu_infer_type);
    WasmEdge_ModuleInstanceAddFunction(host_mod, npu_infer_name, npu_infer_func);
    WasmEdge_StringDelete(npu_infer_name);

    WasmEdge_String check_signal_name = WasmEdge_StringCreateByCString("check_signal");
    enum WasmEdge_ValType check_signal_params[] = {
        WasmEdge_ValType_I32
    };
    enum WasmEdge_ValType check_signal_returns[] = {
        WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* check_signal_type =
        WasmEdge_FunctionTypeCreate(check_signal_params, 1, check_signal_returns, 1);
    WasmEdge_FunctionInstanceContext* check_signal_func =
        WasmEdge_FunctionInstanceCreate(check_signal_type, aios_host_check_signal, nullptr, 0);
    WasmEdge_FunctionTypeDelete(check_signal_type);
    WasmEdge_ModuleInstanceAddFunction(host_mod, check_signal_name, check_signal_func);
    WasmEdge_StringDelete(check_signal_name);

    WasmEdge_String vfs_read_name = WasmEdge_StringCreateByCString("__aios_vfs_read");
    enum WasmEdge_ValType vfs_read_params[] = {
        WasmEdge_ValType_I32, WasmEdge_ValType_I32,
        WasmEdge_ValType_I32, WasmEdge_ValType_I32
    };
    enum WasmEdge_ValType vfs_read_returns[] = {
        WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* vfs_read_type =
        WasmEdge_FunctionTypeCreate(vfs_read_params, 4, vfs_read_returns, 1);
    WasmEdge_FunctionInstanceContext* vfs_read_func =
        WasmEdge_FunctionInstanceCreate(vfs_read_type, aios_host_vfs_read, nullptr, 0);
    WasmEdge_FunctionTypeDelete(vfs_read_type);
    WasmEdge_ModuleInstanceAddFunction(host_mod, vfs_read_name, vfs_read_func);
    WasmEdge_StringDelete(vfs_read_name);

    WasmEdge_String think_name = WasmEdge_StringCreateByCString("__aios_think");
    enum WasmEdge_ValType think_params[] = {
        WasmEdge_ValType_I32, WasmEdge_ValType_I32,
        WasmEdge_ValType_I32, WasmEdge_ValType_I32
    };
    enum WasmEdge_ValType think_returns[] = {
        WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* think_type =
        WasmEdge_FunctionTypeCreate(think_params, 4, think_returns, 1);
    WasmEdge_FunctionInstanceContext* think_func =
        WasmEdge_FunctionInstanceCreate(think_type, aios_host_think, nullptr, 0);
    WasmEdge_FunctionTypeDelete(think_type);
    WasmEdge_ModuleInstanceAddFunction(host_mod, think_name, think_func);
    WasmEdge_StringDelete(think_name);

    WasmEdge_String speak_name = WasmEdge_StringCreateByCString("__aios_vfs_speak");
    enum WasmEdge_ValType speak_params[] = {
        WasmEdge_ValType_I32, WasmEdge_ValType_I32
    };
    enum WasmEdge_ValType speak_returns[] = {
        WasmEdge_ValType_I32
    };
    WasmEdge_FunctionTypeContext* speak_type =
        WasmEdge_FunctionTypeCreate(speak_params, 2, speak_returns, 1);
    WasmEdge_FunctionInstanceContext* speak_func =
        WasmEdge_FunctionInstanceCreate(speak_type, aios_host_speak, nullptr, 0);
    WasmEdge_FunctionTypeDelete(speak_type);
    WasmEdge_ModuleInstanceAddFunction(host_mod, speak_name, speak_func);
    WasmEdge_StringDelete(speak_name);

    WasmEdge_Result reg_res = WasmEdge_VMRegisterModuleFromImport(vm, host_mod);
    if (!WasmEdge_ResultOK(reg_res)) {
        std::fprintf(stderr, "[WasmNode] Host module register failed: %s\n",
                     WasmEdge_ResultGetMessage(reg_res));
    } else {
        std::fprintf(stderr, "[WasmNode] aios module registered: kprint, http_get, shm_write, shm_read, snapshot, npu_infer, check_signal, __aios_vfs_read, __aios_think, __aios_vfs_speak\n");
    }

    {
        mkdir("/tmp/aios_workspace", 0777);

        WasmEdge_ModuleInstanceContext* wasi_module =
            WasmEdge_VMGetImportModuleContext(vm, WasmEdge_HostRegistration_Wasi);
        if (wasi_module) {
            const char* args[] = {wasm_file.c_str(), nullptr};
            const char* preopens[] = {"/workspace:/tmp/aios_workspace"};
            WasmEdge_ModuleInstanceInitWASI(wasi_module, args, 1, nullptr, 0, preopens, 1);
        }
    }

    {
        WasmEdge_String func_name = WasmEdge_StringWrap(
            func_name_str.c_str(), static_cast<uint32_t>(func_name_str.size()));

        WasmEdge_Result load_res = WasmEdge_VMLoadWasmFromFile(vm, wasm_file.c_str());
        if (!WasmEdge_ResultOK(load_res)) {
            std::fprintf(stderr, "[WasmNode] Load failed: %s\n",
                         WasmEdge_ResultGetMessage(load_res));
            WasmEdge_VMDelete(vm);
            WasmEdge_ModuleInstanceDelete(host_mod);
            restore_stdout();
            restore_stdin();
            nlohmann::json result_json;
            result_json["file"] = wasm_file;
            result_json["status"] = "error";
            result_json["reason"] = "WASM load failed";
            return result_json.dump();
        }

        WasmEdge_Result validate_res = WasmEdge_VMValidate(vm);
        if (!WasmEdge_ResultOK(validate_res)) {
            std::fprintf(stderr, "[WasmNode] Validate failed: %s\n",
                         WasmEdge_ResultGetMessage(validate_res));
            WasmEdge_VMDelete(vm);
            WasmEdge_ModuleInstanceDelete(host_mod);
            restore_stdout();
            restore_stdin();
            nlohmann::json result_json;
            result_json["file"] = wasm_file;
            result_json["status"] = "error";
            result_json["reason"] = "WASM validation failed";
            return result_json.dump();
        }

        WasmEdge_Result instantiate_res = WasmEdge_VMInstantiate(vm);
        if (!WasmEdge_ResultOK(instantiate_res)) {
            std::fprintf(stderr, "[WasmNode] Instantiate failed: %s\n",
                         WasmEdge_ResultGetMessage(instantiate_res));
            WasmEdge_VMDelete(vm);
            WasmEdge_ModuleInstanceDelete(host_mod);
            restore_stdout();
            restore_stdin();
            nlohmann::json result_json;
            result_json["file"] = wasm_file;
            result_json["status"] = "error";
            result_json["reason"] = "WASM instantiation failed";
            return result_json.dump();
        }

        if (restore_agent_id > 0) {
            std::string restore_path = "/tmp/aios_tasks/agent_" + std::to_string(restore_agent_id) + ".mem";
            std::fprintf(stderr, "[WasmNode] RESTORE mode: injecting memory from %s\n", restore_path.c_str());

            std::ifstream ifs(restore_path, std::ios::binary | std::ios::ate);
            if (!ifs.is_open()) {
                std::fprintf(stderr, "[WasmNode] RESTORE failed: cannot open %s\n", restore_path.c_str());
            } else {
                std::streamsize file_size = ifs.tellg();
                ifs.seekg(0, std::ios::beg);

                uint32_t header[2] = {0, 0};
                if (!ifs.read(reinterpret_cast<char*>(header), sizeof(header))) {
                    std::fprintf(stderr, "[WasmNode] RESTORE failed: cannot read header from %s\n", restore_path.c_str());
                } else {
                    uint32_t snap_offset = header[0];
                    uint32_t snap_len = header[1];
                    std::streamsize data_file_size = file_size - sizeof(header);
                    if (snap_len > static_cast<uint32_t>(data_file_size)) {
                        snap_len = static_cast<uint32_t>(data_file_size);
                    }

                    std::vector<uint8_t> mem_data(snap_len);
                    if (ifs.read(reinterpret_cast<char*>(mem_data.data()), snap_len)) {
                        WasmEdge_MemoryInstanceContext *mem = nullptr;

                        const WasmEdge_ModuleInstanceContext *active_mod = WasmEdge_VMGetActiveModule(vm);
                        if (active_mod) {
                            uint32_t mem_count = WasmEdge_ModuleInstanceListMemoryLength(active_mod);
                            if (mem_count > 0) {
                                std::vector<WasmEdge_String> mem_names(mem_count);
                                WasmEdge_ModuleInstanceListMemory(active_mod, mem_names.data(), mem_count);
                                mem = WasmEdge_ModuleInstanceFindMemory(active_mod, mem_names[0]);
                            }
                        }

                        if (mem) {
                            uint32_t current_pages = WasmEdge_MemoryInstanceGetPageSize(mem);
                            uint32_t current_bytes = current_pages * 65536;
                            uint32_t copy_len = snap_len;
                            if (snap_offset + copy_len > current_bytes) {
                                copy_len = current_bytes - snap_offset;
                            }
                            WasmEdge_Result set_res = WasmEdge_MemoryInstanceSetData(mem, mem_data.data(), snap_offset, copy_len);
                            if (WasmEdge_ResultOK(set_res)) {
                                std::fprintf(stderr, "[WasmNode] RESTORE OK: %u bytes injected at offset %u\n", copy_len, snap_offset);
                            } else {
                                std::fprintf(stderr, "[WasmNode] RESTORE SetData failed: %s\n",
                                             WasmEdge_ResultGetMessage(set_res));
                            }
                        } else {
                            std::fprintf(stderr, "[WasmNode] RESTORE failed: cannot locate memory instance\n");
                        }
                    } else {
                        std::fprintf(stderr, "[WasmNode] RESTORE failed: cannot read %s\n", restore_path.c_str());
                    }
                }
                ifs.close();
            }
        }

        const WasmEdge_FunctionTypeContext* vfunc_type =
            WasmEdge_VMGetFunctionType(vm, func_name);
        if (!vfunc_type) {
            WasmEdge_String alt = WasmEdge_StringWrap("run", 3);
            vfunc_type = WasmEdge_VMGetFunctionType(vm, alt);
            if (vfunc_type) func_name_str = "run";
        }
        if (!vfunc_type) {
            WasmEdge_String alt = WasmEdge_StringWrap("_start", 6);
            vfunc_type = WasmEdge_VMGetFunctionType(vm, alt);
            if (vfunc_type) func_name_str = "_start";
        }
        if (!vfunc_type) {
            WasmEdge_String alt = WasmEdge_StringWrap("main", 4);
            vfunc_type = WasmEdge_VMGetFunctionType(vm, alt);
            if (vfunc_type) func_name_str = "main";
        }
        if (!vfunc_type) {
            WasmEdge_String alt = WasmEdge_StringWrap("__original_main", 15);
            vfunc_type = WasmEdge_VMGetFunctionType(vm, alt);
            if (vfunc_type) func_name_str = "__original_main";
        }
        if (!vfunc_type) {
            std::fprintf(stderr, "[WasmNode] No '%s' or 'run' or '_start' or 'main' export found\n",
                         func_name_str.c_str());
            WasmEdge_VMDelete(vm);
            WasmEdge_ModuleInstanceDelete(host_mod);
            restore_stdout();
            restore_stdin();
            nlohmann::json result_json;
            result_json["file"] = wasm_file;
            result_json["status"] = "error";
            result_json["reason"] = "No exportable function found";
            return result_json.dump();
        }

        func_name = WasmEdge_StringWrap(
            func_name_str.c_str(), static_cast<uint32_t>(func_name_str.size()));

        uint32_t param_count = WasmEdge_FunctionTypeGetParametersLength(vfunc_type);
        uint32_t return_count = WasmEdge_FunctionTypeGetReturnsLength(vfunc_type);

        std::vector<WasmEdge_Value> actual_params;
        for (uint32_t i = 0; i < param_count && i < static_cast<uint32_t>(params.size()); ++i) {
            actual_params.push_back(params[i]);
        }
        while (actual_params.size() < param_count) {
            actual_params.push_back(WasmEdge_ValueGenI32(0));
        }

        std::vector<WasmEdge_Value> returns(return_count > 0 ? return_count : 1);

        struct sigaction sa, old_sa;
        sa.sa_handler = wasm_timeout_handler;
        sigemptyset(&sa.sa_mask);
        sa.sa_flags = 0;
        sigaction(SIGALRM, &sa, &old_sa);

        g_wasm_timeout_armed = true;
        unsigned int prev_alarm = alarm(15);

        if (setjmp(g_wasm_timeout_jmp) == 0) {
            if (param_count == 0) {
                exec_res = WasmEdge_VMExecute(vm, func_name, nullptr, 0,
                                               returns.data(), returns.size());
            } else {
                exec_res = WasmEdge_VMExecute(vm, func_name,
                                               actual_params.data(), actual_params.size(),
                                               returns.data(), returns.size());
            }
        } else {
            std::fprintf(stderr, "[WasmNode] ⏰ TIMEOUT! WASM execution exceeded 15s, forcibly terminated!\n");
            exec_res = {.Code = WasmEdge_ErrCode_RuntimeError};
        }

        alarm(0);
        g_wasm_timeout_armed = false;
        sigaction(SIGALRM, &old_sa, nullptr);
        if (prev_alarm > 0) alarm(prev_alarm);

        if (WasmEdge_ResultOK(exec_res) && return_count > 0) {
            wasm_return_val = WasmEdge_ValueGetI32(returns[0]);
        }

        if (!WasmEdge_ResultOK(exec_res)) {
            const char* trap_msg = WasmEdge_ResultGetMessage(exec_res);
            std::string trap_detail(trap_msg ? trap_msg : "unknown trap");
            std::fprintf(stderr, "\033[31m[Ring 0 | Trap] 捕获到沙盒硬件异常: %s\033[0m\n",
                         trap_detail.c_str());
            KernelLogger::instance().log("[Ring 0 | Trap] 捕获到沙盒硬件异常: " + trap_detail);
            EventBus::instance().publish(EventType::WASM_TRAP, "WasmNode",
                "Trap in " + wasm_file + ": " + trap_detail);

            uint64_t crash_instr = 0;
            uint64_t crash_gas = 0;
            if (stat) {
                crash_instr = WasmEdge_StatisticsGetInstrCount(stat);
                crash_gas = WasmEdge_StatisticsGetTotalCost(stat);
            }

            CrashAnalyzer::instance().handle_wasm_crash(
                restore_agent_id > 0 ? restore_agent_id : 0,
                wasm_file,
                func_name_str,
                trap_detail,
                crash_instr,
                crash_gas
            );
        }
    }

    fflush(stdout);
    restore_stdout();
    restore_stdin();

    WasmEdge_StatisticsContext* final_stat = WasmEdge_VMGetStatisticsContext(vm);
    uint64_t gas_used = 0;
    uint64_t instr_count = 0;
    if (final_stat) {
        instr_count = WasmEdge_StatisticsGetInstrCount(final_stat);
        gas_used = WasmEdge_StatisticsGetTotalCost(final_stat);
    }

    WasmEdge_VMDelete(vm);
    WasmEdge_ModuleInstanceDelete(host_mod);

    nlohmann::json result_json;
    result_json["file"] = wasm_file;
    result_json["func"] = func_name_str;
    result_json["gas_used"] = gas_used;
    result_json["instr_count"] = instr_count;
    result_json["gas_limit"] = 50000000;

    if (WasmEdge_ResultOK(exec_res)) {
        result_json["status"] = "ok";
        result_json["exit_code"] = 0;
        if (func_name_str != "_start" || !params.empty()) {
            result_json["return_value"] = wasm_return_val;
        }
        std::fprintf(stderr, "[WasmNode] OK | func=%s | return=%d\n",
                     func_name_str.c_str(), wasm_return_val);
    } else {
        const char* trap_msg = WasmEdge_ResultGetMessage(exec_res);
        std::string trap_detail(trap_msg ? trap_msg : "unknown trap");
        result_json["status"] = "trap";
        result_json["reason"] = trap_detail;
        result_json["trap"] = trap_detail;
        std::fprintf(stderr, "[WasmNode] TRAP | func=%s | detail=%s\n",
                     func_name_str.c_str(), trap_detail.c_str());
        EventBus::instance().publish(EventType::WASM_TRAP, "WasmNode",
            "Trap result in " + wasm_file + " func=" + func_name_str + ": " + trap_detail);
    }

    return result_json.dump();
}

} // namespace aios
