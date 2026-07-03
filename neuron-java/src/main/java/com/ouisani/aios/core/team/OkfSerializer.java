package com.ouisani.aios.core.team;

import com.ouisani.aios.core.VfsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OKF (Open Knowledge Format) 序列化器 — 借鉴 Google Knowledge Catalog 标准。
 * <p>
 * 当子 Agent 向上级汇报工作成果时，强制将其输出序列化为 OKF 格式的
 * Markdown 文件写入 VFS。上游 Agent 读取时，可通过极简的 OKF 解析器
 * 将 YAML frontmatter 转换为强类型 Java Map 供条件路由使用，
 * 将 Markdown 正文作为 Prompt 供思考使用。
 * <p>
 * OKF 格式：
 * <pre>
 * ---
 * type: vulnerability_report
 * confidence: 0.95
 * target: backend_service
 * sender: Security_Auditor
 * timestamp: 2026-06-22T12:00:00Z
 * ---
 * # SQL 注入漏洞分析
 * 在 `login` 接口发现...
 * </pre>
 *
 * @see AgentMailbox
 * @see MailMessage
 */
public class OkfSerializer {

    private static final Logger log = LoggerFactory.getLogger(OkfSerializer.class);

    /** OKF 文件存储根目录 */
    private static final String OKF_BASE = "/vfs/okf";

    /**
     * 将 MailMessage 的 payload 序列化为 OKF 格式并写入 VFS。
     * <p>
     * 文件路径：/vfs/okf/{receiverId}/{type}_{timestamp}.md
     *
     * @param message 邮件消息
     * @return 写入的 VFS 路径，失败返回 null
     */
    public static String serializeToVfs(MailMessage message) {
        String receiverId = message.getReceiverId();
        String type = message.getType().name().toLowerCase();
        String timestamp = Instant.now().toString().replace(":", "-").replace(".", "_");
        String filename = type + "_" + timestamp + ".md";
        String dirPath = OKF_BASE + "/" + receiverId;
        String vfsPath = dirPath + "/" + filename;

        // 构建 OKF 内容
        String okfContent = buildOkfDocument(message);

        // 写入 VFS
        boolean ok = VfsManager.instance().writeText(vfsPath, okfContent);
        if (ok) {
            log.info("[OKF] 消息已序列化为 OKF 文档: {} → {}", message.getMessageId(), vfsPath);
            return vfsPath;
        } else {
            log.error("[OKF] 序列化失败: {}", vfsPath);
            return null;
        }
    }

    /**
     * 构建 OKF 格式的 Markdown 文档。
     * <p>
     * YAML frontmatter 包含结构化元数据，
     * Markdown body 包含自由文本内容。
     */
    private static String buildOkfDocument(MailMessage message) {
        StringBuilder sb = new StringBuilder();

        // ── YAML Frontmatter ──
        sb.append("---\n");
        sb.append("type: ").append(message.getType().name().toLowerCase()).append("\n");
        sb.append("sender: ").append(message.getSenderId()).append("\n");
        sb.append("receiver: ").append(message.getReceiverId()).append("\n");
        sb.append("priority: ").append(message.getPriority().name()).append("\n");
        sb.append("trace_id: ").append(message.getTraceId()).append("\n");
        sb.append("message_id: ").append(message.getMessageId()).append("\n");
        sb.append("timestamp: ").append(Instant.now().toString()).append("\n");
        sb.append("---\n\n");

        // ── Markdown Body ──
        String payload = message.getPayloadAsString();
        if (payload == null || payload.isEmpty()) {
            sb.append("*(empty payload)*\n");
        } else {
            // 如果 payload 本身就是 Markdown，直接嵌入
            sb.append(payload).append("\n");
        }

        return sb.toString();
    }

    /**
     * 从 VFS 读取 OKF 文档并解析。
     *
     * @param vfsPath OKF 文件路径
     * @return 解析结果（frontmatter + body），文件不存在返回 null
     */
    public static OkfDocument parseFromVfs(String vfsPath) {
        String content = VfsManager.instance().readText(vfsPath);
        if (content == null || content.isEmpty()) {
            return null;
        }
        return parse(content);
    }

    /**
     * 解析 OKF 格式的 Markdown 文档。
     * <p>
     * 解析逻辑：
     * 1. 提取 --- 之间的 YAML frontmatter
     * 2. 提取 --- 之后的 Markdown body
     *
     * @param content OKF 格式的字符串内容
     * @return 解析结果
     */
    public static OkfDocument parse(String content) {
        Map<String, String> frontmatter = new LinkedHashMap<>();
        String body = "";

        if (content.startsWith("---")) {
            // 找到第二个 ---
            int secondDash = content.indexOf("\n---", 3);
            if (secondDash > 0) {
                String yamlBlock = content.substring(3, secondDash).trim();
                body = content.substring(secondDash + 4).trim();

                // 解析简单的 YAML key: value
                for (String line : yamlBlock.split("\n")) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = line.substring(0, colonIdx).trim();
                        String value = line.substring(colonIdx + 1).trim();
                        frontmatter.put(key, value);
                    }
                }
            } else {
                body = content;
            }
        } else {
            body = content;
        }

        return new OkfDocument(frontmatter, body);
    }

    /**
     * OKF 文档解析结果。
     *
     * @param frontmatter YAML frontmatter 中的键值对
     * @param body        Markdown 正文内容
     */
    public record OkfDocument(Map<String, String> frontmatter, String body) {

        /**
         * 获取 frontmatter 中的字段值。
         */
        public String get(String key) {
            return frontmatter.get(key);
        }

        /**
         * 获取 frontmatter 中的字段值，带默认值。
         */
        public String getOrDefault(String key, String defaultValue) {
            return frontmatter.getOrDefault(key, defaultValue);
        }

        /**
         * 获取文档类型（frontmatter 中的 type 字段）。
         */
        public String type() {
            return frontmatter.get("type");
        }

        /**
         * 获取发送者（frontmatter 中的 sender 字段）。
         */
        public String sender() {
            return frontmatter.get("sender");
        }

        /**
         * 获取置信度（frontmatter 中的 confidence 字段）。
         */
        public double confidence() {
            String val = frontmatter.get("confidence");
            if (val == null) return 0.0;
            try {
                return Double.parseDouble(val);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        }
    }
}
