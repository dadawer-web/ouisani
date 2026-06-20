package com.ouisani.aios.operator.session;

import java.time.Instant;

/**
 * 会话头 — JSONL 文件首行。
 * <p>
 * 对标 OpenClaw 的 SessionHeader。
 *
 * @param type           固定为 "session"
 * @param version        格式版本（当前 = 3）
 * @param id             会话唯一 ID
 * @param timestamp      创建时间 ISO 8601
 * @param cwd            工作目录
 * @param parentSession  父会话文件路径（fork 时设置）
 */
public record SessionHeader(
        String type,
        int version,
        String id,
        String timestamp,
        String cwd,
        String parentSession
) {
    public SessionHeader {
        if (type == null) type = "session";
        if (version <= 0) version = 3;
        if (timestamp == null) timestamp = Instant.now().toString();
    }

    public SessionHeader(String id, String cwd) {
        this("session", 3, id, Instant.now().toString(), cwd, null);
    }

    public SessionHeader(String id, String cwd, String parentSession) {
        this("session", 3, id, Instant.now().toString(), cwd, parentSession);
    }
}
