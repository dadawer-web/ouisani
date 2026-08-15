package com.ouisani.aios.core.syscall;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.security.ObjectManager;
import com.ouisani.aios.user.bin.AiosApt;
import com.ouisani.aios.user.bin.CoreUtils;
import com.ouisani.aios.vfs.MutableFileNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extracted legacy + VFS syscall handlers.
 * <p>
 * Originally part of {@link SyscallDispatcher}; moved out to keep that file
 * under the ratchet budget (&lt;1000 LOC). All methods are package-private and
 * {@code static}; required runtime collaborators ({@link VfsManager},
 * {@link ObjectManager}) are passed in explicitly so the dispatcher can wire
 * them at call sites without retaining instance state here.
 *
 * <h3>Groups:</h3>
 * <ul>
 *   <li>VFS syscalls (legacy): {@code vfs.read}, {@code vfs.write},
 *       {@code vfs.snapshot}, {@code vfs.rollback}</li>
 *   <li>Legacy namespace syscalls: {@code tool.*}, {@code coreutils.*},
 *       {@code apt.*}, {@code jit.*}, {@code bin.*}, {@code handle.*}</li>
 * </ul>
 */
final class SyscallLegacyHandler {

    private static final Logger log = LoggerFactory.getLogger(SyscallLegacyHandler.class);

    private SyscallLegacyHandler() {}

    // ── VFS Syscalls (legacy) ──

    static SyscallResponse handleVfsRead(String agentId, SyscallRequest request, VfsManager vfsManager) {
        if (vfsManager == null) {
            return SyscallResponse.fail("VFS 管理器未配置");
        }

        String path = request.paramString("path");
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("缺少参数: path");
        }

        try {
            var nodeOpt = vfsManager.resolve(path);
            if (nodeOpt.isEmpty()) {
                return SyscallResponse.fail("路径未找到: " + path);
            }
            String content = nodeOpt.get().read();
            return SyscallResponse.ok(content);
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    static SyscallResponse handleVfsWrite(String agentId, SyscallRequest request, VfsManager vfsManager) {
        if (vfsManager == null) {
            return SyscallResponse.fail("VFS 管理器未配置");
        }

        String path = request.paramString("path");
        String payload = request.paramString("data");
        if (payload == null) {
            payload = request.paramString("payload");
        }
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("缺少参数: path");
        }
        if (payload == null) {
            return SyscallResponse.fail("缺少参数: data");
        }

        try {
            var nodeOpt = vfsManager.resolve(path);
            if (nodeOpt.isEmpty()) {
                MutableFileNode newNode = new MutableFileNode(path);
                newNode.write(payload);
                vfsManager.mount(extractDirPath(path), extractFileName(path), newNode);
                log.debug("[VFS] Auto-created file node: {}", path);
                return SyscallResponse.ok();
            }
            boolean ok = nodeOpt.get().write(payload);
            return ok ? SyscallResponse.ok() : SyscallResponse.fail("写入被节点拒绝");
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    // ── VFS Snapshot & Rollback Syscalls ──

    static SyscallResponse handleVfsSnapshot(String agentId, SyscallRequest request, VfsManager vfsManager) {
        if (vfsManager == null) {
            return SyscallResponse.fail("VFS 管理器未配置");
        }

        String path = request.paramString("path");
        String label = request.paramString("label");
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("缺少参数: path");
        }

        try {
            var nodeOpt = vfsManager.resolve(path);
            if (nodeOpt.isEmpty()) {
                return SyscallResponse.fail("路径未找到: " + path);
            }

            if (nodeOpt.get() instanceof com.ouisani.aios.vfs.ShadowCopyNode shadow) {
                long timestamp = shadow.createSnapshot(label);
                return SyscallResponse.ok("快照已创建: timestamp=" + timestamp
                        + " label=" + (label != null ? label : "auto")
                        + " cowPages=" + shadow.cowPageCount());
            } else {
                // 自动包装为 ShadowCopyNode
                com.ouisani.aios.vfs.ShadowCopyNode shadowNode =
                        new com.ouisani.aios.vfs.ShadowCopyNode(path, nodeOpt.get());
                long timestamp = shadowNode.createSnapshot(label);
                // 替换 VFS 中的节点
                vfsManager.mount(extractDirPath(path), extractFileName(path), shadowNode);
                return SyscallResponse.ok("快照已创建 (自动包装): timestamp=" + timestamp
                        + " cowPages=" + shadowNode.cowPageCount());
            }
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    static SyscallResponse handleVfsRollback(String agentId, SyscallRequest request, VfsManager vfsManager) {
        if (vfsManager == null) {
            return SyscallResponse.fail("VFS 管理器未配置");
        }

        String path = request.paramString("path");
        String timestampStr = request.paramString("timestamp");
        String label = request.paramString("label");
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("缺少参数: path");
        }

        try {
            var nodeOpt = vfsManager.resolve(path);
            if (nodeOpt.isEmpty()) {
                return SyscallResponse.fail("路径未找到: " + path);
            }

            if (!(nodeOpt.get() instanceof com.ouisani.aios.vfs.ShadowCopyNode shadow)) {
                return SyscallResponse.fail("路径不是 ShadowCopyNode — 请先创建快照");
            }

            boolean success;
            if (label != null && !label.isEmpty()) {
                success = shadow.rollbackToLabel(label);
            } else if (timestampStr != null && !timestampStr.isEmpty()) {
                long timestamp = Long.parseLong(timestampStr);
                success = shadow.rollback(timestamp);
            } else {
                success = shadow.rollbackToLatest();
            }

            if (success) {
                return SyscallResponse.ok("回滚成功: path=" + path
                        + " cowPages=" + shadow.cowPageCount()
                        + " snapshots=" + shadow.snapshotCount());
            } else {
                return SyscallResponse.fail("回滚失败: 未找到匹配的快照");
            }
        } catch (NumberFormatException e) {
            return SyscallResponse.fail("无效的时间戳格式");
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    static String extractDirPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash <= 0) return "/";
        return path.substring(0, lastSlash);
    }

    static String extractFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash < 0) return path;
        return path.substring(lastSlash + 1);
    }

    // ── Dynamic Tool Plugin Syscalls (legacy) ──

    static SyscallResponse handleToolPlugin(String agentId, SyscallRequest request) {
        PluginManager pluginManager = PluginManager.getInstance();
        String action = request.fullAction();

        if (!pluginManager.hasPlugin(action)) {
            return SyscallResponse.fail("插件未注册: " + action);
        }

        String paramsJson = SyscallDispatcher.serializeParams(request.params());

        try {
            String result = pluginManager.executePlugin(action, paramsJson);
            log.info("[Syscall Dispatcher] 插件 '{}' 已为 Agent '{}' 成功执行", action, agentId);
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("插件执行失败: " + e.getMessage());
        }
    }

    // ── CoreUtils Syscalls ──

    static SyscallResponse handleCoreUtils(String agentId, SyscallRequest request) {
        String subAction = request.fullAction().substring("coreutils.".length());
        try {
            String result = CoreUtils.dispatch(subAction, request.params());
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("CoreUtils 错误: " + e.getMessage());
        }
    }

    // ── APT (Package Manager) Syscalls ──

    static SyscallResponse handleApt(String agentId, SyscallRequest request) {
        String subAction = request.fullAction().substring("apt.".length());
        try {
            String result = switch (subAction) {
                case "install" -> {
                    String pkg = request.paramString("package");
                    if (pkg == null || pkg.isEmpty()) yield "缺少参数: package";
                    AiosApt.install(pkg);
                    yield "包 '" + pkg + "' 安装成功";
                }
                case "remove" -> {
                    String pkg = request.paramString("package");
                    if (pkg == null || pkg.isEmpty()) yield "缺少参数: package";
                    AiosApt.remove(pkg);
                    yield "包 '" + pkg + "' 已卸载";
                }
                case "list" -> AiosApt.list();
                default -> "未知的 apt 命令: " + subAction;
            };
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("APT 错误: " + e.getMessage());
        }
    }

    // ── JIT (Just-In-Time Compilation) Syscalls ──

    static SyscallResponse handleJit(String agentId, SyscallRequest request) {
        String subAction = request.fullAction().substring("jit.".length());
        try {
            String result = switch (subAction) {
                case "compile" -> {
                    String sourceCode = request.paramString("source");
                    String language = request.paramString("language");
                    if (sourceCode == null || sourceCode.isEmpty())
                        yield "缺少参数: source";
                    if (language == null || language.isEmpty())
                        language = "java";

                    com.ouisani.aios.core.sandbox.CompilerBridge.CompilationResult compileResult =
                            com.ouisani.aios.core.sandbox.CompilerBridge.instance()
                                    .compile(sourceCode, language);

                    if (compileResult.success()) {
                        yield "JIT 编译成功: id=" + compileResult.compileId()
                                + " lang=" + compileResult.language()
                                + " output=" + compileResult.outputPath()
                                + (compileResult.isMock() ? " (MOCK: " + compileResult.mockReason() + ")" : "");
                    } else {
                        yield "JIT 编译失败: " + compileResult.errorMessage();
                    }
                }
                case "execute" -> {
                    String compileId = request.paramString("compile_id");
                    if (compileId == null || compileId.isEmpty())
                        yield "缺少参数: compile_id";

                    com.ouisani.aios.core.sandbox.CompilerBridge.CompilationResult compileResult =
                            com.ouisani.aios.core.sandbox.CompilerBridge.instance().getResult(compileId);
                    if (compileResult == null)
                        yield "编译结果未找到: " + compileId;

                    // 在 Ring 3 沙箱中执行
                    com.ouisani.aios.core.sandbox.GraalWasmSandbox sandbox =
                            new com.ouisani.aios.core.sandbox.GraalWasmSandbox();
                    sandbox.initContext();
                    com.ouisani.aios.core.sandbox.GraalWasmSandbox.SandboxExecutionResult execResult =
                            sandbox.executeJitArtifact(compileResult);

                    if (execResult.success()) {
                        yield "执行结果: " + execResult.result();
                    } else {
                        yield "执行失败: " + execResult.error();
                    }
                }
                case "stats" -> com.ouisani.aios.core.sandbox.CompilerBridge.instance().getStatsReport();
                default -> "未知的 jit 命令: " + subAction;
            };
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("JIT 错误: " + e.getMessage());
        }
    }

    // ── bin.* Unified User-Space Binary Syscalls ──

    static SyscallResponse handleBin(String agentId, SyscallRequest request) {
        String subAction = request.fullAction().substring("bin.".length());
        try {
            String result = switch (subAction) {
                case "ps" -> CoreUtils.ps();
                case "kill" -> CoreUtils.kill(request.paramString("pid"));
                case "whoami" -> CoreUtils.whoami();
                case "uptime" -> CoreUtils.uptime();
                case "free" -> CoreUtils.free();
                case "install" -> {
                    String pkg = request.paramString("package");
                    if (pkg == null || pkg.isEmpty()) yield "缺少参数: package";
                    AiosApt.install(pkg);
                    yield "包 '" + pkg + "' 安装成功";
                }
                default -> "未知的 bin 命令: " + subAction;
            };
            log.info("[用户空间] 核心工具和包管理器已链接到 Intent Router。bin.{} 已分发", subAction);
            return SyscallResponse.ok(result);
        } catch (Exception e) {
            return SyscallResponse.fail("bin 错误: " + e.getMessage());
        }
    }

    // ── Handle Syscalls ──

    static SyscallResponse handleOpen(String agentId, SyscallRequest request, ObjectManager objectManager) {
        if (objectManager == null) {
            return SyscallResponse.fail("Object 管理器未配置");
        }

        String path = request.paramString("path");
        if (path == null || path.isEmpty()) {
            return SyscallResponse.fail("缺少参数: path");
        }

        try {
            int handle = objectManager.openHandle(agentId, path);
            return SyscallResponse.ok(String.valueOf(handle));
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    static SyscallResponse handleRead(String agentId, SyscallRequest request, ObjectManager objectManager) {
        if (objectManager == null) {
            return SyscallResponse.fail("Object 管理器未配置");
        }

        Integer handle = request.paramInt("handle", -1);
        if (handle < 0) {
            return SyscallResponse.fail("缺少或无效的参数: handle");
        }

        try {
            VfsNode node = objectManager.getNodeByHandle(handle);
            String content = node.read();
            return SyscallResponse.ok(content);
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }

    static SyscallResponse handleClose(String agentId, SyscallRequest request, ObjectManager objectManager) {
        if (objectManager == null) {
            return SyscallResponse.fail("Object 管理器未配置");
        }

        Integer handle = request.paramInt("handle", -1);
        if (handle < 0) {
            return SyscallResponse.fail("缺少或无效的参数: handle");
        }

        try {
            boolean closed = objectManager.closeHandle(handle);
            return closed ? SyscallResponse.ok() : SyscallResponse.fail("句柄已关闭或无效");
        } catch (Exception e) {
            return SyscallResponse.fail(e);
        }
    }
}
