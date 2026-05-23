#include <llama.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <signal.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>

static const char* UDS_PATH     = "/tmp/aios_decoder.sock";
static const int   MAX_RECV     = 4096;

static llama_model*        g_model  = nullptr;
static const llama_vocab*  g_vocab  = nullptr;
static llama_context*      g_ctx    = nullptr;
static llama_sampler*      g_grammar = nullptr;
static volatile sig_atomic_t g_running = 1;

static void signal_handler(int) { g_running = 0; }

static bool load_grammar(const std::string& path) {
    std::ifstream f(path);
    if (!f.is_open()) {
        std::printf("[Daemon] Cannot open GBNF: %s\n", path.c_str());
        return false;
    }
    std::stringstream buf;
    buf << f.rdbuf();
    std::string text = buf.str();
    if (text.empty()) return false;

    g_grammar = llama_sampler_init_grammar(g_vocab, text.c_str(), "root");
    if (!g_grammar) {
        std::printf("[Daemon] Failed to parse GBNF grammar\n");
        return false;
    }
    std::printf("[Daemon] GBNF grammar lock loaded (%zu bytes)\n", text.size());
    return true;
}

static std::string build_prompt(const std::string& intent) {
    return
        "<|im_start|>system\n"
        "You are a kernel syscall decoder. Map user intent to a flat micro-instruction.\n"
        "Format: SYS_CMD <ACTION> <ARG> EOF\n"
        "Actions: EXECUTE_TASK CANCEL_TASK VFS_READ SNAPSHOT RESTORE COMPILE_AND_EXECUTE\n"
        "Rules:\n"
        "- remember/save/store/memorize -> EXECUTE_TASK <text>\n"
        "- cancel/stop/abort -> CANCEL_TASK <agent_id>\n"
        "- list files/show files/read file -> VFS_READ <path>\n"
        "- snapshot/freeze/hibernate -> SNAPSHOT <agent_id>\n"
        "- restore/resurrect -> RESTORE <agent_id>\n"
        "- compile/编译/运行代码/执行代码/compile and run -> COMPILE_AND_EXECUTE <code>\n"
        "- anything else -> EXECUTE_TASK <text>\n"
        "Output ONLY one line in the flat format.<|im_end|>\n"
        "<|im_start|>user\n"
        "remember my name is Alice<|im_end|>\n"
        "<|im_start|>assistant\n"
        "SYS_CMD EXECUTE_TASK my_name_is_Alice EOF<|im_end|>\n"
        "<|im_start|>user\n"
        "stop agent 3<|im_end|>\n"
        "<|im_start|>assistant\n"
        "SYS_CMD CANCEL_TASK 3 EOF<|im_end|>\n"
        "<|im_start|>user\n"
        "take a snapshot<|im_end|>\n"
        "<|im_start|>assistant\n"
        "SYS_CMD SNAPSHOT 0 EOF<|im_end|>\n"
        "<|im_start|>user\n"
        "show me the files in /tmp<|im_end|>\n"
        "<|im_start|>assistant\n"
        "SYS_CMD VFS_READ /tmp EOF<|im_end|>\n"
        "<|im_start|>user\n"
        "restore agent 5<|im_end|>\n"
        "<|im_start|>assistant\n"
        "SYS_CMD RESTORE 5 EOF<|im_end|>\n"
        "<|im_start|>user\n"
        "tell me a joke<|im_end|>\n"
        "<|im_start|>assistant\n"
        "SYS_CMD EXECUTE_TASK tell_me_a_joke EOF<|im_end|>\n"
        "<|im_start|>user\n"
        "compile and run int add(int a, int b) { return a + b; }<|im_end|>\n"
        "<|im_start|>assistant\n"
        "SYS_CMD COMPILE_AND_EXECUTE int_add EOF<|im_end|>\n"
        "<|im_start|>user\n"
        "编译运行代码 int multiply(int a, int b) { return a * b; }<|im_end|>\n"
        "<|im_start|>assistant\n"
        "SYS_CMD COMPILE_AND_EXECUTE multiply_code EOF<|im_end|>\n"
        "<|im_start|>user\n" + intent + "<|im_end|>\n"
        "<|im_start|>assistant\n";
}

static std::string decode(const std::string& intent, int max_tokens = 64) {
    std::string prompt = build_prompt(intent);

    std::vector<llama_token> tokens(prompt.size() + 64);
    int n_tokens = llama_tokenize(g_vocab, prompt.c_str(), prompt.size(),
                                  tokens.data(), tokens.size(), true, false);
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(g_vocab, prompt.c_str(), prompt.size(),
                                  tokens.data(), tokens.size(), true, false);
    }
    if (n_tokens < 0) return "SYS_CMD EXECUTE_TASK error EOF";
    tokens.resize(n_tokens);

    llama_memory_t mem = llama_get_memory(g_ctx);
    llama_memory_clear(mem, true);

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());
    if (llama_decode(g_ctx, batch) != 0) {
        return "SYS_CMD EXECUTE_TASK error EOF";
    }

    llama_sampler* smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (g_grammar) {
        llama_sampler* gram_copy = llama_sampler_clone(g_grammar);
        llama_sampler_chain_add(smpl, gram_copy);
    }
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(10));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.1f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string result;
    result.reserve(256);

    try {
        llama_token next_token = llama_sampler_sample(smpl, g_ctx, -1);
        for (int i = 0; i < max_tokens; ++i) {
            if (llama_vocab_is_eog(g_vocab, next_token)) break;

            char buf[128];
            int n_chars = llama_token_to_piece(g_vocab, next_token, buf, sizeof(buf), 0, true);
            if (n_chars > 0) result.append(buf, n_chars);

            if (result.size() >= 3) {
                auto pos = result.rfind("EOF");
                if (pos != std::string::npos) {
                    result = result.substr(0, pos + 3);
                    break;
                }
            }

            batch = llama_batch_get_one(&next_token, 1);
            if (llama_decode(g_ctx, batch) != 0) break;
            next_token = llama_sampler_sample(smpl, g_ctx, -1);
        }
    } catch (const std::exception& e) {
        std::printf("[Daemon] Grammar sampling exception: %s\n", e.what());
    }

    llama_sampler_free(smpl);
    return result;
}

int main(int argc, char* argv[]) {
    std::printf("=== aios_decoder - Kernel Instruction Decoder Daemon ===\n\n");

    signal(SIGINT, signal_handler);
    signal(SIGTERM, signal_handler);

    const char* model_path   = (argc >= 2) ? argv[1] : "./models/qwen2.5-0.5b-instruct-q4_k_m.gguf";
    const char* grammar_path = (argc >= 3) ? argv[2] : "./grammar/syscall_flat.gbnf";
    const char* sock_path    = (argc >= 4) ? argv[3] : UDS_PATH;

    llama_backend_init();

    auto m_params = llama_model_default_params();
    m_params.n_gpu_layers = 0;
    g_model = llama_model_load_from_file(model_path, m_params);
    if (!g_model) {
        std::printf("[Daemon] FATAL: Cannot load model: %s\n", model_path);
        return 1;
    }
    std::printf("[Daemon] Model loaded: %s\n", model_path);

    g_vocab = llama_model_get_vocab(g_model);

    auto c_params = llama_context_default_params();
    c_params.n_ctx     = 512;
    c_params.n_batch   = 512;
    c_params.n_seq_max = 1;
    g_ctx = llama_init_from_model(g_model, c_params);
    if (!g_ctx) {
        std::printf("[Daemon] FATAL: Cannot create llama context\n");
        llama_model_free(g_model);
        return 1;
    }

    if (!load_grammar(grammar_path)) {
        std::printf("[Daemon] WARNING: Running WITHOUT grammar lock\n");
    }

    std::printf("[Daemon] Decoder READY | vocab=%d | grammar=%s\n",
                llama_vocab_n_tokens(g_vocab), g_grammar ? "LOCKED" : "UNLOCKED");

    unlink(sock_path);

    int server_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (server_fd < 0) {
        std::printf("[Daemon] FATAL: socket() failed: %s\n", std::strerror(errno));
        return 1;
    }

    sockaddr_un addr{};
    addr.sun_family = AF_UNIX;
    std::strncpy(addr.sun_path, sock_path, sizeof(addr.sun_path) - 1);

    if (bind(server_fd, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) < 0) {
        std::printf("[Daemon] FATAL: bind() failed: %s\n", std::strerror(errno));
        close(server_fd);
        return 1;
    }

    if (listen(server_fd, 16) < 0) {
        std::printf("[Daemon] FATAL: listen() failed: %s\n", std::strerror(errno));
        close(server_fd);
        unlink(sock_path);
        return 1;
    }

    std::printf("[Daemon] UDS listening on %s\n", sock_path);
    std::printf("[Daemon] Waiting for kernel connections...\n\n");

    while (g_running) {
        int client_fd = accept(server_fd, nullptr, nullptr);
        if (client_fd < 0) {
            if (errno == EINTR) continue;
            std::printf("[Daemon] accept() error: %s\n", std::strerror(errno));
            continue;
        }

        char buf[MAX_RECV];
        memset(buf, 0, sizeof(buf));
        ssize_t n = recv(client_fd, buf, sizeof(buf) - 1, 0);
        if (n <= 0) {
            close(client_fd);
            continue;
        }

        std::string intent(buf, static_cast<size_t>(n));
        std::printf("[Daemon] <<< Recv: \"%s\"\n", intent.c_str());

        std::string flat = decode(intent);
        std::printf("[Daemon] >>> Send: \"%s\"\n", flat.c_str());

        send(client_fd, flat.c_str(), flat.size(), 0);
        close(client_fd);
    }

    std::printf("\n[Daemon] Shutting down...\n");
    close(server_fd);
    unlink(sock_path);

    if (g_grammar) llama_sampler_free(g_grammar);
    if (g_ctx)     llama_free(g_ctx);
    if (g_model)   llama_model_free(g_model);
    llama_backend_free();

    std::printf("[Daemon] Clean exit.\n");
    return 0;
}
