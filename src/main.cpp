#include "aios/agent_task.h"
#include "aios/agent_registry.h"
#include "aios/cache_manager.h"
#include "aios/env_loader.h"
#include "aios/instruction_decoder.h"
#include "aios/kernel_logger.h"
#include "aios/llm_adapter.h"
#include "aios/llm_provider.h"
#include "aios/llm_router.h"
#include "aios/http_node.h"
#include "aios/mcp_server.h"
#include "aios/memory_manager.h"
#include "aios/sandbox_driver.h"
#include "aios/scheduler_strategy.h"
#include "aios/security_guard.h"
#include "aios/syscall_server.h"
#include "aios/task_scheduler.h"
#include "aios/vfs_manager.h"
#include "aios/vfs_node.h"
#include "aios/wasm_node.h"
#include "aios/process_manager.h"
#include "aios/camera_node.h"
#include "aios/module_manager.h"
#include "aios/openai_server.h"
#include "aios/semantic_node.h"
#include "aios/vector_node.h"
#include "aios/event_bus.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <fcntl.h>
#include <memory>
#include <signal.h>
#include <string>
#include <sys/file.h>
#include <unistd.h>

namespace aios {

class ProcTopNode : public VfsNode {
public:
    ProcTopNode(const std::string& path, std::shared_ptr<TaskScheduler> scheduler)
        : VfsNode(VfsNodeType::FILE, path), scheduler_(std::move(scheduler)) {}

    std::string read() const override {
        if (!scheduler_) return "[ERROR] No scheduler bound\n";
        return scheduler_->GetSystemStat();
    }

private:
    std::shared_ptr<TaskScheduler> scheduler_;
};

class ProcAgentsNode : public VfsNode {
public:
    ProcAgentsNode(const std::string& path)
        : VfsNode(VfsNodeType::FILE, path) {}

    std::string read() const override {
        return ProcessManager::instance().generate_proc_agents();
    }
};

class KmsgNode : public VfsNode {
public:
    KmsgNode(const std::string& path)
        : VfsNode(VfsNodeType::FILE, path) {}

    std::string read() const override {
        return KernelLogger::instance().dump_logs();
    }
};

class EventsNode : public VfsNode {
public:
    EventsNode(const std::string& path)
        : VfsNode(VfsNodeType::FILE, path) {}

    std::string read() const override {
        return EventBus::instance().dump_events();
    }
};

}

static std::atomic<bool> g_running{true};

static void signal_handler(int) {
    g_running.store(false);
}

int main(int argc, char* argv[]) {
    std::printf("=== AIOS Core v1.8.0 - Checkpointing & Hibernation ===\n\n");

    static int lock_fd = ::open("/tmp/aios_core.lock", O_CREAT | O_RDWR, 0666);
    if (lock_fd >= 0) {
        if (flock(lock_fd, LOCK_EX | LOCK_NB) != 0) {
            std::printf("[Main] FATAL: Another AIOS Core instance is already running!\n");
            std::printf("[Main] Kill it first: pkill -9 aios_core\n");
            return 1;
        }
    }

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    auto env = aios::EnvLoader::load(".env");

    std::string api_key = aios::EnvLoader::get(env, "OPENAI_API_KEY", "");
    std::string base_url = aios::EnvLoader::get(env, "OPENAI_BASE_URL", "https://api.openai.com");
    std::string model = aios::EnvLoader::get(env, "OPENAI_MODEL", "gpt-3.5-turbo");

    if (argc >= 2) api_key = argv[1];
    if (argc >= 3) base_url = argv[2];
    if (argc >= 4) model = argv[3];

    if (api_key.empty()) {
        std::printf("[Main] WARNING: No API key found! LLM calls will fail.\n");
    } else {
        std::printf("[Main] API Key: %s...%s\n", api_key.substr(0, 6).c_str(), api_key.substr(api_key.size() - 4).c_str());
    }
    std::printf("[Main] Base URL: %s\n", base_url.c_str());
    std::printf("[Main] Model: %s\n", model.c_str());

    auto llm = std::make_shared<aios::LlmAdapter>(api_key, base_url, model, 120);
    aios::WasmNode::SetGlobalLlm(llm);

    std::string emb_key = aios::EnvLoader::get(env, "EMBEDDING_API_KEY", "");
    std::string emb_url = aios::EnvLoader::get(env, "EMBEDDING_BASE_URL", "");
    std::string emb_model = aios::EnvLoader::get(env, "EMBEDDING_MODEL", "");

    if (!emb_key.empty() && !emb_url.empty()) {
        llm->set_embedding_config(emb_key, emb_url, emb_model);
        std::printf("[Main] Embedding: %s model=%s\n", emb_url.c_str(), emb_model.c_str());
    } else {
        std::printf("[Main] WARNING: No embedding config, semantic paging disabled\n");
    }

    auto memory_mgr = std::make_shared<aios::MemoryManager>(15, 5, 10, 5, "./swap");

    auto& registry = aios::AgentRegistry::instance();
    registry.register_agent(0, aios::PrivilegeLevel::RING_0);

    auto scheduler = std::make_shared<aios::TaskScheduler>(4, 8, llm, memory_mgr);

    auto sandbox = std::make_shared<aios::SandboxDriver>("http://127.0.0.1:5000", 10);
    scheduler->register_driver("python_sandbox", sandbox);

    auto& vfs = aios::VfsManager::instance();
    vfs.init();
    auto sandbox_exec = std::make_shared<aios::ExecutableNode>("/bin/sandbox", sandbox);
    vfs.mount("/bin", "sandbox", sandbox_exec);

    auto version_file = std::make_shared<aios::FileNode>("/proc/version", "AIOS Core v1.5.0 (VFS + Ring 0/3 + SecurityGuard)");
    vfs.mount("/proc", "version", version_file);

    auto agent_top = std::make_shared<aios::ProcTopNode>("/proc/agent_top", scheduler);
    vfs.mount("/proc", "agent_top", agent_top);

    auto proc_agents = std::make_shared<aios::ProcAgentsNode>("/proc/agents");
    vfs.mount("/proc", "agents", proc_agents);

    auto proc_kmsg = std::make_shared<aios::KmsgNode>("/proc/kmsg");
    vfs.mount("/proc", "kmsg", proc_kmsg);

    auto proc_events = std::make_shared<aios::EventsNode>("/proc/events");
    vfs.mount("/proc", "events", proc_events);

    auto dev_mem = std::make_shared<aios::DirectoryNode>("/dev/mem");
    vfs.mount("/dev", "mem", dev_mem);

    auto semantic_dev = std::make_shared<aios::SemanticNode>(
        "/dev/semantic",
        [scheduler](std::shared_ptr<aios::AgentTask> task) {
            scheduler->submit_llm(std::move(task));
        },
        [scheduler](std::shared_ptr<aios::AgentTask> task) {
            scheduler->submit(std::move(task));
        },
        memory_mgr
    );
    vfs.mount("/dev", "semantic", semantic_dev);

    auto vec_mem_101 = std::make_shared<aios::VectorNode>("/dev/vec_mem_101", llm, 101, 0600);
    vfs.mount("/dev", "vec_mem_101", vec_mem_101);

    auto dev_net = std::make_shared<aios::DirectoryNode>("/dev/net");
    vfs.mount("/dev", "net", dev_net);

    auto http_node = std::make_shared<aios::HttpNode>("/dev/net/http");
    vfs.mount("/dev/net", "http", http_node);

    auto camera0 = std::make_shared<aios::CameraNode>("/dev/camera0");
    vfs.mount("/dev", "camera0", camera0);

    auto tmp = std::make_shared<aios::DirectoryNode>("/tmp");
    vfs.mount("/", "tmp", tmp);
    auto pipes = std::make_shared<aios::DirectoryNode>("/tmp/pipes");
    vfs.mount("/tmp", "pipes", pipes);

    auto pipe_101_102 = std::make_shared<aios::PipeNode>("/tmp/pipes/agent_101_to_102");
    vfs.mount("/tmp/pipes", "agent_101_to_102", pipe_101_102);

    auto var_dir = std::make_shared<aios::DirectoryNode>("/var");
    vfs.mount("/", "var", var_dir);
    auto snapshots_dir = std::make_shared<aios::DirectoryNode>("/var/snapshots");
    vfs.mount("/var", "snapshots", snapshots_dir);

    auto& cache_mgr = aios::CacheManager::instance();
    cache_mgr.set_threshold(0.90f);
    cache_mgr.set_max_entries(1000);

    std::string decoder_sock = aios::EnvLoader::get(env, "DECODER_UDS_PATH", "/tmp/aios_decoder.sock");

    auto& decoder = aios::InstructionDecoder::GetInstance();
    bool decoder_ok = decoder.Initialize(decoder_sock);

    aios::SyscallServer server(
        [scheduler](std::shared_ptr<aios::AgentTask> task) {
            scheduler->submit(std::move(task));
        },
        [scheduler](int agent_id) {
            scheduler->cancel_agent(agent_id);
        },
        [scheduler](int agent_id) {
            scheduler->ping_heartbeat(agent_id);
        },
        "0.0.0.0", 8080
    );

    server.set_submit_llm_fn([scheduler](std::shared_ptr<aios::AgentTask> task) {
        scheduler->submit_llm(std::move(task));
    });

    scheduler->set_response_callback([&server](int fd, const std::string& resp) {
        server.enqueue_response(fd, resp);
    });

    scheduler->set_strategy(std::make_shared<aios::PrioritySchedulerStrategy>());

    auto& router = aios::LlmRouter::instance();
    router.register_provider(std::make_shared<aios::LocalOllamaProvider>());
    router.register_provider(std::make_shared<aios::CloudGptProvider>(llm));

    scheduler->start();

    aios::ModuleManager::instance().init("./usr_lib_wasm");

    server.start();

    aios::McpServer mcp_server(
        8081,
        [scheduler](std::shared_ptr<aios::AgentTask> task) {
            scheduler->submit(std::move(task));
        },
        [scheduler](std::shared_ptr<aios::AgentTask> task) {
            scheduler->submit_llm(std::move(task));
        }
    );
    std::thread mcp_thread([&mcp_server]() {
        mcp_server.start();
    });

    aios::OpenAiServer openai_server(8082);
    std::thread openai_thread([&openai_server]() {
        openai_server.start();
    });

    std::printf("[Main] AIOS Core is running (Checkpointing & Hibernation)\n");
    std::printf("[Main] I/O: epoll LT + eventfd (Reactor)\n");
    std::printf("[Main] Dispatch: ThreadPool x4 | IOPool: ThreadPool x8\n");
    std::printf("[Main] MMU: Semantic Paging + Memory Compression\n");
    std::printf("[Main] Interrupt: CANCEL_TASK + is_cancelled token\n");
    std::printf("[Main] Watchdog: Sandbox timeout=10s | LLM timeout=120s\n");
    std::printf("[Main] Security: Ring 0/3 Privilege + SecurityGuard Code Scanner\n");
    std::printf("[Main] VFS: /bin/sandbox /bin/wasm_sandbox /proc/version /proc/agent_top /proc/agents /proc/kmsg /dev/mem /dev/semantic /tmp/pipes /var/snapshots\n");
    std::printf("[Main] TLB: Semantic Cache (threshold=0.90, max=1000)\n");
    std::printf("[Main] IPC: PipeNode (blocking read + notify write)\n");
    std::printf("[Main] Checkpoint: SNAPSHOT / RESTORE (process hibernation)\n");
    std::printf("[Main] Reaper: Zombie detection (30s idle -> SIGKILL)\n");
    std::printf("[Main] Auto-restore: Scan /tmp/aios_tasks/ on boot\n");
    std::printf("[Main] Module Store: COMPILE_ONLY / EXECUTE_MODULE (LRU cache=%zu)\n", (size_t)16);
    std::printf("[Main] LLM Scheduler: Priority-driven LLM_INFERENCE queue (dedicated worker thread)\n");
    std::printf("[Main] MCP Server: 127.0.0.1:8081 (JSON-RPC 2.0 / Model Context Protocol)\n");
    std::printf("[Main] OpenAI API: 127.0.0.1:8082 (/v1/chat/completions compatible gateway)\n");
    std::printf("[Main] Decoder: %s | UDS: %s\n",
                decoder_ok ? "VIA DAEMON (UDS)" : "DISABLED",
                decoder_sock.c_str());
    std::printf("[Main] Embedding: %s\n\n", llm->has_embedding_config() ? "ENABLED" : "DISABLED");

    while (g_running.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }

    std::printf("\n[Main] Signal received, shutting down...\n");
    openai_server.shutdown();
    if (openai_thread.joinable()) {
        openai_thread.join();
    }
    mcp_server.shutdown();
    if (mcp_thread.joinable()) {
        mcp_thread.join();
    }
    server.shutdown();
    scheduler->shutdown();
    std::printf("[Main] AIOS Core exited cleanly.\n");
    return 0;
}
