#include "aios/kexec_manager.h"
#include "aios/agent_registry.h"
#include "aios/cgroup_manager.h"
#include "aios/event_bus.h"
#include "aios/kernel_logger.h"
#include "aios/process_manager.h"
#include "aios/syscall_server.h"
#include "aios/task_scheduler.h"
#include "aios/token_mmu.h"
#include "aios/vfs_manager.h"
#include "aios/vfs_node.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <sstream>
#include <unistd.h>
#include <sys/wait.h>

namespace aios {

static const char* KEXEC_STATE_PATH = "/tmp/aios_kexec_state.json";
static const char* KEXEC_ARG_PREFIX = "--kexec-state=";

KexecManager& KexecManager::instance() {
    static KexecManager inst;
    return inst;
}

void KexecManager::trigger_kexec(const std::string& new_kernel_binary_path) {
    std::printf("\n");
    std::printf("  ╔══════════════════════════════════════════════════════════════╗\n");
    std::printf("  ║  🔥 KEXEC — Kernel Hot-Swap Initiated                     ║\n");
    std::printf("  ║  Replacing kernel with: %-34s ║\n", new_kernel_binary_path.c_str());
    std::printf("  ╚══════════════════════════════════════════════════════════════╝\n");
    std::printf("\n");

    if (!std::filesystem::exists(new_kernel_binary_path)) {
        std::printf("[kexec] ❌ FATAL: New kernel binary not found: %s\n",
                    new_kernel_binary_path.c_str());
        KernelLogger::instance().log(
            "[Ring 0 | kexec] ABORTED: binary not found: " + new_kernel_binary_path);
        return;
    }

    std::printf("[kexec] Phase 1/5: Suspending all agents...\n");
    suspend_all_agents();

    std::printf("[kexec] Phase 2/5: Collecting agent snapshots...\n");
    nlohmann::json agent_snapshots = collect_all_agent_snapshots();

    std::printf("[kexec] Phase 3/5: Serializing VFS, pipes, and cgroup state...\n");
    nlohmann::json vfs_state = serialize_vfs_state();
    nlohmann::json pipe_state = serialize_pipe_state();
    nlohmann::json cgroup_state = serialize_cgroup_state();

    nlohmann::json kexec_state;
    kexec_state["version"] = "1.0";
    kexec_state["kexec_timestamp"] = std::chrono::system_clock::now().time_since_epoch().count();
    kexec_state["agents"] = agent_snapshots;
    kexec_state["vfs"] = vfs_state;
    kexec_state["pipes"] = pipe_state;
    kexec_state["cgroups"] = cgroup_state;

    std::printf("[kexec] Phase 4/5: Writing kexec state to %s...\n", KEXEC_STATE_PATH);
    if (!write_kexec_state(KEXEC_STATE_PATH, kexec_state)) {
        std::printf("[kexec] ❌ FATAL: Failed to write kexec state. Aborting.\n");
        return;
    }

    std::printf("[kexec] Phase 5/5: Graceful shutdown and execve...\n");
    graceful_shutdown();

    do_execve(new_kernel_binary_path, KEXEC_STATE_PATH);

    std::printf("[kexec] ❌ FATAL: execve returned! This should never happen.\n");
}

void KexecManager::suspend_all_agents() {
    auto& pm = ProcessManager::instance();
    auto ptable = pm.get_ptable();

    int suspended = 0;
    for (auto& [pid, proc] : ptable) {
        if (proc.state == ProcessState::RUNNING || proc.state == ProcessState::SLEEPING) {
            pm.set_sleeping(pid);
            suspended++;
            std::printf("[kexec]   Agent#%d → SLEEPING\n", pid);
        }
    }

    std::printf("[kexec]   %d agents suspended\n", suspended);
    KernelLogger::instance().log(
        "[Ring 0 | kexec] " + std::to_string(suspended) + " agents suspended");
}

nlohmann::json KexecManager::collect_all_agent_snapshots() {
    auto& pm = ProcessManager::instance();
    auto ptable = pm.get_ptable();

    nlohmann::json agents_arr = nlohmann::json::array();
    int exported = 0;

    for (auto& [pid, proc] : ptable) {
        if (proc.state == ProcessState::ZOMBIE) continue;

        std::string snapshot_str = pm.export_snapshot(pid);
        if (!snapshot_str.empty()) {
            try {
                auto snapshot_json = nlohmann::json::parse(snapshot_str);
                agents_arr.push_back(snapshot_json);
                exported++;
                std::printf("[kexec]   Agent#%d snapshot exported (%zu bytes)\n",
                            pid, snapshot_str.size());
            } catch (const nlohmann::json::parse_error& e) {
                std::printf("[kexec]   Agent#%d snapshot parse error: %s\n", pid, e.what());
            }
        }
    }

    std::printf("[kexec]   %d agent snapshots collected\n", exported);
    return agents_arr;
}

nlohmann::json KexecManager::serialize_vfs_state() {
    auto& vfs = VfsManager::instance();
    std::string tree_str = vfs.tree("/", 0, "/");

    nlohmann::json vfs_state;
    vfs_state["tree"] = tree_str;

    nlohmann::json files = nlohmann::json::array();

    std::function<void(const std::string&, const std::shared_ptr<VfsNode>&)> walk;
    walk = [&](const std::string& path, const std::shared_ptr<VfsNode>& node) {
        if (!node) return;

        if (node->node_type() == VfsNodeType::FILE) {
            try {
                std::string content = node->read();
                nlohmann::json f;
                f["path"] = path;
                f["type"] = "file";
                f["size"] = content.size();
                f["content"] = content;
                files.push_back(f);
            } catch (...) {}
        } else if (node->node_type() == VfsNodeType::DIRECTORY) {
            auto dir = std::dynamic_pointer_cast<DirectoryNode>(node);
            if (dir) {
                auto child_names = dir->list_children();
                for (const auto& name : child_names) {
                    auto child = dir->get_child(name);
                    if (child) {
                        std::string child_path = (path == "/") ? "/" + name : path + "/" + name;
                        walk(child_path, child);
                    }
                }
            }
        } else {
            try {
                std::string content = node->read();
                nlohmann::json f;
                f["path"] = path;
                f["type"] = "device";
                f["node_type"] = static_cast<int>(node->node_type());
                f["size"] = content.size();
                f["content"] = content;
                files.push_back(f);
            } catch (...) {}
        }
    };

    auto root = vfs.resolve_path("/", 0);
    if (root) {
        walk("/", root);
    }

    vfs_state["files"] = files;
    vfs_state["file_count"] = files.size();
    std::printf("[kexec]   VFS: %zu nodes serialized\n", files.size());

    return vfs_state;
}

nlohmann::json KexecManager::serialize_pipe_state() {
    auto& vfs = VfsManager::instance();
    nlohmann::json pipes_arr = nlohmann::json::array();

    auto pipes_dir = vfs.resolve_path("/tmp/pipes", 0);
    if (!pipes_dir) return pipes_arr;

    auto dir = std::dynamic_pointer_cast<DirectoryNode>(pipes_dir);
    if (!dir) return pipes_arr;

    auto child_names = dir->list_children();
    for (const auto& name : child_names) {
        auto child = dir->get_child(name);
        if (child && child->node_type() == VfsNodeType::PIPE) {
            auto pipe = std::dynamic_pointer_cast<PipeNode>(child);
            if (pipe) {
                nlohmann::json p;
                p["path"] = "/tmp/pipes/" + name;
                p["name"] = name;
                p["has_data"] = (pipe->queue_size() > 0);
                pipes_arr.push_back(p);
            }
        }
    }

    std::printf("[kexec]   Pipes: %zu serialized\n", pipes_arr.size());
    return pipes_arr;
}

nlohmann::json KexecManager::serialize_cgroup_state() {
    auto& cgm = CgroupManager::instance();
    std::string info = cgm.dump_tree();

    nlohmann::json cg;
    cg["tree_dump"] = info;
    std::printf("[kexec]   Cgroups: serialized\n");
    return cg;
}

bool KexecManager::write_kexec_state(const std::string& path, const nlohmann::json& state) {
    try {
        std::ofstream ofs(path, std::ios::trunc);
        if (!ofs.is_open()) {
            std::printf("[kexec] ❌ Cannot open %s for writing\n", path.c_str());
            return false;
        }
        ofs << state.dump(2);
        ofs.close();

        auto file_size = std::filesystem::file_size(path);
        std::printf("[kexec]   State written: %s (%zu bytes)\n",
                    path.c_str(), file_size);
        return true;
    } catch (const std::exception& e) {
        std::printf("[kexec] ❌ Write error: %s\n", e.what());
        return false;
    }
}

void KexecManager::graceful_shutdown() {
    std::printf("[kexec]   Shutting down servers and scheduler...\n");

    auto& pm = ProcessManager::instance();
    pm.set_scheduler(nullptr);
    pm.set_memory_manager(nullptr);

    std::printf("[kexec]   Shutdown sequence complete. Ready for execve.\n");
}

void KexecManager::do_execve(const std::string& binary_path, const std::string& state_path) {
    std::printf("\n");
    std::printf("  ╔══════════════════════════════════════════════════════════════╗\n");
    std::printf("  ║  🔥 KEXEC — Executing process replacement...               ║\n");
    std::printf("  ║  New kernel: %-46s ║\n", binary_path.c_str());
    std::printf("  ║  State file: %-46s ║\n", state_path.c_str());
    std::printf("  ╚══════════════════════════════════════════════════════════════╝\n");
    std::printf("\n");

    KernelLogger::instance().log(
        "[Ring 0 | kexec] EXECVE: " + binary_path + " --kexec-state=" + state_path);

    std::fflush(stdout);
    std::fflush(stderr);

    std::string arg = std::string(KEXEC_ARG_PREFIX) + state_path;

    char* argv[] = {
        const_cast<char*>(binary_path.c_str()),
        const_cast<char*>(arg.c_str()),
        nullptr
    };

    char* envp[] = { nullptr };

    int ret = execve(binary_path.c_str(), argv, envp);

    if (ret < 0) {
        std::printf("[kexec] ❌ execve FAILED: %s (errno=%d: %s)\n",
                    binary_path.c_str(), errno, std::strerror(errno));
        std::printf("[kexec] Falling back to fork+exec strategy...\n");

        pid_t pid = fork();
        if (pid == 0) {
            char* child_argv[] = {
                const_cast<char*>(binary_path.c_str()),
                const_cast<char*>(arg.c_str()),
                nullptr
            };
            execv(binary_path.c_str(), child_argv);
            _exit(127);
        } else if (pid > 0) {
            std::printf("[kexec] New kernel started as PID %d\n", pid);
            std::printf("[kexec] Old kernel exiting gracefully...\n");
            _exit(0);
        } else {
            std::printf("[kexec] ❌ fork() also failed: %s\n", std::strerror(errno));
        }
    }
}

bool KexecManager::has_kexec_state(int argc, char* argv[]) {
    for (int i = 1; i < argc; i++) {
        if (argv[i] && std::string(argv[i]).find(KEXEC_ARG_PREFIX) == 0) {
            return true;
        }
    }
    return false;
}

std::string KexecManager::get_kexec_state_path(int argc, char* argv[]) {
    for (int i = 1; i < argc; i++) {
        if (argv[i]) {
            std::string arg(argv[i]);
            auto pos = arg.find(KEXEC_ARG_PREFIX);
            if (pos == 0) {
                return arg.substr(strlen(KEXEC_ARG_PREFIX));
            }
        }
    }
    return "";
}

void KexecManager::restore_from_kexec(const std::string& state_path) {
    std::printf("\n");
    std::printf("  ╔══════════════════════════════════════════════════════════════╗\n");
    std::printf("  ║  ⚡ KEXEC RESTORE — Resurrecting from previous kernel      ║\n");
    std::printf("  ║  State file: %-46s ║\n", state_path.c_str());
    std::printf("  ╚══════════════════════════════════════════════════════════════╝\n");
    std::printf("\n");

    std::ifstream ifs(state_path);
    if (!ifs.is_open()) {
        std::printf("[kexec-restore] ❌ Cannot open state file: %s\n", state_path.c_str());
        return;
    }

    std::string content((std::istreambuf_iterator<char>(ifs)),
                         std::istreambuf_iterator<char>());
    ifs.close();

    nlohmann::json state;
    try {
        state = nlohmann::json::parse(content);
    } catch (const nlohmann::json::parse_error& e) {
        std::printf("[kexec-restore] ❌ JSON parse error: %s\n", e.what());
        return;
    }

    std::printf("[kexec-restore] State file loaded (%zu bytes)\n", content.size());

    if (state.contains("vfs") && state["vfs"].contains("files")) {
        auto& vfs = VfsManager::instance();
        auto& files = state["vfs"]["files"];
        int vfs_restored = 0;

        for (const auto& f : files) {
            std::string path = f.value("path", "");
            std::string type = f.value("type", "");
            std::string file_content = f.value("content", "");

            if (path.empty() || type != "file") continue;

            auto node = vfs.resolve_path(path, 0);
            if (node) {
                node->write(file_content);
                vfs_restored++;
            }
        }

        std::printf("[kexec-restore] VFS: %d file contents restored\n", vfs_restored);
    }

    if (state.contains("cgroups") && state["cgroups"].contains("tree_dump")) {
        std::printf("[kexec-restore] Cgroups: state preserved in dump (re-apply if needed)\n");
    }

    if (state.contains("agents") && state["agents"].is_array()) {
        auto& pm = ProcessManager::instance();
        int agents_restored = 0;

        for (const auto& agent_snap : state["agents"]) {
            if (!agent_snap.contains("pcb")) continue;

            int agent_id = agent_snap["pcb"].value("agent_id", -1);
            if (agent_id < 0) continue;

            std::string snapshot_str = agent_snap.dump();
            bool ok = pm.import_snapshot(agent_id, snapshot_str);
            if (ok) {
                agents_restored++;
                std::printf("[kexec-restore]   ⚡ Agent#%d RESURRECTED from kexec state\n", agent_id);
            } else {
                std::printf("[kexec-restore]   ❌ Agent#%d import failed\n", agent_id);
            }
        }

        std::printf("[kexec-restore] %d agents resurrected from previous kernel\n", agents_restored);

        KernelLogger::instance().log(
            "[Ring 0 | kexec-restore] " + std::to_string(agents_restored) +
            " agents resurrected from kexec state");
    }

    std::printf("\n");
    std::printf("  ╔══════════════════════════════════════════════════════════════╗\n");
    std::printf("  ║  ⚡ KEXEC RESTORE COMPLETE — New kernel is live!           ║\n");
    std::printf("  ╚══════════════════════════════════════════════════════════════╝\n");
    std::printf("\n");
}

} // namespace aios
