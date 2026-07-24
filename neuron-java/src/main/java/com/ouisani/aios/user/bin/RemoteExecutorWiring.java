package com.ouisani.aios.user.bin;

import com.ouisani.aios.core.remote.RemoteExecutorConfig;
import com.ouisani.aios.core.remote.RemoteExecutorConfigRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 远程执行器配置启动装配 — 从环境变量读取连接参数，构造 {@link RemoteExecutorConfig} 并注册到
 * {@link RemoteExecutorConfigRegistry}。
 * <p>
 * <b>幂等</b>：{@link AtomicBoolean} 守卫，重复调用直接返回（启动序列可能多次触发 configure）。
 * <b>best-effort</b>：永不抛异常 — 单个后端装配失败只 {@code log.warn}，不影响其他后端与主启动流程。
 * 关键字段缺失（如 ssh 无 host、slurm 无 loginHost/partition）则跳过该后端，不注册。
 * <p>
 * <b>识别的 env vars</b>（关键字段缺失则跳过该后端）：
 * <ul>
 *   <li>{@code AIOS_REMOTE_SSH_HOST}（+ 可选 {@code _USER}/{@code _PORT}/{@code _KEY}）
 *       → register("ssh-default", ssh config)</li>
 *   <li>{@code AIOS_REMOTE_SLURM_LOGIN_HOST} 或 {@code AIOS_REMOTE_SLURM_PARTITION}
 *       （+ 可选 {@code _CPUS}/{@code _GPUS}/{@code _WORKDIR}）
 *       → register("slurm-default", slurm config；loginHost 非空走远程，否则本地）</li>
 *   <li>{@code AIOS_REMOTE_MODAL_APP} + {@code AIOS_REMOTE_MODAL_FN}
 *       → register("modal-default", modal config；token 由 ModalExecutor 运行时从 env 读）</li>
 *   <li>{@code AIOS_REMOTE_MODAL_REST_ENDPOINT} + {@code _TOKEN_ID} + {@code _TOKEN_SECRET}
 *       （function 名取 {@code AIOS_REMOTE_MODAL_FN}，缺省 "handle"）
 *       → register("modal-rest-default", modalRest config)</li>
 * </ul>
 * <p>
 * <b>为何只注册 config 不注册 executor 实例</b>：{@code RemoteExecutorRegistry} 默认构造器已注册 4 个
 * 无状态 executor；executor 每次 execute 从 config 取连接参数，无连接状态，无需启动绑定。
 * <p>
 * 镜像 {@code AiosAppManager.wirePrimaryMemoryStore} 的幂等 + best-effort 模式。
 *
 * @see RemoteExecutorConfigRegistry
 * @see AiosAppManager#wireRemoteExecutors
 */
public final class RemoteExecutorWiring {

    private static final Logger log = LoggerFactory.getLogger(RemoteExecutorWiring.class);
    private static final AtomicBoolean wired = new AtomicBoolean(false);

    private RemoteExecutorWiring() {}

    /**
     * 从环境变量读取远程执行配置并注册到 {@link RemoteExecutorConfigRegistry}。
     * 幂等：重复调用直接返回。永不抛异常。
     */
    public static void wire() {
        if (!wired.compareAndSet(false, true)) {
            log.debug("[RemoteExecutorWiring] 已装配，跳过");
            return;
        }
        wireSsh();
        wireSlurm();
        wireModal();
        wireModalRest();
    }

    /** 测试用 — 重置 wired 标志 + 清空 registry。 */
    static void reset() {
        wired.set(false);
        RemoteExecutorConfigRegistry.getInstance().clear();
    }

    // ════════════════════════════════════════════════════════════════
    //  各后端装配
    // ════════════════════════════════════════════════════════════════

    private static void wireSsh() {
        String host = env("AIOS_REMOTE_SSH_HOST");
        if (isBlank(host)) return;
        try {
            String user = env("AIOS_REMOTE_SSH_USER");
            int port = parseIntOr(env("AIOS_REMOTE_SSH_PORT"), 22);
            String key = env("AIOS_REMOTE_SSH_KEY");
            RemoteExecutorConfig cfg = RemoteExecutorConfig.ssh(host, port, user, key);
            RemoteExecutorConfigRegistry.getInstance().register("ssh-default", cfg);
            log.info("[RemoteExecutorWiring] 已注册 ssh-default: host={}, port={}, user={}",
                    host, port, user);
        } catch (Exception e) {
            log.warn("[RemoteExecutorWiring] ssh 装配失败: {}", e.getMessage());
        }
    }

    private static void wireSlurm() {
        String loginHost = env("AIOS_REMOTE_SLURM_LOGIN_HOST");
        String partition = env("AIOS_REMOTE_SLURM_PARTITION");
        if (isBlank(loginHost) && isBlank(partition)) return;
        try {
            int cpus = parseIntOr(env("AIOS_REMOTE_SLURM_CPUS"), 0);
            int gpus = parseIntOr(env("AIOS_REMOTE_SLURM_GPUS"), 0);
            String workDir = env("AIOS_REMOTE_SLURM_WORKDIR");
            RemoteExecutorConfig cfg = isBlank(loginHost)
                    ? RemoteExecutorConfig.slurm(partition, cpus, gpus)
                    : RemoteExecutorConfig.slurm(loginHost, partition, cpus, gpus, workDir);
            RemoteExecutorConfigRegistry.getInstance().register("slurm-default", cfg);
            log.info("[RemoteExecutorWiring] 已注册 slurm-default: loginHost={}, partition={}, cpus={}, gpus={}",
                    loginHost, partition, cpus, gpus);
        } catch (Exception e) {
            log.warn("[RemoteExecutorWiring] slurm 装配失败: {}", e.getMessage());
        }
    }

    private static void wireModal() {
        String app = env("AIOS_REMOTE_MODAL_APP");
        String fn = env("AIOS_REMOTE_MODAL_FN");
        if (isBlank(app) || isBlank(fn)) return;
        try {
            RemoteExecutorConfig cfg = RemoteExecutorConfig.modal(app, fn);
            RemoteExecutorConfigRegistry.getInstance().register("modal-default", cfg);
            log.info("[RemoteExecutorWiring] 已注册 modal-default: app={}, fn={}", app, fn);
        } catch (Exception e) {
            log.warn("[RemoteExecutorWiring] modal 装配失败: {}", e.getMessage());
        }
    }

    private static void wireModalRest() {
        String endpoint = env("AIOS_REMOTE_MODAL_REST_ENDPOINT");
        String tokenId = env("AIOS_REMOTE_MODAL_REST_TOKEN_ID");
        String tokenSecret = env("AIOS_REMOTE_MODAL_REST_TOKEN_SECRET");
        if (isBlank(endpoint) || isBlank(tokenId) || isBlank(tokenSecret)) return;
        try {
            // function 名复用 AIOS_REMOTE_MODAL_FN，缺省 "handle"（Modal 常见入口函数名）
            String fn = env("AIOS_REMOTE_MODAL_FN");
            if (isBlank(fn)) fn = "handle";
            RemoteExecutorConfig cfg = RemoteExecutorConfig.modalRest(endpoint, fn, tokenId, tokenSecret);
            RemoteExecutorConfigRegistry.getInstance().register("modal-rest-default", cfg);
            log.info("[RemoteExecutorWiring] 已注册 modal-rest-default: endpoint={}, fn={}", endpoint, fn);
        } catch (Exception e) {
            log.warn("[RemoteExecutorWiring] modal-rest 装配失败: {}", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  helpers
    // ════════════════════════════════════════════════════════════════

    private static String env(String name) {
        return System.getenv(name);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static int parseIntOr(String s, int def) {
        if (isBlank(s)) return def;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
