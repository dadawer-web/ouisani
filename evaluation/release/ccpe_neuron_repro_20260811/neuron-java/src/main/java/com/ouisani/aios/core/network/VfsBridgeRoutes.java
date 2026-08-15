package com.ouisani.aios.core.network;

import com.ouisani.aios.core.VfsManager;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * VFS 工作区桥接路由 — 从 AppGateway 抽取的前端与 VFS 双向通道。
 * <p>
 * 借鉴 Apboa 工作空间，提供 ZIP 上传、文件读写、目录列举、下载等能力，
 * 让前端可以像操作本地文件系统一样操作 AIOS 的虚拟文件系统。
 * <p>
 * OS 类比：Linux 的 fuse 挂载点 — 把内核 VFS 暴露给用户态 HTTP 客户端。
 */
final class VfsBridgeRoutes {

    private static final Logger log = LoggerFactory.getLogger(VfsBridgeRoutes.class);

    private VfsBridgeRoutes() {}

    /**
     * 挂载 VFS Workspace Bridge 路由到 Javalin 应用。
     *
     * <ul>
     *   <li>POST /api/vfs/upload?taskId=xxx  — 上传 ZIP/文件，自动解压到 /vfs/workspace/{taskId}/</li>
     *   <li>GET  /api/vfs/read?path=xxx      — 读取 VFS 文件内容</li>
     *   <li>GET  /api/vfs/list?path=xxx      — 列出 VFS 目录下的文件</li>
     *   <li>GET  /api/vfs/download?path=xxx  — 下载 VFS 文件</li>
     *   <li>POST /api/vfs/write              — 写入 VFS 文件</li>
     * </ul>
     */
    static void attachTo(Javalin app) {
        // POST /api/vfs/upload — 上传 ZIP 文件并解压到 VFS workspace
        app.post("/api/vfs/upload", ctx -> {
            String token = ctx.queryParam("token");
            if (!AuthManager.instance().verifyToken(token)) {
                ctx.status(401).result("{\"error\":\"Unauthorized\"}");
                return;
            }

            String taskId = ctx.queryParam("taskId");
            if (taskId == null || taskId.isBlank()) taskId = "default";

            byte[] fileBytes = ctx.bodyAsBytes();
            if (fileBytes.length == 0) {
                ctx.status(400).result("{\"error\":\"Empty body\"}");
                return;
            }

            String vfsBase = "/vfs/workspace/" + taskId;
            VfsManager vfs = VfsManager.instance();

            // 确保目录存在
            vfs.writeText(vfsBase + "/.keep", "");

            int fileCount = 0;
            boolean isZip = fileBytes.length > 2 && fileBytes[0] == 0x50 && fileBytes[1] == 0x4B;

            if (isZip) {
                // ZIP 解压
                try (var zis = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(fileBytes))) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        if (entry.isDirectory()) {
                            vfs.writeText(vfsBase + "/" + entry.getName() + "/.keep", "");
                            continue;
                        }
                        // 读取条目内容
                        byte[] buf = new byte[8192];
                        int len;
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        while ((len = zis.read(buf)) > 0) {
                            baos.write(buf, 0, len);
                        }
                        String content = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                        vfs.writeText(vfsBase + "/" + entry.getName(), content);
                        fileCount++;
                    }
                }
                log.info("[VFS Bridge] ZIP 解压完成: {} 个文件 → {}", fileCount, vfsBase);
            } else {
                // 单文件上传
                String filename = ctx.queryParam("filename");
                if (filename == null || filename.isBlank()) filename = "upload_" + System.currentTimeMillis();
                String content = new String(fileBytes, java.nio.charset.StandardCharsets.UTF_8);
                vfs.writeText(vfsBase + "/" + filename, content);
                fileCount = 1;
                log.info("[VFS Bridge] 单文件上传: {} → {}/{}", filename, vfsBase, filename);
            }

            ctx.contentType("application/json");
            ctx.result("{\"success\":true,\"taskId\":\"" + taskId
                    + "\",\"vfsPath\":\"" + vfsBase
                    + "\",\"fileCount\":" + fileCount + "}");
        });

        // OPTIONS for CORS
        app.options("/api/vfs/upload", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        // GET /api/vfs/read — 读取 VFS 文件内容
        app.get("/api/vfs/read", ctx -> {
            String token = ctx.queryParam("token");
            if (!AuthManager.instance().verifyToken(token)) {
                ctx.status(401).result("{\"error\":\"Unauthorized\"}");
                return;
            }
            String path = ctx.queryParam("path");
            if (path == null || path.isBlank()) {
                ctx.status(400).result("{\"error\":\"Missing 'path' parameter\"}");
                return;
            }
            String content = VfsManager.instance().readText(path);
            if (content == null) {
                ctx.status(404).result("{\"error\":\"File not found: " + path + "\"}");
                return;
            }
            ctx.contentType("text/plain; charset=utf-8");
            ctx.result(content);
        });

        // GET /api/vfs/list — 列出 VFS 目录下的文件
        app.get("/api/vfs/list", ctx -> {
            String token = ctx.queryParam("token");
            if (!AuthManager.instance().verifyToken(token)) {
                ctx.status(401).result("{\"error\":\"Unauthorized\"}");
                return;
            }
            String path = ctx.queryParam("path");
            if (path == null || path.isBlank()) path = "/";

            List<String> files = VfsManager.instance().listFilesUnder(path);
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (String f : files) {
                if (!first) sb.append(",");
                sb.append("\"").append(f).append("\"");
                first = false;
            }
            sb.append("]");
            ctx.contentType("application/json");
            ctx.result(sb.toString());
        });

        // GET /api/vfs/download — 下载 VFS 文件
        app.get("/api/vfs/download", ctx -> {
            String token = ctx.queryParam("token");
            if (!AuthManager.instance().verifyToken(token)) {
                ctx.status(401).result("{\"error\":\"Unauthorized\"}");
                return;
            }
            String path = ctx.queryParam("path");
            if (path == null || path.isBlank()) {
                ctx.status(400).result("{\"error\":\"Missing 'path' parameter\"}");
                return;
            }
            String content = VfsManager.instance().readText(path);
            if (content == null) {
                ctx.status(404).result("{\"error\":\"File not found: " + path + "\"}");
                return;
            }
            String filename = path.substring(path.lastIndexOf('/') + 1);
            ctx.header("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            ctx.contentType("application/octet-stream");
            ctx.result(content);
        });

        // POST /api/vfs/write — 写入 VFS 文件
        app.post("/api/vfs/write", ctx -> {
            String token = ctx.queryParam("token");
            if (!AuthManager.instance().verifyToken(token)) {
                ctx.status(401).result("{\"error\":\"Unauthorized\"}");
                return;
            }
            String body = ctx.body();
            var jsonObj = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
            String path = jsonObj.has("path") ? jsonObj.get("path").getAsString() : null;
            String content = jsonObj.has("content") ? jsonObj.get("content").getAsString() : null;
            if (path == null || content == null) {
                ctx.status(400).result("{\"error\":\"Missing 'path' or 'content'\"}");
                return;
            }
            boolean ok = VfsManager.instance().writeText(path, content);
            ctx.contentType("application/json");
            ctx.result("{\"success\":" + ok + ",\"path\":\"" + path + "\"}");
        });

        app.options("/api/vfs/write", ctx -> {
            ctx.header("Access-Control-Allow-Origin", "*");
            ctx.header("Access-Control-Allow-Methods", "POST, OPTIONS");
            ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            ctx.result("");
        });

        log.info("[App Gateway] VFS Workspace Bridge API 已挂载: /api/vfs/upload, /api/vfs/read, /api/vfs/list, /api/vfs/download, /api/vfs/write");
        System.out.println("  ✓ [App Gateway] VFS Workspace Bridge: /api/vfs/upload, /api/vfs/read, /api/vfs/list, /api/vfs/download, /api/vfs/write");
    }
}
