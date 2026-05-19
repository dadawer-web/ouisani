#include "aios/agent_task.h"
#include "aios/llm_adapter.h"
#include "aios/memory_manager.h"
#include "aios/syscall_handler.h"
#include "aios/syscall_server.h"
#include "aios/task_scheduler.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <memory>
#include <signal.h>
#include <string>

static std::atomic<bool> g_running{true};

static void signal_handler(int) {
    g_running.store(false);
}

int main(int argc, char* argv[]) {
    std::string llm_url = "http://localhost:11434";
    std::string llm_model = "qwen2.5:7b";

    if (argc >= 2) {
        llm_model = argv[1];
    }

    std::printf("=== AIOS Core v0.5.0 - LLM Pipeline Integration ===\n\n");

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    auto llm = std::make_shared<aios::LlmAdapter>(llm_url, llm_model, 120);
    auto memory_mgr = std::make_shared<aios::MemoryManager>(5, "./swap");

    if (llm->is_available()) {
        std::printf("[Main] Ollama service detected at %s (model=%s)\n", llm_url.c_str(), llm_model.c_str());
    } else {
        std::printf("[Main] WARNING: Ollama service not available at %s\n", llm_url.c_str());
        std::printf("[Main] LLM calls will return error messages. Start Ollama first for full pipeline.\n");
    }

    auto scheduler = std::make_shared<aios::TaskScheduler>(2, llm, memory_mgr);
    scheduler->start();

    auto handler = std::make_shared<aios::SyscallHandler>(
        [scheduler](int agent_id, int priority, const std::string& payload) {
            auto task = std::make_shared<aios::AgentTask>(
                agent_id,
                priority,
                aios::TaskStatus::READY,
                payload
            );
            scheduler->submit(std::move(task));
        },
        memory_mgr
    );

    aios::SyscallServer server(handler, "0.0.0.0", 8080);
    server.start();

    std::printf("[Main] AIOS Core is running. Press Ctrl+C to stop.\n");
    std::printf("[Main] Pipeline: Context Injection -> Prompt Formatting -> LLM -> Memory Update\n");
    std::printf("[Main] Supported syscalls: WRITE_MEMORY, READ_MEMORY, EXECUTE_TOOL\n\n");

    while (g_running.load()) {
        std::this_thread::sleep_for(std::chrono::milliseconds(200));
    }

    std::printf("\n[Main] Signal received, shutting down...\n");
    server.shutdown();
    scheduler->shutdown();

    std::printf("[Main] AIOS Core exited cleanly.\n");
    return 0;
}
