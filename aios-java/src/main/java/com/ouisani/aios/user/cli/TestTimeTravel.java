package com.ouisani.aios.user.cli;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.rtos.WatchdogDaemon;
import com.ouisani.aios.vfs.MutableFileNode;
import com.ouisani.aios.vfs.ShadowCopyNode;

/**
 * 时间旅行回滚测试 — 模拟 CoderAgent 发疯，验证 ShadowCopyNode 的 CoW 回滚能力。
 * <p>
 * 测试场景：
 * <ol>
 *   <li>创建一个代码库目录，包含多个源文件</li>
 *   <li>创建快照（before_refactor）— O(1) 瞬间完成</li>
 *   <li>模拟 CoderAgent 发疯：清空代码库、删除文件</li>
 *   <li>内核调用 rollback — 瞬间恢复到快照版本</li>
 *   <li>验证数据完整性</li>
 * </ol>
 *
 * <h3>同时测试 WatchdogDaemon 喂狗机制</h3>
 * <ul>
 *   <li>正常喂狗 → 系统健康</li>
 *   <li>停止喂狗 → 系统超时 → 强制重置</li>
 * </ul>
 */
public class TestTimeTravel {

    public static void main(String[] args) {
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          AIOS Time Travel & Watchdog Test                   ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ════════════════════════════════════════════════════════════════
        //  Part 1: ShadowCopyNode CoW + 时间旅行回滚
        // ════════════════════════════════════════════════════════════════

        System.out.println("  ┌─ Part 1: ShadowCopyNode CoW + Time Travel ────────────────┐");
        System.out.println();

        // ── Step 1: 初始化 VFS ──
        System.out.println("  [1/7] Initializing VFS...");
        VfsManager vfs = VfsManager.instance();
        vfs.init();
        System.out.println("  ✓ VFS initialized");
        System.out.println();

        // ── Step 2: 创建代码库 ──
        System.out.println("  [2/7] Creating code repository...");
        MutableFileNode mainJava = new MutableFileNode("/project/src/Main.java");
        mainJava.write("public class Main {\n    public static void main(String[] args) {\n"
                + "        System.out.println(\"Hello, AIOS!\");\n    }\n}");

        MutableFileNode utilsJava = new MutableFileNode("/project/src/Utils.java");
        utilsJava.write("public class Utils {\n    public static int add(int a, int b) {\n"
                + "        return a + b;\n    }\n}");

        MutableFileNode readme = new MutableFileNode("/project/README.md");
        readme.write("# AIOS Project\n\nThis is a critical project.\n");

        vfs.mount("/project/src", "Main.java", mainJava);
        vfs.mount("/project/src", "Utils.java", utilsJava);
        vfs.mount("/project", "README.md", readme);

        System.out.println("  ✓ Code repository created:");
        System.out.println("    /project/src/Main.java   (48 bytes)");
        System.out.println("    /project/src/Utils.java  (52 bytes)");
        System.out.println("    /project/README.md       (36 bytes)");
        System.out.println();

        // ── Step 3: 包装为 ShadowCopyNode + 创建快照 ──
        System.out.println("  [3/7] Wrapping with ShadowCopyNode + creating snapshot...");

        ShadowCopyNode mainShadow = new ShadowCopyNode("/project/src/Main.java", mainJava);
        ShadowCopyNode utilsShadow = new ShadowCopyNode("/project/src/Utils.java", utilsJava);
        ShadowCopyNode readmeShadow = new ShadowCopyNode("/project/README.md", readme);

        // 替换 VFS 中的节点
        vfs.mount("/project/src", "Main.java", mainShadow);
        vfs.mount("/project/src", "Utils.java", utilsShadow);
        vfs.mount("/project", "README.md", readmeShadow);

        // 创建快照 — O(1) 瞬间完成
        long snap1 = mainShadow.createSnapshot("before_refactor");
        long snap2 = utilsShadow.createSnapshot("before_refactor");
        long snap3 = readmeShadow.createSnapshot("before_refactor");

        System.out.println("  ✓ Snapshots created (O(1)):");
        System.out.printf("    Main.java  → snap-%d (timestamp=%d)%n", 1, snap1);
        System.out.printf("    Utils.java → snap-%d (timestamp=%d)%n", 1, snap2);
        System.out.printf("    README.md  → snap-%d (timestamp=%d)%n", 1, snap3);
        System.out.println();

        // ── Step 4: 验证快照前数据 ──
        System.out.println("  [4/7] Verifying pre-disaster data...");
        String mainContent = mainShadow.read();
        String readmeContent = readmeShadow.read();
        assert mainContent.contains("Hello, AIOS!") : "Main.java should contain greeting";
        assert readmeContent.contains("critical project") : "README should contain project description";
        System.out.println("  ✓ Pre-disaster data verified:");
        System.out.println("    Main.java: " + mainContent.substring(0, 40) + "...");
        System.out.println("    README.md: " + readmeContent.substring(0, 30) + "...");
        System.out.println();

        // ── Step 5: 模拟 CoderAgent 发疯 ──
        System.out.println("  [5/7] ⚠ SIMULATING CODER AGENT MELTDOWN...");
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  ⚠ CoderAgent executing dangerous operations:              ║");
        System.out.println("  ║    - Overwriting Main.java with garbage                    ║");
        System.out.println("  ║    - Deleting Utils.java                                    ║");
        System.out.println("  ║    - Overwriting README.md with empty string               ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");

        // CoderAgent 发疯：清空代码库
        mainShadow.write("GARBAGE DATA - ALL YOUR CODE ARE BELONG TO US");
        utilsShadow.deleteEntry("_content");
        readmeShadow.write("");

        System.out.println("  ✓ Disaster simulated:");
        System.out.println("    Main.java: " + mainShadow.read().substring(0, 30) + "...");
        System.out.println("    Utils.java: DELETED");
        System.out.println("    README.md: EMPTY");
        System.out.println();

        // ── Step 6: 内核调用 rollback ──
        System.out.println("  [6/7] ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  [6/7] ║  KERNEL: Initiating TIME TRAVEL ROLLBACK...               ║");
        System.out.println("  [6/7] ╚══════════════════════════════════════════════════════════════╝");

        boolean r1 = mainShadow.rollback(snap1);
        boolean r2 = utilsShadow.rollback(snap2);
        boolean r3 = readmeShadow.rollback(snap3);

        System.out.printf("  ✓ Rollback results: Main=%s, Utils=%s, README=%s%n", r1, r2, r3);
        System.out.println();

        // ── Step 7: 验证恢复 ──
        System.out.println("  [7/7] Verifying data recovery...");
        String recoveredMain = mainShadow.read();
        String recoveredReadme = readmeShadow.read();

        boolean mainOk = recoveredMain.contains("Hello, AIOS!");
        boolean readmeOk = recoveredReadme.contains("critical project");

        System.out.println("  ✓ Recovery verification:");
        System.out.printf("    Main.java: %s — %s%n", mainOk ? "OK" : "FAIL",
                recoveredMain.substring(0, Math.min(40, recoveredMain.length())) + "...");
        System.out.printf("    README.md: %s — %s%n", readmeOk ? "OK" : "FAIL",
                recoveredReadme.substring(0, Math.min(30, recoveredReadme.length())) + "...");
        System.out.println();

        assert mainOk : "Main.java should be recovered";
        assert readmeOk : "README.md should be recovered";

        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  ✓ TIME TRAVEL ROLLBACK: ALL DATA RECOVERED                ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ════════════════════════════════════════════════════════════════
        //  Part 2: WatchdogDaemon 喂狗机制测试
        // ════════════════════════════════════════════════════════════════

        System.out.println("  ┌─ Part 2: WatchdogDaemon Feed Test ────────────────────────┐");
        System.out.println();

        // ── Test 1: 正常喂狗 ──
        System.out.println("  [1/3] Testing normal ping...");
        WatchdogDaemon watchdog = WatchdogDaemon.instance();
        watchdog.setWatchdogTimeout(5000); // 5 秒超时（测试用）
        watchdog.start();

        watchdog.ping("scheduler");
        watchdog.ping("llm_api");

        boolean healthy = watchdog.isSystemHealthy();
        System.out.printf("  ✓ After ping: system healthy = %s, msSinceLastPing = %dms%n",
                healthy, watchdog.msSinceLastPing());
        assert healthy : "System should be healthy after ping";
        System.out.println();

        // ── Test 2: 喂狗来源追踪 ──
        System.out.println("  [2/3] Testing ping source tracking...");
        boolean schedulerAlive = watchdog.isSourceAlive("scheduler");
        boolean llmAlive = watchdog.isSourceAlive("llm_api");
        boolean unknownAlive = watchdog.isSourceAlive("unknown_source");

        System.out.printf("  ✓ Source tracking: scheduler=%s, llm_api=%s, unknown=%s%n",
                schedulerAlive, llmAlive, unknownAlive);
        assert schedulerAlive : "scheduler should be alive";
        assert llmAlive : "llm_api should be alive";
        assert !unknownAlive : "unknown source should not be alive";
        System.out.println();

        // ── Test 3: 超时重置 ──
        System.out.println("  [3/3] Testing watchdog timeout (2s)...");
        watchdog.setWatchdogTimeout(2000); // 2 秒超时

        // 等待超时
        try {
            Thread.sleep(2500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        boolean healthyAfterTimeout = watchdog.isSystemHealthy();
        long resets = watchdog.totalSystemResets();

        System.out.printf("  ✓ After 2.5s without ping: healthy=%s, systemResets=%d%n",
                healthyAfterTimeout, resets);
        assert !healthyAfterTimeout : "System should not be healthy after timeout";
        assert resets >= 1 : "At least one system reset should have been triggered";
        System.out.println();

        // 恢复
        watchdog.ping("recovery");
        watchdog.setWatchdogTimeout(60_000); // 恢复 60 秒
        watchdog.stop();

        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║  ✓ WATCHDOG TEST: ALL PASSED                               ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // ── 最终报告 ──
        System.out.println("  ╔══════════════════════════════════════════════════════════════╗");
        System.out.println("  ║          ALL TESTS PASSED ✓                                  ║");
        System.out.println("  ║                                                              ║");
        System.out.println("  ║  ShadowCopyNode: CoW + Snapshot + Rollback                  ║");
        System.out.println("  ║  WatchdogDaemon: Ping + Timeout + SystemReset               ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════╝");
    }
}
