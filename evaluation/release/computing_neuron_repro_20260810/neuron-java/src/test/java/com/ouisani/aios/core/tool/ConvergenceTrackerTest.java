package com.ouisani.aios.core.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ConvergenceTracker 单元测试 — 验证收敛检测逻辑。
 */
class ConvergenceTrackerTest {

    @Test
    void firstWrite_neverConverged() {
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/main.py", "print('hello')");
        assertFalse(tracker.isConverged(), "首次写入不应触发收敛");
        assertNull(tracker.convergenceReason());
    }

    @Test
    void sameContentTwice_converged() {
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/main.py", "print('hello')");
        assertFalse(tracker.isConverged());
        tracker.recordWrite("/app/main.py", "print('hello')");
        assertTrue(tracker.isConverged(), "连续 2 次相同内容应触发收敛");
        assertNotNull(tracker.convergenceReason());
        assertTrue(tracker.convergenceReason().contains("/app/main.py"));
    }

    @Test
    void differentContent_notConverged() {
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/main.py", "print('v1')");
        tracker.recordWrite("/app/main.py", "print('v2')");
        assertFalse(tracker.isConverged(), "不同内容不应触发收敛");
    }

    @Test
    void differentFiles_notConverged() {
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/a.py", "content");
        tracker.recordWrite("/app/b.py", "content");
        assertFalse(tracker.isConverged(), "不同文件不应触发收敛");
    }

    @Test
    void sameContentAfterDifferent_notConverged() {
        // v1 → v2 → v2 应该收敛（连续 2 次 v2）
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/main.py", "v1");
        assertFalse(tracker.isConverged());
        tracker.recordWrite("/app/main.py", "v2");
        assertFalse(tracker.isConverged(), "v1→v2 不同内容");
        tracker.recordWrite("/app/main.py", "v2");
        assertTrue(tracker.isConverged(), "v2→v2 连续相同应收敛");
    }

    @Test
    void convergedIsPermanent_untilReset() {
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/main.py", "same");
        tracker.recordWrite("/app/main.py", "same");
        assertTrue(tracker.isConverged());
        // 即使后续写不同内容，仍保持 converged（一次性触发）
        tracker.recordWrite("/app/main.py", "different");
        assertTrue(tracker.isConverged(), "收敛触发后应永久标记");
    }

    @Test
    void reset_clearsConvergence() {
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/main.py", "same");
        tracker.recordWrite("/app/main.py", "same");
        assertTrue(tracker.isConverged());
        tracker.reset();
        assertFalse(tracker.isConverged(), "reset 后应清除收敛状态");
        assertNull(tracker.convergenceReason());
    }

    @Test
    void interleaveFiles_trackPerFile() {
        // fileA(x) → fileB(y) → fileA(x) → fileA(x) 应收敛
        // fileA 的两次 x 不连续（fileB 在中间），但 per-file 追踪应忽略 fileB
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/a.py", "x");
        tracker.recordWrite("/app/b.py", "y");
        tracker.recordWrite("/app/a.py", "x");
        assertTrue(tracker.isConverged(), "per-file 追踪：a.py 的连续两次 x 应收敛");
    }

    @Test
    void nullInputs_ignored() {
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite(null, "content");
        tracker.recordWrite("/app/main.py", null);
        assertFalse(tracker.isConverged(), "null 输入应被忽略");
    }

    @Test
    void emptyContent_worksCorrectly() {
        ConvergenceTracker tracker = new ConvergenceTracker();
        tracker.recordWrite("/app/empty.txt", "");
        tracker.recordWrite("/app/empty.txt", "");
        assertTrue(tracker.isConverged(), "空内容连续 2 次也应收敛");
    }
}
