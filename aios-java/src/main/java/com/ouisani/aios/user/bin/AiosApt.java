package com.ouisani.aios.user.bin;

import com.ouisani.aios.core.plugin.PluginManager;
import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AIOS 包管理器（AiosApt）— 从模拟远程仓库安装并热加载 WASM 插件到本地插件目录。
 * <p>
 * OS 类比：相当于 apt-get / yum — 从远程仓库下载软件包并安装到本地系统。
 * 在 AIOS 中，"软件包"是 WASM 插件，"安装"意味着下载字节码并热加载到 PluginManager。
 * <p>
 * 使用示例：
 * <pre>
 *   AiosApt.install("math_tool");     // 下载并注册 tool.math_tool
 *   AiosApt.install("translator");    // 下载并注册 tool.translator
 * </pre>
 */
public final class AiosApt {

    private static final Logger log = LoggerFactory.getLogger(AiosApt.class);

    /** 插件本地安装目录 */
    private static final String PLUGIN_DIR = "/opt/aios/plugins";
    /** 远程插件仓库 URL */
    private static final String REGISTRY_URL = "https://registry.aios.dev/plugins";

    private AiosApt() {}

    /**
     * 按名称安装插件。模拟从远程仓库下载 .wasm 文件，
     * 保存到本地，并热加载到 PluginManager。
     *
     * @param pluginUrl 插件名称或 URL（如 "math_tool"）
     */
    public static void install(String pluginUrl) {
        if (pluginUrl == null || pluginUrl.isBlank()) {
            log.warn("[APT] Empty package name");
            return;
        }

        // Extract package name from URL if needed
        String packageName = pluginUrl;
        if (pluginUrl.contains("/")) {
            packageName = pluginUrl.substring(pluginUrl.lastIndexOf('/') + 1);
        }
        if (packageName.endsWith(".wasm")) {
            packageName = packageName.substring(0, packageName.length() - 5);
        }

        String wasmFileName = packageName + ".wasm";
        String toolName = "tool." + packageName;

        System.out.printf("  📦 [APT] Fetching package from registry... %s/%s%n", REGISTRY_URL, wasmFileName);
        log.info("[APT] Fetching package from registry: {}", wasmFileName);

        // Step 1: Simulate download
        byte[] bytecode = simulateDownload(packageName);
        if (bytecode == null || bytecode.length == 0) {
            System.out.printf("  📦 [APT] Failed to download '%s'%n", packageName);
            log.error("[APT] Download failed for: {}", packageName);
            return;
        }

        // Step 2: Write to local plugin directory
        Path pluginDir = Path.of(PLUGIN_DIR);
        try {
            Files.createDirectories(pluginDir);
            Path wasmPath = pluginDir.resolve(wasmFileName);
            Files.write(wasmPath, bytecode);
        } catch (IOException e) {
            System.out.printf("  📦 [APT] Failed to write plugin: %s%n", e.getMessage());
            log.error("[APT] Write failed: {}", e.getMessage());
            return;
        }

        // Step 3: Hot-load via PluginManager
        PluginManager.getInstance().registerPlugin(toolName, bytecode);

        System.out.printf("  📦 [APT] Installed successfully! → %s (hot-loaded as %s)%n", wasmFileName, toolName);
        log.info("[APT] Fetching package from registry... Installed successfully! → {}", wasmFileName);
    }

    /**
     * 按名称卸载插件。
     */
    public static void remove(String packageName) {
        String wasmFileName = packageName + ".wasm";
        Path wasmPath = Path.of(PLUGIN_DIR, wasmFileName);

        try {
            Files.deleteIfExists(wasmPath);
        } catch (IOException e) {
            log.warn("[APT] Failed to delete plugin file: {}", e.getMessage());
        }

        System.out.printf("  🗑 [APT] Package '%s' removed%n", packageName);
        log.info("[APT] Package removed: {}", packageName);
    }

    /**
     * 列出所有已安装的插件。
     */
    public static String list() {
        var plugins = PluginManager.getInstance().registeredPlugins();
        if (plugins.isEmpty()) return "No plugins installed.";

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-30s %s%n", "TOOL NAME", "STATUS"));
        sb.append("-".repeat(45)).append("\n");
        for (String name : plugins) {
            sb.append(String.format("%-30s %s%n", name, "LOADED"));
        }
        return sb.toString();
    }

    /**
     * 模拟从远程仓库下载 WASM 插件。生成一个最小有效 WASM 模块。
     */
    private static byte[] simulateDownload(String packageName) {
        byte[] wasmHeader = new byte[]{
                0x00, 0x61, 0x73, 0x6D, // magic: \0asm
                0x01, 0x00, 0x00, 0x00, // version: 1
                0x01, 0x04, 0x01, 0x60, 0x00, 0x00,
                0x03, 0x02, 0x01, 0x00,
                0x07, (byte) (8 + packageName.length()), 0x01,
                (byte) packageName.length()
        };
        byte[] nameBytes = packageName.getBytes();
        byte[] exportSuffix = new byte[]{0x00, 0x00, 0x0A, 0x04, 0x01, 0x02, 0x00, 0x0B};

        byte[] result = new byte[wasmHeader.length + nameBytes.length + exportSuffix.length];
        System.arraycopy(wasmHeader, 0, result, 0, wasmHeader.length);
        System.arraycopy(nameBytes, 0, result, wasmHeader.length, nameBytes.length);
        System.arraycopy(exportSuffix, 0, result, wasmHeader.length + nameBytes.length, exportSuffix.length);
        return result;
    }
}
