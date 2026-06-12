package com.ouisani.aios.openclaw.secrets;

/**
 * 密钥引用 — 对标 OpenClaw 的 SecretRef。
 * <p>
 * 三段式标识：source:provider:id
 * <ul>
 *   <li>source: 密钥来源类型（env/file/exec）</li>
 *   <li>provider: 密钥提供者名称</li>
 *   <li>id: 密钥标识符</li>
 * </ul>
 * <p>
 * 示例：
 * <ul>
 *   <li>env:openai:OPENAI_API_KEY — 从环境变量读取</li>
 *   <li>file:telegram:./telegram-token.txt — 从文件读取</li>
 *   <li>exec:aws:sts AssumeRole — 执行命令获取</li>
 * </ul>
 *
 * @param source   密钥来源（env/file/exec）
 * @param provider 密钥提供者
 * @param id       密钥标识符
 */
public record SecretRef(
        String source,
        String provider,
        String id
) {
    public SecretRef {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("SecretRef source required");
        if (provider == null || provider.isBlank()) throw new IllegalArgumentException("SecretRef provider required");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("SecretRef id required");
        if (!source.equals("env") && !source.equals("file") && !source.equals("exec")) {
            throw new IllegalArgumentException("SecretRef source must be env/file/exec, got: " + source);
        }
    }

    /** 从字符串解析 SecretRef（格式：source:provider:id） */
    public static SecretRef parse(String ref) {
        if (ref == null || ref.isBlank()) throw new IllegalArgumentException("SecretRef string is empty");
        String[] parts = ref.split(":", 3);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid SecretRef format: " + ref);
        return new SecretRef(parts[0], parts[1], parts[2]);
    }

    /** 转回字符串表示 */
    public String toKey() {
        return source + ":" + provider + ":" + id;
    }

    @Override
    public String toString() { return toKey(); }
}
