package com.ouisani.aios.user.bin;

import com.ouisani.aios.core.AgentTask;
import com.ouisani.aios.core.ProcessPriority;
import com.ouisani.aios.core.TaskScheduler;
import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.user.container.AgentfileParser;
import com.ouisani.aios.user.container.AppManifest;
import com.ouisani.aios.user.sdk.AbstractAgent;
import com.ouisani.aios.vfs.HostSourceNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * AIOS 应用安装与调度引擎。
 * <p>
 * OS 类比：相当于 systemd 的 {@code systemctl start} + Docker 的 {@code docker run} —
 * 解析应用清单、挂载 VFS 目录、通过 Cgroup 分配 token 预算，
 * 然后生成 {@code spawnCount} 个虚拟线程，每个运行一个 {@link GenericAppAgent}，
 * 可在 Docker 沙箱中执行代码。
 * <p>
 * 使用方式：
 * <pre>
 * AiosAppManager.configure(scheduler);
 * AiosAppManager.installAndRun(manifestText);
 * </pre>
 */
public class AiosAppManager {

    private static final Logger log = LoggerFactory.getLogger(AiosAppManager.class);

    private static TaskScheduler scheduler;

    public static void configure(TaskScheduler taskScheduler) {
        scheduler = taskScheduler;
    }

    /**
     * 安装并运行通用 OS 应用（从清单文本解析）。
     *
     * @param appManifestContent 原始清单文本（APP_NAME, SPAWN, BUDGET, MOUNT, ENTRYPOINT）
     */
    public static void installAndRun(String appManifestContent) {
        if (scheduler == null) {
            throw new IllegalStateException("[App Manager] TaskScheduler not configured. Call configure() first.");
        }

        // 1. Parse manifest
        AppManifest manifest = AgentfileParser.parseManifest(appManifestContent);
        String appName = manifest.appName();
        int spawnCount = manifest.spawnCount();
        int tokenBudget = manifest.tokenBudget();
        String entrypoint = manifest.entrypoint();

        System.out.println("[App Manager] Installing generic application: " + appName);

        // 2. 构建集装箱目录结构 — 所有物理文件收敛到单一容器下
        //    workspaces/{appName}/root      — 应用根目录（对应 VFS /）
        //    workspaces/{appName}/factory   — 工厂目录（对应 VFS /factory）
        //    workspaces/{appName}/shared    — 共享目录（对应 VFS /shared）
        //    workspaces/{appName}/outputs   — 出货区（对应 VFS /shared/outputs）
        String safeAppName = appName.replaceAll("[\\\\/:*?\"<>|]", "_");
        java.nio.file.Path containerDir = java.nio.file.Paths.get(
                "/home/xmy/tryaios/aios-java/workspaces", safeAppName);
        java.nio.file.Path physicalRoot = containerDir.resolve("root");
        java.nio.file.Path physicalFactory = containerDir.resolve("factory");
        java.nio.file.Path physicalShared = containerDir.resolve("shared");
        java.nio.file.Path physicalOutputs = containerDir.resolve("outputs");

        try {
            java.nio.file.Files.createDirectories(containerDir);
            java.nio.file.Files.createDirectories(physicalRoot);
            java.nio.file.Files.createDirectories(physicalFactory);
            java.nio.file.Files.createDirectories(physicalShared);
            java.nio.file.Files.createDirectories(physicalOutputs);
            System.out.println("[App Manager] Container directory created: " + containerDir);
        } catch (java.io.IOException e) {
            throw new RuntimeException("[App Manager] Failed to create container directories: " + e.getMessage(), e);
        }

        // 3. Mount VFS directories for this application
        for (Map.Entry<String, String> mount : manifest.mounts().entrySet()) {
            String hostPath = mount.getKey();
            String containerPath = mount.getValue();
            // 将 VFS 路径挂载到集装箱内的 root 子目录
            String relativeDir = containerPath.replace("/", "_");
            java.nio.file.Path physicalSubDir = physicalRoot.resolve(relativeDir);
            try {
                java.nio.file.Files.createDirectories(physicalSubDir);
            } catch (java.io.IOException e) {
                System.err.println("[App Manager] Failed to create mount sub-dir: " + physicalSubDir);
                continue;
            }
            HostSourceNode hostNode = new HostSourceNode(hostPath, physicalSubDir.toString());
            VfsManager.instance().mount(hostPath, containerPath, hostNode);
            System.out.printf("  ├─ [App Manager] Mounted: %s → %s (Physical: %s)%n", hostPath, containerPath, physicalSubDir);
        }

        // 注册 /factory 物理工作目录映射，使后续 writeText 自动写入物理磁盘
        VfsManager.instance().registerPhysicalWorkspace("/factory", physicalFactory.toString());

        // ── 技能舱 (Skills Registry) VFS 挂载 ──
        // 将全局技能库目录注册为 /shared/skills，使 VFS 层面可访问 MANIFEST.md 等资源
        String globalSkillsDir = "/home/xmy/tryaios/aios-java/aios_skills";
        java.io.File skillsDir = new java.io.File(globalSkillsDir);
        if (!skillsDir.exists()) {
            skillsDir.mkdirs();
        }
        HostSourceNode skillsHostNode = new HostSourceNode("/shared/skills", globalSkillsDir);
        VfsManager.instance().mount("/shared/skills", "/shared/skills", skillsHostNode);
        System.out.printf("  ├─ [App Manager] Skills Registry mounted: /shared/skills → %s%n", globalSkillsDir);

        // 4. Spawn virtual threads
        System.out.printf("[App Manager] Allocated %d virtual threads with Cgroup budget %d%n", spawnCount, tokenBudget);

        for (int i = 0; i < spawnCount; i++) {
            String workerId = appName + "_worker_" + (i + 1);
            GenericAppAgent agent = new GenericAppAgent(workerId, tokenBudget, entrypoint, appName,
                    manifest.enabledSkills(), manifest.enabledRoles(),
                    physicalFactory.toString(), physicalOutputs.toString());
            agent.spawn(scheduler);
        }

        System.out.println("[App Manager] Application successfully launched into User Space.");
        log.info("[App Manager] Application '{}' launched: spawnCount={}, budget={}, entrypoint='{}', container={}",
                appName, spawnCount, tokenBudget, entrypoint, containerDir);
    }

    /**
     * 通用应用 Agent — 根据清单动态创建的工作线程。
     * <p>
     * 所有入口点统一路由到沙箱真实执行，绝不将 Shell 命令发给 LLM。
     */
    static class GenericAppAgent extends AbstractAgent {

        private final String entrypoint;
        private final String appName;
        private final List<String> enabledSkills;
        private final List<String> enabledRoles;
        private final String physicalFactoryDir;  // 集装箱内 factory 子目录
        private final String physicalOutputsDir;   // 集装箱内 outputs 子目录

        GenericAppAgent(String agentId, int tokenBudget, String entrypoint, String appName,
                        List<String> enabledSkills, List<String> enabledRoles,
                        String physicalFactoryDir, String physicalOutputsDir) {
            super(agentId, ProcessPriority.NORMAL, tokenBudget);
            this.entrypoint = entrypoint;
            this.appName = appName;
            this.enabledSkills = enabledSkills != null ? enabledSkills : List.of();
            this.enabledRoles = enabledRoles != null ? enabledRoles : List.of();
            this.physicalFactoryDir = physicalFactoryDir;
            this.physicalOutputsDir = physicalOutputsDir;
        }

        @Override
        protected void onStart() {
            System.out.printf("  ▶ [%s] Booting... entrypoint='%s'%n", agentId, entrypoint);
            System.out.println("[AppManager] Strict runtime assertion enabled. Ready to trigger AutoMedic on failure.");

            if (entrypoint == null || entrypoint.isBlank()) {
                System.out.printf("  ■ [%s] No entrypoint defined, idle exit.%n", agentId);
                exit();
                return;
            }

            // 所有入口点统一路由到沙箱执行，绝不发给 LLM
            // 特权穿透：直接实例化 BashTool，绕过 Syscall 安全检查
            // /factory 已通过 registerPhysicalWorkspace 映射到物理磁盘，文件可直接访问
            try {
                System.out.println("[GenericAppAgent] Executing entrypoint (Privileged Mode): " + entrypoint);

                // 当前应用的集装箱物理路径（从实例字段获取，防状态泄漏）
                final String effectiveFactoryDir = physicalFactoryDir;
                final String effectiveOutputsDir = physicalOutputsDir;

                // ── VFS 全量签出 (Checkout)：将 /factory 下所有文件刷到物理磁盘 ──
                // 这一步确保母体通过 writeText 写入 VFS 的所有文件都真实存在于物理机上
                flushVfsToPhysicalWorkspace(effectiveFactoryDir);

                // ── 全局技能舱 (Global Skills Registry) VFS 挂载 ──
                // 将宿主机公共技能库映射到沙箱 /shared/skills，使大模型 file_read 可访问 MANIFEST.md
                // 注意：/shared/skills 是全局共享资源，多个 app 共用同一挂载点，幂等检查防重复挂载
                String physicalSkillsPath = "/home/xmy/tryaios/aios-java/aios_skills";
                try {
                    java.nio.file.Path skillsPath = java.nio.file.Path.of(physicalSkillsPath);
                    if (!java.nio.file.Files.exists(skillsPath)) {
                        java.nio.file.Files.createDirectories(skillsPath);
                        System.out.println("[GenericAppAgent] Global Skills Registry directory created: " + physicalSkillsPath);
                    }
                    if (!VfsManager.instance().exists("/shared/skills")) {
                        VfsManager.instance().mountHostFile("/shared/skills", physicalSkillsPath);
                        log.info("[AppManager] Global Skills Registry mounted to /shared/skills (Physical: {})", physicalSkillsPath);
                    } else {
                        log.debug("[AppManager] /shared/skills already mounted, skipping (shared global resource)");
                    }
                } catch (Exception e) {
                    System.err.println("[GenericAppAgent] Skills Registry VFS mount failed (non-fatal): " + e.getMessage());
                }

                // ── 全局角色舱 (Global Roles Registry) VFS 挂载 ──
                // 将宿主机角色卡目录映射到沙箱 /shared/roles，使大模型 file_read 可读取 .yaml 角色卡
                // 注意：/shared/roles 是全局共享资源，多个 app 共用同一挂载点，幂等检查防重复挂载
                String physicalRolesPath = "/home/xmy/tryaios/aios-java/aios_roles";
                try {
                    java.nio.file.Path rolesPath = java.nio.file.Path.of(physicalRolesPath);
                    if (!java.nio.file.Files.exists(rolesPath)) {
                        java.nio.file.Files.createDirectories(rolesPath);
                        System.out.println("[GenericAppAgent] Global Roles Registry directory created: " + physicalRolesPath);
                    }
                    if (!VfsManager.instance().exists("/shared/roles")) {
                        VfsManager.instance().mountHostFile("/shared/roles", physicalRolesPath);
                        log.info("[AppManager] Global Roles Registry mounted to /shared/roles (Physical: {})", physicalRolesPath);
                    } else {
                        log.debug("[AppManager] /shared/roles already mounted, skipping (shared global resource)");
                    }
                } catch (Exception e) {
                    System.err.println("[GenericAppAgent] Roles Registry VFS mount failed (non-fatal): " + e.getMessage());
                }

                // ── 应用出货区 (App Outputs) VFS 挂载 ──
                // 为当前应用创建专属的输出目录，供 agent 脚本写入最终产物（PDF、报告等）
                try {
                    java.nio.file.Path outputsPath = java.nio.file.Path.of(effectiveOutputsDir);
                    if (!java.nio.file.Files.exists(outputsPath)) {
                        java.nio.file.Files.createDirectories(outputsPath);
                        System.out.println("[GenericAppAgent] App Outputs directory created: " + effectiveOutputsDir);
                    }
                    VfsManager.instance().mountHostFile("/shared/outputs", effectiveOutputsDir);
                    log.info("[AppManager] App Outputs directory mounted to /shared/outputs (Physical: {})", effectiveOutputsDir);
                } catch (Exception e) {
                    System.err.println("[GenericAppAgent] App Outputs VFS mount failed (non-fatal): " + e.getMessage());
                }

                // ── 动态角色卡裁剪 (Role Manifest Tailoring) ──
                // 根据 enabledRoles 列表，从物理角色目录读取 .yaml 文件
                // 直接写入当前应用专属物理目录，再同步到 VFS，防状态泄漏
                try {
                    String activeRolesContent = tailorRolesManifest(enabledRoles);
                    // 直接写物理磁盘（使用集装箱 factory 子目录，不依赖全局 VFS 映射）
                    java.nio.file.Path rolesFilePath = java.nio.file.Path.of(effectiveFactoryDir, "ACTIVE_ROLES.md");
                    java.nio.file.Files.writeString(rolesFilePath, activeRolesContent);
                    // 同步到 VFS（供大模型 file_read 访问）
                    VfsManager.instance().writeText("/factory/ACTIVE_ROLES.md", activeRolesContent);
                    log.info("[AppManager] ACTIVE_ROLES.md written (physical: {}, enabledRoles={})", rolesFilePath, enabledRoles);
                } catch (Exception e) {
                    System.err.println("[GenericAppAgent] ACTIVE_ROLES.md generation failed (non-fatal): " + e.getMessage());
                }

                // ── 动态技能说明书裁剪 (Skill Manifest Tailoring) ──
                // 根据 enabledSkills 列表，从全局 MANIFEST.md 中裁剪出对应段落
                // 直接写入当前应用专属物理目录，再同步到 VFS，防状态泄漏
                try {
                    String activeSkillsContent = tailorSkillsManifest(enabledSkills);
                    // 直接写物理磁盘（使用集装箱 factory 子目录，不依赖全局 VFS 映射）
                    java.nio.file.Path skillsFilePath = java.nio.file.Path.of(effectiveFactoryDir, "ACTIVE_SKILLS.md");
                    java.nio.file.Files.writeString(skillsFilePath, activeSkillsContent);
                    // 同步到 VFS（供大模型 file_read 访问）
                    VfsManager.instance().writeText("/factory/ACTIVE_SKILLS.md", activeSkillsContent);
                    log.info("[AppManager] ACTIVE_SKILLS.md written (physical: {}, enabledSkills={})", skillsFilePath, enabledSkills);
                } catch (Exception e) {
                    System.err.println("[GenericAppAgent] ACTIVE_SKILLS.md generation failed (non-fatal): " + e.getMessage());
                }

                // ── 技能舱 (Skills Registry) 物理软链接挂载 ──
                // 将公共技能库软链接到应用沙箱的 skills 目录，使 Python 脚本可直接 import
                mountSkillsRegistry(effectiveFactoryDir);

                String physicalCommand = entrypoint;

                // 检测 entrypoint 是否引用了 VFS 路径（如 sh /factory/run_all.sh）
                // 如果是，尝试从物理工作目录直接执行（因为 writeText 已桥接到物理磁盘）
                String vfsScriptPath = extractVfsScriptPath(entrypoint);
                if (vfsScriptPath != null) {
                    // /factory 下的文件已经通过 HostSourceNode 写入物理磁盘
                    // 检查物理文件是否存在
                    String relativePath = vfsScriptPath.substring("/factory".length());
                    java.io.File physicalFile = new java.io.File(effectiveFactoryDir + relativePath);

                    if (physicalFile.exists()) {
                        // 物理文件存在，读取内容并进行内核级路径翻译
                        try {
                            String scriptContent = java.nio.file.Files.readString(physicalFile.toPath());
                            scriptContent = applyKernelPathTranslation(scriptContent);
                            java.nio.file.Files.writeString(physicalFile.toPath(), scriptContent);
                        } catch (Exception e) {
                            System.err.println("[GenericAppAgent] Path translation failed: " + e.getMessage());
                        }

                        // 使用相对路径，避免绝对路径问题
                        String fileName = physicalFile.getName();
                        if (fileName.endsWith(".py")) {
                            physicalCommand = "python3 " + fileName;
                        } else {
                            physicalCommand = "sh " + fileName;
                        }
                        System.out.println("[GenericAppAgent] VFS file exists on physical disk: " + physicalFile.getAbsolutePath());
                        System.out.println("[GenericAppAgent] Executing in workdir: " + effectiveFactoryDir + ", command: " + physicalCommand);
                    } else {
                        // 物理文件不存在，尝试从 VFS 读取并落盘
                        try {
                            var nodeOpt = VfsManager.instance().resolve(vfsScriptPath);
                            String scriptContent = nodeOpt.map(n -> n.read()).orElse(null);

                            if (scriptContent != null && !scriptContent.isBlank()) {
                                // 内核级路径翻译：VFS→物理路径 + python→python3
                                scriptContent = applyKernelPathTranslation(scriptContent);

                                String suffix = vfsScriptPath.endsWith(".py") ? ".py" : ".sh";
                                java.io.File tempScript = java.io.File.createTempFile("aios_run_", suffix);
                                java.nio.file.Files.writeString(tempScript.toPath(), scriptContent);
                                tempScript.setExecutable(true);

                                String runner = suffix.equals(".py") ? "python3 " : "sh ";
                                physicalCommand = runner + tempScript.getAbsolutePath();

                                System.out.println("[AppManager] VFS script bridged to physical temp file for execution.");
                                System.out.println("[GenericAppAgent] VFS:" + vfsScriptPath + " → Physical:" + tempScript.getAbsolutePath());
                            } else {
                                System.err.println("[GenericAppAgent] VFS script not found or empty: " + vfsScriptPath + ", executing raw command");
                            }
                        } catch (Exception e) {
                            System.err.println("[GenericAppAgent] VFS bridge failed, executing raw command: " + e.getMessage());
                        }
                    }
                }

                com.ouisani.aios.core.tool.BashTool bashTool = new com.ouisani.aios.core.tool.BashTool();
                com.ouisani.aios.core.tool.BashTool.Input input = new com.ouisani.aios.core.tool.BashTool.Input(physicalCommand);
                // 构造特权运行上下文：工作目录为物理机真实目录
                com.ouisani.aios.core.tool.ToolContext privilegedContext =
                        new com.ouisani.aios.core.tool.ToolContext(agentId, sdk, effectiveFactoryDir);
                com.ouisani.aios.core.tool.ToolOutput output = bashTool.call(input, privilegedContext);

                String resultText = output.toText();
                System.out.println("[GenericAppAgent] Execution Result: " +
                        (resultText != null && resultText.length() > 300 ? resultText.substring(0, 300) + "..." : resultText));

                // 致命错误拦截：检测 Bash 输出中的关键错误信号，触发 SemanticCrashAnalyzer
                if (!output.success() || containsFatalError(resultText)) {
                    System.err.println("🚨 [GenericAppAgent] FATAL: Script execution failed. Triggering Kernel Panic...");
                    throw new RuntimeException("App Crash detected in User Space: " +
                            (resultText != null && resultText.length() > 200 ? resultText.substring(0, 200) : resultText));
                }

                // 执行成功 — 将 stdout 桥接到 EventBus，网关自动推给前端 WebSocket
                try {
                    String escapedPayload = resultText != null
                            ? resultText.replace("\\", "\\\\").replace("\"", "\\\"")
                                    .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")
                            : "";
                    String logJson = String.format(
                            "{\"type\":\"APP_OUTPUT\", \"agentId\":\"%s\", \"timestamp\":%d, \"payload\":\"%s\"}",
                            appName, System.currentTimeMillis(), escapedPayload);
                    com.ouisani.aios.core.network.EventBus.instance().broadcast("sys.eventbus.logs", logJson);
                    System.out.println("[AppManager] App stdout published to EventBus.");
                } catch (Exception e) {
                    System.err.println("[GenericAppAgent] EventBus publish failed: " + e.getMessage());
                }
            } catch (RuntimeException e) {
                // 致命异常：必须击穿 onStart，让 VirtualThread 异常终止，
                // 从而触发 TaskScheduler → SemanticCrashAnalyzer → AutoMedic 自愈流水线
                System.err.println("🚨 [GenericAppAgent] FATAL ERROR. Thread must die to trigger OS AutoMedic!");
                throw e; // 绝不吞掉！直接向外抛出
            } catch (Exception e) {
                // 非致命异常：记录日志，但不阻止线程死亡
                System.err.println("[GenericAppAgent] Execution Failed: " + e.getMessage());
                throw new RuntimeException("App Crash in User Space: " + e.getMessage(), e);
            }

            System.out.println("[AppManager] GenericAppAgent execution routed to Sandbox instead of LLM.");
            System.out.printf("  ■ [%s] Task completed. Exiting.%n", agentId);
            exit();
        }

        /**
         * VFS 全量签出 — 将 /factory 下所有 VFS 文件刷到物理磁盘。
         * <p>
         * 母体通过 writeText 写入 VFS 的文件可能存在于 MutableFileNode（内存）
         * 或 HostSourceNode（物理磁盘）中。此方法确保所有文件都真实存在于
         * 物理机上，以便 BashTool 的 ProcessBuilder 可以读取和执行。
         * <p>
         * 类比 Git 的 checkout：将对象数据库中的文件写入工作目录。
         *
         * @param physicalWorkDir 物理工作目录路径
         */
        private void flushVfsToPhysicalWorkspace(String physicalWorkDir) {
            java.util.List<String> vfsFiles = VfsManager.instance().listFilesUnder("/factory");
            if (vfsFiles.isEmpty()) {
                System.out.println("[GenericAppAgent] No VFS files found under /factory, skipping flush.");
                return;
            }

            int flushed = 0;
            for (String vfsPath : vfsFiles) {
                try {
                    // 读取 VFS 文件内容
                    var nodeOpt = VfsManager.instance().resolve(vfsPath);
                    if (nodeOpt.isEmpty()) continue;
                    String content = nodeOpt.get().read();
                    if (content == null || content.isBlank()) continue;

                    // 应用内核级路径翻译（python→python3, /factory→物理路径）
                    content = applyKernelPathTranslation(content);

                    // 计算物理文件路径：physicalWorkDir + VFS 相对路径
                    String relativePath = vfsPath.substring("/factory".length());
                    java.io.File physicalFile = new java.io.File(physicalWorkDir + relativePath);

                    // 确保父目录存在
                    if (physicalFile.getParentFile() != null) {
                        physicalFile.getParentFile().mkdirs();
                    }

                    // 写入物理磁盘
                    java.nio.file.Files.writeString(physicalFile.toPath(), content);
                    if (physicalFile.getName().endsWith(".sh") || physicalFile.getName().endsWith(".py")) {
                        physicalFile.setExecutable(true);
                    }
                    flushed++;
                } catch (Exception e) {
                    System.err.println("[GenericAppAgent] VFS flush failed for " + vfsPath + ": " + e.getMessage());
                }
            }

            System.out.println("[AppManager] VFS matrix fully flushed to physical workspace: " + physicalWorkDir
                    + " (" + flushed + " files)");
        }

        /**
         * 动态角色卡裁剪 — 根据 enabledRoles 列表从物理角色目录读取 .yaml 文件并拼接。
         * <p>
         * 如果 enabledRoles 为空，生成"未挂载角色"提示；
         * 如果不为空，依次读取 /home/xmy/tryaios/aios-java/aios_roles/{RoleName}.yaml 并拼接。
         *
         * @param enabledRoles 按需装载的角色名列表（如 ["System_Architect", "Python_Coder"]）
         * @return 拼接后的角色规范文本
         */
        private String tailorRolesManifest(List<String> enabledRoles) {
            // 兜底逻辑：如果 enabledRoles 为空，强制注入 System_Architect 角色卡
            // 绝不允许给大模型喂空的角色说明书！
            if (enabledRoles == null || enabledRoles.isEmpty()) {
                String defaultRolePath = "/home/xmy/tryaios/aios-java/aios_roles/System_Architect.yaml";
                try {
                    String architectContent = java.nio.file.Files.readString(java.nio.file.Path.of(defaultRolePath));
                    log.info("[AppManager] enabledRoles is empty, force-injecting System_Architect as default role");
                    return "# 当前任务角色配置（默认兜底）\n\n" +
                            "本次任务未指定按需装载角色，强制注入系统架构师角色：\n\n" +
                            "---\n# Role: System_Architect (Default)\n" +
                            architectContent.trim() + "\n---\n";
                } catch (Exception e) {
                    System.err.println("[AppManager] Failed to read default System_Architect.yaml: " + e.getMessage());
                    return "# 当前任务角色配置\n\n[WARN] 默认架构师角色卡读取失败，请使用默认 AGI 逻辑。\n";
                }
            }

            String rolesDir = "/home/xmy/tryaios/aios-java/aios_roles";
            StringBuilder content = new StringBuilder();
            content.append("# 当前任务角色配置（按需装载）\n\n");
            content.append("以下工程角色已为本任务激活：\n\n");

            int loadedCount = 0;
            for (String roleName : enabledRoles) {
                String yamlPath = rolesDir + "/" + roleName + ".yaml";
                try {
                    String yamlContent = java.nio.file.Files.readString(java.nio.file.Path.of(yamlPath));
                    content.append("---\n");
                    content.append("# Role: ").append(roleName).append("\n");
                    content.append(yamlContent.trim()).append("\n");
                    content.append("---\n\n");
                    loadedCount++;
                    System.out.println("[AppManager] Loaded role: " + roleName);
                } catch (java.nio.file.NoSuchFileException e) {
                    System.err.println("[AppManager] Role file not found: " + yamlPath + " (skipped)");
                    content.append("[WARN] 角色卡未找到: ").append(roleName).append(".yaml\n\n");
                } catch (Exception e) {
                    System.err.println("[AppManager] Failed to read role: " + roleName + " - " + e.getMessage());
                    content.append("[WARN] 角色卡读取失败: ").append(roleName).append(" (").append(e.getMessage()).append(")\n\n");
                }
            }

            content.append("---\n已激活 ").append(loadedCount).append(" 个工程角色（共请求 ").append(enabledRoles.size()).append(" 个）。\n");
            log.info("[AppManager] Injected {} Roles into ACTIVE_ROLES.md", loadedCount);

            return content.toString();
        }

        /**
         * 动态技能说明书裁剪 — 根据 enabledSkills 列表从全局 MANIFEST.md 中提取对应段落。
         * <p>
         * 如果 enabledSkills 为空，生成"未挂载技能"提示；
         * 如果不为空，按模块名（如 skills.web_scraper）从 MANIFEST.md 中提取 ## skills.xxx 块。
         *
         * @param enabledSkills 按需装载的技能模块列表
         * @return 裁剪后的技能说明书文本
         */
        private String tailorSkillsManifest(List<String> enabledSkills) {
            // 读取全局 MANIFEST.md
            String manifestPath = "/home/xmy/tryaios/aios-java/aios_skills/MANIFEST.md";
            String fullManifest;
            try {
                fullManifest = java.nio.file.Files.readString(java.nio.file.Path.of(manifestPath));
            } catch (Exception e) {
                return "# 当前任务技能配置\n\n[WARN] 全局技能注册表读取失败: " + e.getMessage() +
                        "\n请尝试 file_read /shared/skills/MANIFEST.md\n";
            }

            // 如果 enabledSkills 为空，默认开放所有本地技能（写入全量 MANIFEST.md）
            if (enabledSkills == null || enabledSkills.isEmpty()) {
                log.info("[AppManager] enabledSkills is empty, writing full MANIFEST.md as ACTIVE_SKILLS.md (default open all)");
                return "# 当前任务技能配置（全量开放）\n\n" +
                        "本次任务未指定按需装载列表，默认开放所有本地技能：\n\n" +
                        fullManifest;
            }

            StringBuilder tailored = new StringBuilder();
            tailored.append("# 当前任务技能配置（按需装载）\n\n");
            tailored.append("以下技能模块已为本任务激活：\n\n");

            // 按 ## skills.xxx 标题分割，提取匹配的段落
            // MANIFEST.md 格式：## skills.web_scraper  → 直到下一个 ## 或文件结尾
            String[] sections = fullManifest.split("(?=^## )", -1);

            int matchedCount = 0;
            for (String section : sections) {
                String trimmed = section.trim();
                if (trimmed.isEmpty()) continue;

                // 提取标题行中的模块名，如 "## skills.web_scraper"
                String moduleName = null;
                for (String skill : enabledSkills) {
                    // 匹配 "## skills.web_scraper" 或 "## skills.web_scraper "
                    if (trimmed.startsWith("## " + skill)) {
                        moduleName = skill;
                        break;
                    }
                }

                if (moduleName != null) {
                    tailored.append(trimmed).append("\n\n");
                    matchedCount++;
                }
            }

            // 快速索引也保留（如果存在）
            // 快速索引区以 "## 快速索引" 开头
            for (String section : sections) {
                String trimmed = section.trim();
                if (trimmed.startsWith("## 快速索引")) {
                    // 从快速索引中只保留已启用模块对应的行
                    tailored.insert(tailored.indexOf("以下技能模块") + "以下技能模块已为本任务激活：\n\n".length(),
                            buildQuickIndexForEnabled(fullManifest, enabledSkills));
                    break;
                }
            }

            if (matchedCount == 0) {
                tailored.append("[WARN] 未在 MANIFEST.md 中找到匹配的模块: ").append(enabledSkills).append("\n");
                tailored.append("请尝试 file_read /shared/skills/MANIFEST.md 查看全部可用技能。\n");
            } else {
                tailored.append("---\n已激活 ").append(matchedCount).append(" 个技能模块（共请求 ").append(enabledSkills.size()).append(" 个）。\n");
            }

            return tailored.toString();
        }

        /**
         * 从快速索引区提取已启用模块对应的索引行。
         */
        private String buildQuickIndexForEnabled(String fullManifest, List<String> enabledSkills) {
            StringBuilder index = new StringBuilder();
            String[] lines = fullManifest.split("\n");
            for (String line : lines) {
                if (!line.startsWith("- `from skills.")) continue;
                for (String skill : enabledSkills) {
                    if (line.contains(skill)) {
                        index.append(line).append("\n");
                        break;
                    }
                }
            }
            return index.toString();
        }

        /**
         * 技能舱 (Skills Registry) 全局挂载 — 将公共技能库目录软链接到应用沙箱的 skills 子目录，
         * 使 Python 脚本可通过 `from skills.xxx import yyy` 直接复用底层公共代码。
         */
        private void mountSkillsRegistry(String physicalWorkDir) {
            try {
                // 全局公共技能目录（宿主机绝对路径）
                java.nio.file.Path globalSkillsDir = java.nio.file.Path.of(
                        "/home/xmy/tryaios/aios-java/aios_skills");

                // 自动创建全局技能目录（如不存在）
                if (!java.nio.file.Files.exists(globalSkillsDir)) {
                    java.nio.file.Files.createDirectories(globalSkillsDir);
                    System.out.println("[AppManager] Global Skills Registry directory created: " + globalSkillsDir);
                }

                // 应用沙箱内的 skills 挂载点
                java.nio.file.Path mountPoint = java.nio.file.Path.of(physicalWorkDir, "skills");

                // 如果挂载点已存在（可能是之前的软链接或目录），先删除
                if (java.nio.file.Files.exists(mountPoint)) {
                    java.nio.file.Files.delete(mountPoint);
                }

                // 创建软链接：workspace/skills → aios_skills/
                java.nio.file.Files.createSymbolicLink(mountPoint, globalSkillsDir);

                System.out.println("[AppManager] Global Skills Registry mounted to /shared/skills (Physical: "
                        + globalSkillsDir + ")");
            } catch (Exception e) {
                // 技能舱挂载失败不应阻断主流程，仅降级警告
                System.err.println("[AppManager] Skills Registry mount failed (non-fatal): " + e.getMessage());
                System.err.println("[AppManager] Agents will run without shared skills library.");
            }
        }

        @Override
        protected void onMessage(String msg) {
            log.debug("[{}] Message ignored: {}", agentId, msg.substring(0, Math.min(msg.length(), 60)));
        }

        /**
         * 内核级路径翻译 — 强制将大模型生成脚本中的 VFS 路径和 python 命令
         * 翻译为物理宿主机可执行的形式。
         * <p>
         * 由于大模型无法稳定遵循路径和环境约束，必须在执行前进行
         * 字符串级的强制重定向，确保脚本在物理机上正确运行。
         *
         * @param scriptContent 原始脚本内容
         * @return 翻译后的脚本内容
         */
        private String applyKernelPathTranslation(String scriptContent) {
            if (scriptContent == null) return null;

            // 1. 获取当前应用的物理挂载绝对路径
            String physicalWorkspace = VfsManager.instance().findPhysicalWorkspace("/factory");
            if (physicalWorkspace == null) {
                physicalWorkspace = System.getProperty("user.dir") + "/aios_workspace_default";
            }

            // 2. 将 VFS 虚拟路径强制翻译为宿主机物理路径
            //    避免路径重复叠加：如果 LLM 已经输出了真实的物理路径，就不再替换
            if (physicalWorkspace != null && !scriptContent.contains(physicalWorkspace)) {
                // 使用正则，仅替换作为独立路径起点的 /factory
                scriptContent = scriptContent.replaceAll("(?<![\\w\\-\\.])\\/factory", physicalWorkspace);
            }

            // 3. 强制兼容现代 OS 的 Python3 环境（拦截大模型的 python 指令）
            //    注意：先替换 python3 避免被二次替换，所以用占位符保护
            //    同时强制注入 -u 参数，禁用 stdout 缓冲，确保 Java 能实时捕获输出
            scriptContent = scriptContent.replace("python3 -u ", "\u0000PY3U\u0000 ");
            scriptContent = scriptContent.replace("python3 ", "python3 -u ");
            scriptContent = scriptContent.replace("python ", "python3 -u ");
            scriptContent = scriptContent.replace("\u0000PY3U\u0000 ", "python3 -u ");

            // 4. pip → pip3
            scriptContent = scriptContent.replace("pip3 ", "\u0000PIP3\u0000 ");
            scriptContent = scriptContent.replace("pip ", "pip3 ");
            scriptContent = scriptContent.replace("\u0000PIP3\u0000 ", "pip3 ");

            System.out.println("[AppManager] Applied Kernel-level path translation and ABI bridging to script.");
            System.out.println("[AppManager] Python unbuffered mode (-u) enforced globally via command injection.");
            return scriptContent;
        }

        /**
         * 致命错误检测 — 扫描 Bash 输出中的关键错误信号。
         * <p>
         * 当检测到以下模式时返回 true，触发 SemanticCrashAnalyzer 和 AutoMedic 自愈流水线：
         * <ul>
         *   <li>command not found — 命令不存在（如 python 而非 python3）</li>
         *   <li>No such file or directory — 脚本或依赖文件缺失</li>
         *   <li>ModuleNotFoundError — Python 依赖包缺失</li>
         *   <li>ImportError — Python 导入失败</li>
         *   <li>SyntaxError — 语法错误</li>
         *   <li>Permission denied — 权限不足</li>
         * </ul>
         */
        private boolean containsFatalError(String text) {
            if (text == null || text.isBlank()) return false;
            String lower = text.toLowerCase();
            String[] fatalPatterns = {
                    "not found",
                    "no such file",
                    "module not found",
                    "importerror",
                    "syntaxerror",
                    "permission denied",
                    "segmentation fault",
                    "killed",
                    "traceback (most recent call last)"
            };
            for (String pattern : fatalPatterns) {
                if (lower.contains(pattern)) {
                    System.err.println("[GenericAppAgent] Fatal pattern detected: '" + pattern + "'");
                    return true;
                }
            }
            return false;
        }

        /**
         * 从 entrypoint 命令中提取 VFS 脚本路径。
         * <p>
         * 例如 "sh /factory/run_all.sh" → "/factory/run_all.sh"
         * 例如 "python3 /factory/scripts/crawl.py" → "/factory/scripts/crawl.py"
         * 如果不包含 VFS 路径（不以 /factory/ 或 /memories/ 开头），返回 null。
         */
        private String extractVfsScriptPath(String entrypoint) {
            if (entrypoint == null) return null;
            String trimmed = entrypoint.trim();
            // 去掉常见的运行器前缀：sh, bash, python, python3
            String[] prefixes = {"sh ", "bash ", "python3 ", "python "};
            String path = trimmed;
            for (String prefix : prefixes) {
                if (path.startsWith(prefix)) {
                    path = path.substring(prefix.length()).trim();
                    break;
                }
            }
            // 只认 VFS 路径（/factory/ 或 /memories/ 下的文件）
            if (path.startsWith("/factory/") || path.startsWith("/memories/")) {
                // 去掉可能的命令行参数（空格后的部分）
                int spaceIdx = path.indexOf(' ');
                if (spaceIdx > 0) {
                    path = path.substring(0, spaceIdx);
                }
                return path;
            }
            return null;
        }
    }
}
