package com.ouisani.aios.core.plugin;

import com.ouisani.aios.core.sandbox.GraalWasmSandbox;
import com.ouisani.aios.core.syscall.SyscallDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic Tool Registration Center — auto-discovers WASM plugins
 * from the host filesystem and registers them as new Syscall actions.
 * <p>
 * Inspired by cutting-edge AIOS designs where the kernel can dynamically
 * extend its syscall surface by scanning a plugin directory. Each discovered
 * {@code .wasm} file becomes a first-class {@code tool.*} action that
 * Agents can invoke through the standard Syscall interface.
 * <p>
 * Example: {@code math.wasm} → syscall action {@code tool.math}
 */
public final class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final ConcurrentHashMap<String, byte[]> pluginRegistry = new ConcurrentHashMap<>();
    private GraalWasmSandbox sandbox;

    private static final class Holder {
        static final PluginManager INSTANCE = new PluginManager();
    }

    public static PluginManager getInstance() {
        return Holder.INSTANCE;
    }

    private PluginManager() {}

    /**
     * Configure the GraalWasmSandbox used to execute plugin bytecode.
     * Must be called before {@link #scanAndLoadPlugins}.
     */
    public void configure(GraalWasmSandbox sandbox) {
        this.sandbox = sandbox;
        log.info("[Plugin Manager] Configured with GraalWasmSandbox");
    }

    /**
     * Scan a host directory for WASM plugin files and register each
     * as a new Syscall action.
     * <p>
     * For each {@code .wasm} file found:
     * <ol>
     *   <li>Read the bytecode into memory</li>
     *   <li>Derive the tool name: {@code math.wasm} → {@code tool.math}</li>
     *   <li>Register in the local plugin registry</li>
     *   <li>The SyscallDispatcher will route {@code tool.*} actions here</li>
     * </ol>
     *
     * @param pluginDir absolute path on the host (e.g. "/opt/aios/plugins")
     */
    public void scanAndLoadPlugins(String pluginDir) {
        Path dir = Path.of(pluginDir);
        if (!Files.isDirectory(dir)) {
            log.warn("[Plugin Manager] Plugin directory does not exist: {}", pluginDir);
            System.out.printf("  ⚠ [Plugin Manager] Directory not found: %s%n", pluginDir);
            return;
        }

        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.printf("  ║  [Plugin Manager] Scanning: %s%n", pluginDir);
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        int discovered = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.wasm")) {
            for (Path wasmFile : stream) {
                String fileName = wasmFile.getFileName().toString();
                String toolName = "tool." + fileName.substring(0, fileName.length() - ".wasm".length());

                try {
                    byte[] bytecode = Files.readAllBytes(wasmFile);
                    pluginRegistry.put(toolName, bytecode);
                    discovered++;

                    log.info("[Plugin Manager] Auto-discovered and registered new Syscall: {}", toolName);
                    System.out.printf("  ├─ [Plugin Manager] Auto-discovered and registered new Syscall: %s (%d bytes)%n",
                            toolName, bytecode.length);
                } catch (IOException e) {
                    log.error("[Plugin Manager] Failed to read plugin: {} — {}", wasmFile, e.getMessage());
                    System.out.printf("  ├─ [Plugin Manager] Failed to read: %s (%s)%n", fileName, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("[Plugin Manager] Failed to scan plugin directory: {}", e.getMessage());
            System.out.printf("  ⚠ [Plugin Manager] Scan failed: %s%n", e.getMessage());
            return;
        }

        System.out.printf("  └─ [Plugin Manager] Scan complete: %d plugin(s) registered%n", discovered);
        log.info("[Plugin Manager] Plugin scan complete: {} plugin(s) from '{}'", discovered, pluginDir);
    }

    /**
     * Execute a registered plugin tool by its action name.
     * <p>
     * Called by {@link SyscallDispatcher} when it encounters a {@code tool.*} action.
     * The parameters from the SyscallRequest are serialized as a JSON string
     * and passed to the WASM module's entry function.
     *
     * @param action     the tool action (e.g. "tool.math")
     * @param parameters the syscall parameters, serialized as JSON for the WASM module
     * @return the execution result as a string
     * @throws Exception if the plugin is not found or execution fails
     */
    public String executePlugin(String action, String parameters) throws Exception {
        byte[] bytecode = pluginRegistry.get(action);
        if (bytecode == null) {
            throw new IllegalArgumentException("Plugin not registered: " + action);
        }

        if (sandbox == null) {
            throw new IllegalStateException("GraalWasmSandbox not configured in PluginManager");
        }

        log.info("[Plugin Manager] Executing plugin '{}' ({} bytes)", action, bytecode.length);
        System.out.printf("  ├─ [Plugin Manager] Executing plugin '%s' via GraalWasmSandbox%n", action);

        // Use the SandboxProvider interface: pass parameters as code context
        // The WASM module's "main" function will be invoked
        String result = sandbox.executeCode(bytesToHex(bytecode), "main");
        return result;
    }

    /**
     * Check if a tool action is registered.
     */
    public boolean hasPlugin(String action) {
        return pluginRegistry.containsKey(action);
    }

    /**
     * Get all registered plugin action names.
     */
    public java.util.Set<String> registeredPlugins() {
        return pluginRegistry.keySet();
    }

    /**
     * Manually register a plugin with bytecode.
     */
    public void registerPlugin(String toolName, byte[] bytecode) {
        pluginRegistry.put(toolName, bytecode);
        log.info("[Plugin Manager] Manually registered plugin: {} ({} bytes)", toolName, bytecode.length);
        System.out.printf("  ├─ [Plugin Manager] Manually registered: %s (%d bytes)%n", toolName, bytecode.length);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
