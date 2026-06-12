package com.ouisani.aios.openclaw.session;

import java.time.Instant;
import java.util.*;

/**
 * 会话条目 — 对标 OpenClaw 的 SessionEntry 联合类型。
 * <p>
 * 每个条目有 id + parentId 构成树结构，leafId 指向当前位置。
 * 9 种类型通过 {@link Type} 鉴别。
 */
public class SessionEntry {

    public enum Type {
        MESSAGE, THINKING_LEVEL_CHANGE, MODEL_CHANGE, COMPACTION,
        BRANCH_SUMMARY, CUSTOM, CUSTOM_MESSAGE, LABEL, SESSION_INFO
    }

    private final Type type;
    private final String id;
    private final String parentId;
    private final String timestamp;

    // ── MESSAGE ──
    private AgentMessage message;

    // ── THINKING_LEVEL_CHANGE ──
    private String thinkingLevel;

    // ── MODEL_CHANGE ──
    private String provider;
    private String modelId;

    // ── COMPACTION ──
    private String summary;
    private String firstKeptEntryId;
    private long tokensBefore;
    private CompactionDetails details;
    private boolean fromHook;

    // ── BRANCH_SUMMARY ──
    private String fromId;

    // ── CUSTOM / CUSTOM_MESSAGE ──
    private String customType;
    private Object data;
    private String content;
    private boolean display;

    // ── LABEL ──
    private String targetId;
    private String label;

    // ── SESSION_INFO ──
    private String sessionName;

    // ── 工厂方法 ──

    public static SessionEntry message(String id, String parentId, AgentMessage message) {
        SessionEntry e = new SessionEntry(Type.MESSAGE, id, parentId);
        e.message = message;
        return e;
    }

    public static SessionEntry thinkingLevelChange(String id, String parentId, String level) {
        SessionEntry e = new SessionEntry(Type.THINKING_LEVEL_CHANGE, id, parentId);
        e.thinkingLevel = level;
        return e;
    }

    public static SessionEntry modelChange(String id, String parentId, String provider, String modelId) {
        SessionEntry e = new SessionEntry(Type.MODEL_CHANGE, id, parentId);
        e.provider = provider;
        e.modelId = modelId;
        return e;
    }

    public static SessionEntry compaction(String id, String parentId, String summary,
                                          String firstKeptEntryId, long tokensBefore,
                                          CompactionDetails details, boolean fromHook) {
        SessionEntry e = new SessionEntry(Type.COMPACTION, id, parentId);
        e.summary = summary;
        e.firstKeptEntryId = firstKeptEntryId;
        e.tokensBefore = tokensBefore;
        e.details = details;
        e.fromHook = fromHook;
        return e;
    }

    public static SessionEntry branchSummary(String id, String parentId, String fromId,
                                              String summary, CompactionDetails details, boolean fromHook) {
        SessionEntry e = new SessionEntry(Type.BRANCH_SUMMARY, id, parentId);
        e.fromId = fromId;
        e.summary = summary;
        e.details = details;
        e.fromHook = fromHook;
        return e;
    }

    public static SessionEntry custom(String id, String parentId, String customType, Object data) {
        SessionEntry e = new SessionEntry(Type.CUSTOM, id, parentId);
        e.customType = customType;
        e.data = data;
        return e;
    }

    public static SessionEntry customMessage(String id, String parentId, String customType,
                                              String content, boolean display, Object data) {
        SessionEntry e = new SessionEntry(Type.CUSTOM_MESSAGE, id, parentId);
        e.customType = customType;
        e.content = content;
        e.display = display;
        e.data = data;
        return e;
    }

    public static SessionEntry label(String id, String parentId, String targetId, String label) {
        SessionEntry e = new SessionEntry(Type.LABEL, id, parentId);
        e.targetId = targetId;
        e.label = label;
        return e;
    }

    public static SessionEntry sessionInfo(String id, String parentId, String name) {
        SessionEntry e = new SessionEntry(Type.SESSION_INFO, id, parentId);
        e.sessionName = name;
        return e;
    }

    // ── 构造器 ──

    private SessionEntry(Type type, String id, String parentId) {
        this.type = type;
        this.id = id;
        this.parentId = parentId;
        this.timestamp = Instant.now().toString();
    }

    // ── Getters ──

    public Type type() { return type; }
    public String id() { return id; }
    public String parentId() { return parentId; }
    public String timestamp() { return timestamp; }
    public AgentMessage message() { return message; }
    public String thinkingLevel() { return thinkingLevel; }
    public String provider() { return provider; }
    public String modelId() { return modelId; }
    public String summary() { return summary; }
    public String firstKeptEntryId() { return firstKeptEntryId; }
    public long tokensBefore() { return tokensBefore; }
    public CompactionDetails details() { return details; }
    public boolean fromHook() { return fromHook; }
    public String fromId() { return fromId; }
    public String customType() { return customType; }
    public Object data() { return data; }
    public String content() { return content; }
    public boolean display() { return display; }
    public String targetId() { return targetId; }
    public String label() { return label; }
    public String sessionName() { return sessionName; }

    /**
     * 此条目是否参与 LLM 上下文构建。
     */
    public boolean participatesInContext() {
        return type == Type.MESSAGE || type == Type.COMPACTION
                || type == Type.BRANCH_SUMMARY || type == Type.CUSTOM_MESSAGE;
    }

    @Override
    public String toString() {
        return "SessionEntry{" + type + " id=" + id + "}";
    }
}
