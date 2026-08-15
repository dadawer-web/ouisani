package com.ouisani.aios.core.remote;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Modal 执行器 — 通过 {@code modal run} CLI 在 Modal.com serverless GPU 上执行函数。
 * <p>
 * <b>命令构造</b>：
 * <pre>{@code
 * modal run <appPath>::<functionName> --args-json '<json>'
 * }</pre>
 * 其中 {@code <json>} 是 {@code argsMap} 的 JSON 序列化，包含 {@code command}（用户命令）、
 * {@code workingDir}、以及 {@code config.env()} 中前缀为 {@code MODAL_ARG_} 的业务参数（剥离前缀后作为 key）。
 * <p>
 * <b>Token 安全</b>：Modal 凭据经 env Map（{@code MODAL_TOKEN_ID}/{@code MODAL_TOKEN_SECRET}/
 * {@code MODAL_WORKSPACE}）注入到 {@link CommandRunner#run} 的 env 参数，<b>绝不</b>出现在命令行
 * （避免 {@code ps aux} 泄露）。env 字段优先于环境变量。
 * <p>
 * <b>stdout 解析</b>：取末行非空内容作为函数返回值（Modal CLI 把 return 值打到 stdout 末行）。
 * <p>
 * <b>设计选择</b>：
 * <ul>
 *   <li>shell out 到 {@code modal} CLI 而非 REST API — 与 SSH/Slurm 设计统一，{@code modal run} 不需要
 *       预先 {@code modal deploy}，REST 鉴权复杂度留作 R4.1</li>
 *   <li>{@code --args-json} 是 R4 MVP 简化（真实 Modal CLI 走 {@code @app.local_entrypoint} 参数声明）；
 *       R4.1 切 REST API 时可改为标准 Modal 参数传递</li>
 *   <li>非零退出码不抛异常，返回 {@link RemoteResult#failure}</li>
 *   <li>{@code IllegalArgumentException}（config 缺 appPath/functionName）在 {@link #execute} 内
 *       try-catch 后转 {@link RemoteResult#configError}，与 SshExecutor/SlurmExecutor 错误传播一致</li>
 * </ul>
 *
 * @see RemoteExecutor
 * @see SshExecutor
 */
public final class ModalExecutor implements RemoteExecutor {

    private static final Logger log = LoggerFactory.getLogger(ModalExecutor.class);
    private static final Gson GSON = new Gson();

    /** env 中前缀为 MODAL_ARG_ 的项被剥离前缀后作为业务参数加入 argsMap。 */
    private static final String MODAL_ARG_PREFIX = "MODAL_ARG_";

    private final CommandRunner runner;

    /** 生产构造器：用 {@link DefaultCommandRunner#INSTANCE}。 */
    public ModalExecutor() {
        this(DefaultCommandRunner.INSTANCE);
    }

    /** 测试构造器：注入 mock runner。 */
    public ModalExecutor(CommandRunner runner) {
        this.runner = runner;
    }

    @Override
    public RemoteResult execute(RemoteExecutorConfig config, String command, String workingDir) {
        if (config == null) return RemoteResult.configError("config is null");
        if (command == null || command.isBlank()) return RemoteResult.configError("command is empty");

        List<String> argv;
        Map<String, String> env;
        try {
            argv = buildModalArgv(config, command, workingDir);
            env = buildModalEnv(config);
        } catch (IllegalArgumentException e) {
            return RemoteResult.configError(e.getMessage());
        }

        long start = System.currentTimeMillis();
        log.info("[ModalExecutor] 执行: app={}, fn={}, cmd={}",
                config.modalAppPath(), config.modalFunctionName(), command);

        CommandRunner.CommandResult r = runner.run(argv, env, null, config.timeoutSeconds());
        long elapsed = System.currentTimeMillis() - start;

        if (r.timedOut()) {
            log.warn("[ModalExecutor] 超时 ({}ms): app={}", elapsed, config.modalAppPath());
            return RemoteResult.timeout(elapsed);
        }

        if (r.exitCode() == 0) {
            String parsed = parseStdout(r.stdout());
            log.info("[ModalExecutor] 成功 ({}ms): app={}", elapsed, config.modalAppPath());
            return RemoteResult.success(parsed, elapsed);
        }

        log.warn("[ModalExecutor] 失败 exit={} ({}ms): app={}, stderr={}",
                r.exitCode(), elapsed, config.modalAppPath(), r.stderr());
        return RemoteResult.failure(r.exitCode(), r.stdout(), r.stderr(), elapsed);
    }

    @Override
    public String type() {
        return "modal";
    }

    // ════════════════════════════════════════════════════════════════
    //  命令构造（package-private 便于测试断言）
    // ════════════════════════════════════════════════════════════════

    /**
     * 构造 {@code modal run <app>::<fn> --args-json <json>} argv。
     *
     * @throws IllegalArgumentException 当 modalAppPath 或 modalFunctionName 为空
     */
    static List<String> buildModalArgv(RemoteExecutorConfig config, String command, String workingDir) {
        if (config.modalAppPath() == null || config.modalAppPath().isBlank()) {
            throw new IllegalArgumentException("modalAppPath required");
        }
        if (config.modalFunctionName() == null || config.modalFunctionName().isBlank()) {
            throw new IllegalArgumentException("modalFunctionName required");
        }
        Map<String, String> argsMap = buildArgsMap(config, command, workingDir);
        List<String> argv = new ArrayList<>();
        argv.add("modal");
        argv.add("run");
        argv.add(config.modalAppPath() + "::" + config.modalFunctionName());
        argv.add("--args-json");
        argv.add(GSON.toJson(argsMap));
        return argv;
    }

    /** 构造 argsMap：command + workingDir + env 中 MODAL_ARG_ 前缀项。 */
    private static Map<String, String> buildArgsMap(RemoteExecutorConfig config,
                                                     String command, String workingDir) {
        // LinkedHashMap 保 JSON 字段顺序稳定（便于测试断言）
        Map<String, String> argsMap = new LinkedHashMap<>();
        argsMap.put("command", command);
        argsMap.put("workingDir", workingDir == null ? "" : workingDir);
        if (config.env() != null) {
            for (Map.Entry<String, String> e : config.env().entrySet()) {
                if (e.getKey() != null && e.getKey().startsWith(MODAL_ARG_PREFIX)) {
                    String argKey = e.getKey().substring(MODAL_ARG_PREFIX.length());
                    if (!argKey.isEmpty()) {
                        argsMap.put(argKey, e.getValue() == null ? "" : e.getValue());
                    }
                }
            }
        }
        return argsMap;
    }

    /**
     * 构造 env Map — 合并 {@code config.env()}（剔除 MODAL_ARG_ 业务参数）+
     * Modal 凭据（{@code MODAL_TOKEN_ID}/{@code MODAL_TOKEN_SECRET}/{@code MODAL_WORKSPACE}）。
     * <p>
     * 凭据来自 config 字段（非空时覆盖 env 中的同名键）；config 字段为 null 时
     * 不写入 env，让 Modal CLI 从进程环境读（生产部署通常已在 shell 设好）。
     */
    static Map<String, String> buildModalEnv(RemoteExecutorConfig config) {
        Map<String, String> env = new HashMap<>();
        if (config.env() != null) {
            for (Map.Entry<String, String> e : config.env().entrySet()) {
                if (e.getKey() == null) continue;
                // 剔除业务参数前缀（这些已通过 argsJson 传递）
                if (e.getKey().startsWith(MODAL_ARG_PREFIX)) continue;
                env.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
            }
        }
        if (config.modalTokenId() != null && !config.modalTokenId().isBlank()) {
            env.put("MODAL_TOKEN_ID", config.modalTokenId());
        }
        if (config.modalTokenSecret() != null && !config.modalTokenSecret().isBlank()) {
            env.put("MODAL_TOKEN_SECRET", config.modalTokenSecret());
        }
        if (config.modalWorkspace() != null && !config.modalWorkspace().isBlank()) {
            env.put("MODAL_WORKSPACE", config.modalWorkspace());
        }
        return env;
    }

    /**
     * 解析 Modal CLI stdout — 取末行非空内容作为函数返回值。
     * <p>
     * Modal CLI 通常先打日志行，最后把函数 return 值打到末行。全空返回 {@code ""}。
     */
    static String parseStdout(String stdout) {
        if (stdout == null || stdout.isBlank()) return "";
        String[] lines = stdout.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            if (!lines[i].isBlank()) {
                return lines[i];
            }
        }
        return "";
    }
}
