package com.ouisani.aios.core.overnight;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;

/**
 * 资源快照 — 长跑期间的系统资源采样。
 * <p>
 * 镜像 jcode 的 ResourceSnapshot（overnight.rs:529 gather_resource_snapshot），
 * 但精简为 JVM 场景所需的 4 个核心指标。操作契约要求 coordinator"感知
 * RAM/load/battery，避免编译/浏览器/索引/全量测试并行"，本快照为该条款
 * 提供数据支撑。
 * <p>
 * 使用 JDK 内置的 {@link com.sun.management.OperatingSystemMXBean}，无新依赖。
 * 电池在服务器场景无意义，跳过。
 *
 * @see OvernightContract
 */
public record OvernightResourceSnapshot(
        Instant capturedAt,
        double memoryUsedPercent,
        double loadOne,
        int cpuCount,
        double diskAvailableGb
) {

    /** 内存使用率健康阈值（超过则不建议并行重活） */
    private static final double MEM_HEALTHY_THRESHOLD = 85.0;

    /** 采集当前系统资源快照 */
    public static OvernightResourceSnapshot capture() {
        try {
            com.sun.management.OperatingSystemMXBean os = (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();

            double memUsedPercent = 0;
            long totalMem = os.getTotalMemorySize();
            long freeMem = os.getFreeMemorySize();
            if (totalMem > 0) {
                memUsedPercent = 100.0 * (totalMem - freeMem) / totalMem;
            }

            double loadOne = os.getSystemLoadAverage();
            if (loadOne < 0) loadOne = 0;

            int cpuCount = os.getAvailableProcessors();

            double diskGb = new File(".").getUsableSpace() / 1_000_000_000.0;

            return new OvernightResourceSnapshot(Instant.now(), memUsedPercent,
                    loadOne, cpuCount, diskGb);
        } catch (Exception e) {
            return new OvernightResourceSnapshot(Instant.now(), 0, 0, 1, 0);
        }
    }

    /**
     * 资源是否健康 — 是否可以安全并行重活（编译/索引/全量测试）。
     * <p>
     * 内存使用率低于 85% 且负载低于 CPU 核心数时为健康。
     */
    public boolean isHealthy() {
        return memoryUsedPercent < MEM_HEALTHY_THRESHOLD && loadOne < cpuCount;
    }

    /** 可读摘要（注入 coordinator prompt） */
    public String summary() {
        return "RAM %.0f%%, load %.1f/%d, disk %.0fGB".formatted(
                memoryUsedPercent, loadOne, cpuCount, diskAvailableGb);
    }

    /** 资源健康建议（注入 prompt 告诉 coordinator 当前能否并行重活） */
    public String advisory() {
        if (isHealthy()) {
            return "资源健康（%s），可执行中等强度任务。".formatted(summary());
        }
        return "资源紧张（%s），避免并行编译/索引/全量测试。".formatted(summary());
    }
}
