package com.ouisani.aios.core.overnight;

import java.time.Instant;

/**
 * Overnight 阶段机 — 长跑会话的生命周期阶段。
 * <p>
 * 镜像 jcode prompts.rs:7 overnight_phase() 函数。阶段不是存储的状态，
 * 而是根据 manifest 的时间锚点 + 当前时间实时计算的纯函数。
 * <p>
 * 五个运行期阶段（对应"调度期→收尾期→汇报期→宽限期→终结"）：
 * <ol>
 *   <li>{@link #RUNNING} — 调度期：now 早于 handoffReadyAt，全力推进验证性进展</li>
 *   <li>{@link #WIND_DOWN} — 收尾期：handoffReadyAt ≤ now 早于 targetWakeAt，停止开新大活</li>
 *   <li>{@link #MORNING_REPORT} — 汇报期：已到 targetWakeAt 但晨报未发，必须发晨报</li>
 *   <li>{@link #POST_WAKE} — 宽限期：晨报已发且 now 早于 postWakeGraceUntil，仅做有界收尾</li>
 *   <li>{@link #FINALIZING} — 终结期：宽限期已过，立即收尾停止</li>
 * </ol>
 * 三个终态：{@link #COMPLETED}、{@link #FAILED}、{@link #CANCELLING}。
 *
 * @see OvernightManifest
 */
public enum OvernightPhase {
    /** 调度期 — 全力推进已验证低风险进展 */
    RUNNING("running", "调度期"),
    /** 收尾期 — 停止开新大活，让 run 易于理解 */
    WIND_DOWN("wind-down", "收尾期"),
    /** 汇报期 — 已到目标唤醒时间，必须发晨报 */
    MORNING_REPORT("morning report", "汇报期"),
    /** 宽限期 — 晨报已发，仅做有界安全收尾 */
    POST_WAKE("post-wake", "宽限期"),
    /** 终结期 — 宽限期已过，立即停止新工作 */
    FINALIZING("finalizing", "终结"),
    /** 已完成 */
    COMPLETED("completed", "已完成"),
    /** 已失败 */
    FAILED("failed", "已失败"),
    /** 取消中 */
    CANCELLING("cancelling", "取消中");

    private final String code;
    private final String cnLabel;

    OvernightPhase(String code, String cnLabel) {
        this.code = code;
        this.cnLabel = cnLabel;
    }

    /** 英文 code（镜像 jcode 的字符串标签） */
    public String code() {
        return code;
    }

    /** 中文标签 */
    public String cnLabel() {
        return cnLabel;
    }

    /** 是否为运行期阶段（非终态、非取消） */
    public boolean isActive() {
        return this == RUNNING || this == WIND_DOWN || this == MORNING_REPORT
                || this == POST_WAKE || this == FINALIZING;
    }

    /** 是否为终态 */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }

    /**
     * 纯函数：根据 manifest 的时间锚点和当前时间计算当前阶段。
     * <p>
     * 镜像 jcode overnight_phase(manifest, now)：
     * <pre>
     *   match manifest.status {
     *     Completed    => "completed"
     *     Failed       => "failed"
     *     CancelRequested => "cancelling"
     *     Running =>
     *       if now < handoffReadyAt        => "running"
     *       else if now < targetWakeAt     => "wind-down"
     *       else if morningReportPostedAt is None => "morning report"
     *       else if now < postWakeGraceUntil      => "post-wake"
     *       else                                  => "finalizing"
     *   }
     * </pre>
     *
     * @param manifest 运行清单
     * @param now      当前时间
     * @return 当前阶段
     */
    public static OvernightPhase compute(OvernightManifest manifest, Instant now) {
        return switch (manifest.status()) {
            case COMPLETED -> COMPLETED;
            case FAILED -> FAILED;
            case CANCEL_REQUESTED -> CANCELLING;
            case RUNNING -> {
                if (now.isBefore(manifest.handoffReadyAt())) {
                    yield RUNNING;
                } else if (now.isBefore(manifest.targetWakeAt())) {
                    yield WIND_DOWN;
                } else if (manifest.morningReportPostedAt() == null) {
                    yield MORNING_REPORT;
                } else if (now.isBefore(manifest.postWakeGraceUntil())) {
                    yield POST_WAKE;
                } else {
                    yield FINALIZING;
                }
            }
        };
    }

    /**
     * 格式化分钟数为可读时长（镜像 jcode format_minutes）。
     * <ul>
     *   <li>{@code < 60} → "Xm"</li>
     *   <li>{@code < 1440} → "Xh Ym"</li>
     *   <li>否则 → "Xd Yh"</li>
     * </ul>
     *
     * @param minutes 分钟数（非负）
     * @return 可读时长
     */
    public static String formatMinutes(long minutes) {
        if (minutes < 0) minutes = 0;
        if (minutes < 60) {
            return minutes + "m";
        }
        long hours = minutes / 60;
        long mins = minutes % 60;
        if (hours < 24) {
            return mins == 0 ? hours + "h" : hours + "h " + mins + "m";
        }
        long days = hours / 24;
        long hrs = hours % 24;
        return hrs == 0 ? days + "d" : days + "d " + hrs + "h";
    }

    @Override
    public String toString() {
        return code + "（" + cnLabel + "）";
    }
}
