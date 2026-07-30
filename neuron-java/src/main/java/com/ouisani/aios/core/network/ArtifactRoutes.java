package com.ouisani.aios.core.network;

import com.ouisani.aios.core.config.AiosPaths;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流产物 HTTP 路由 — 让前端能看到工作流执行后生成的文件（.py / report.txt 等）。
 * <p>
 * 两个端点：
 * <ul>
 *   <li>{@code GET /api/artifacts/{workflowId}} — 列出 workspaces/{workflowId}/factory/ 下的产物文件</li>
 *   <li>{@code GET /api/artifacts/{workflowId}/file?name=xxx} — 读取单个产物文件内容（文本）</li>
 * </ul>
 * <p>
 * 物理路径：{@code AiosPaths.aiosHome()/workspaces/{workflowId}/factory/}。
 * workflowId 通常是用户输入的 prompt（可能含中文），Javalin pathParam 自动 URL-decode。
 * <p>
 * OS 类比：Linux 的 {@code /proc/<pid>/fd} — 让用户态查看进程产出的文件。
 */
public final class ArtifactRoutes {

    private static final Logger log = LoggerFactory.getLogger(ArtifactRoutes.class);

    private ArtifactRoutes() {}

    public static void attachTo(Javalin app) {
        // ── GET /api/artifacts/{workflowId} — 列出 factory 产物文件 ──
        app.get("/api/artifacts/{workflowId}", ctx -> {
            String workflowId = ctx.pathParam("workflowId");
            Path factoryDir = factoryDir(workflowId);
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.contentType("application/json");

            if (!Files.isDirectory(factoryDir)) {
                ctx.status(404);
                ctx.result("{\"error\":\"no artifacts found\",\"workflowId\":\"" + escapeJson(workflowId) + "\"}");
                return;
            }

            List<Map<String, Object>> files = new ArrayList<>();
            try (var stream = Files.list(factoryDir)) {
                stream.filter(Files::isRegularFile)
                      .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                      .forEach(p -> {
                          try {
                              Map<String, Object> f = new LinkedHashMap<>();
                              f.put("name", p.getFileName().toString());
                              f.put("size", Files.size(p));
                              f.put("modified", Files.getLastModifiedTime(p).toMillis());
                              f.put("text", isTextFile(p.getFileName().toString()));
                              files.add(f);
                          } catch (IOException ignore) { /* 跳过不可读文件 */ }
                      });
            } catch (IOException e) {
                ctx.status(500);
                ctx.result("{\"error\":\"list failed: " + escapeJson(e.getMessage()) + "\"}");
                return;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("{\"workflowId\":\"").append(escapeJson(workflowId)).append("\",");
            sb.append("\"count\":").append(files.size()).append(",\"files\":[");
            for (int i = 0; i < files.size(); i++) {
                if (i > 0) sb.append(",");
                Map<String, Object> f = files.get(i);
                sb.append("{\"name\":\"").append(escapeJson(String.valueOf(f.get("name")))).append("\",");
                sb.append("\"size\":").append(f.get("size")).append(",");
                sb.append("\"modified\":").append(f.get("modified")).append(",");
                sb.append("\"text\":").append(f.get("text")).append("}");
            }
            sb.append("]}");
            ctx.result(sb.toString());
        });

        // ── GET /api/artifacts/{workflowId}/file?name=xxx — 读取单个产物文件内容 ──
        app.get("/api/artifacts/{workflowId}/file", ctx -> {
            String workflowId = ctx.pathParam("workflowId");
            String name = ctx.queryParam("name");
            ctx.header("Access-Control-Allow-Origin", "*");

            if (name == null || name.isBlank()) {
                ctx.status(400);
                ctx.contentType("application/json");
                ctx.result("{\"error\":\"name query param required\"}");
                return;
            }

            Path factoryDir = factoryDir(workflowId).toAbsolutePath().normalize();
            Path file = factoryDir.resolve(name).normalize();
            // 路径穿越防护：解析后必须仍在 factory 目录内
            if (!file.startsWith(factoryDir)) {
                ctx.status(400);
                ctx.contentType("application/json");
                ctx.result("{\"error\":\"invalid path\"}");
                return;
            }
            if (!Files.isRegularFile(file)) {
                ctx.status(404);
                ctx.contentType("application/json");
                ctx.result("{\"error\":\"file not found\"}");
                return;
            }

            // 二进制文件不返回内容（前端按 text 标记决定是否请求）
            if (!isTextFile(file.getFileName().toString())) {
                ctx.status(415);
                ctx.contentType("application/json");
                ctx.result("{\"error\":\"binary file, preview not supported\",\"name\":\"" + escapeJson(name) + "\"}");
                return;
            }

            String content = Files.readString(file, StandardCharsets.UTF_8);
            ctx.contentType("application/json");
            ctx.result("{\"name\":\"" + escapeJson(name) + "\",\"size\":" + content.length()
                    + ",\"content\":\"" + escapeJson(content) + "\"}");
        });

        // CORS 预检
        app.options("/api/artifacts/{workflowId}", ctx -> corsOptions(ctx));
        app.options("/api/artifacts/{workflowId}/file", ctx -> corsOptions(ctx));

        log.info("[Artifacts] 路由已挂载: GET /api/artifacts/{workflowId}[/file]");
        System.out.println("  ✓ [Artifacts] 路由: GET /api/artifacts/{workflowId}[/file]");
    }

    /** workspaces/{workflowId}/factory 物理目录 */
    private static Path factoryDir(String workflowId) {
        return Paths.get(AiosPaths.aiosHome(), "workspaces", workflowId, "factory");
    }

    /** 是否为可在前端预览的文本文件 */
    private static boolean isTextFile(String name) {
        String lower = name.toLowerCase();
        return lower.endsWith(".py") || lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".json") || lower.endsWith(".js") || lower.endsWith(".ts")
                || lower.endsWith(".java") || lower.endsWith(".csv") || lower.endsWith(".yml")
                || lower.endsWith(".yaml") || lower.endsWith(".sh") || lower.endsWith(".html")
                || lower.endsWith(".css") || lower.endsWith(".xml") || lower.endsWith(".log")
                || lower.endsWith(".sql") || lower.endsWith(".toml") || lower.endsWith(".ini")
                || lower.endsWith(".cfg") || lower.endsWith(".conf");
    }

    private static void corsOptions(io.javalin.http.Context ctx) {
        ctx.header("Access-Control-Allow-Origin", "*");
        ctx.header("Access-Control-Allow-Methods", "GET, OPTIONS");
        ctx.header("Access-Control-Allow-Headers", "Content-Type");
        ctx.result("");
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
