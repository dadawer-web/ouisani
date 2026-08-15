package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RemoteResult} 单元测试 — 4 个工厂方法 + compact constructor 的 null 归一化。
 */
class RemoteResultTest {

    @Test
    @DisplayName("success(stdout, dur) → exitCode=0, success=true, errorMessage='', stdout 透传")
    void success_factory_setsFieldsCorrectly() {
        RemoteResult r = RemoteResult.success("training complete\n", 100L);

        assertEquals(0, r.exitCode());
        assertTrue(r.success());
        assertEquals("", r.errorMessage());
        assertEquals("training complete\n", r.stdout());
        assertEquals("", r.stderr());
        assertEquals(100L, r.durationMs());
    }

    @Test
    @DisplayName("failure(exitCode, out, err, dur) → success=false, errorMessage 含退出码和 stderr")
    void failure_factory_setsFieldsAndErrorMessage() {
        RemoteResult r = RemoteResult.failure(2, "partial out", "err msg", 50L);

        assertEquals(2, r.exitCode());
        assertFalse(r.success());
        assertEquals("partial out", r.stdout());
        assertEquals("err msg", r.stderr());
        assertEquals(50L, r.durationMs());
        assertTrue(r.errorMessage().contains("2"), "errorMessage should contain exit code: " + r.errorMessage());
        assertTrue(r.errorMessage().contains("err msg"), "errorMessage should contain stderr: " + r.errorMessage());
    }

    @Test
    @DisplayName("timeout(dur) → exitCode=-1, success=false, errorMessage 含 'timed out' 和耗时")
    void timeout_factory_setsExitCodeMinusOne() {
        RemoteResult r = RemoteResult.timeout(999L);

        assertEquals(-1, r.exitCode());
        assertFalse(r.success());
        assertEquals("", r.stdout());
        assertEquals("", r.stderr());
        assertEquals(999L, r.durationMs());
        assertTrue(r.errorMessage().contains("timed out"), "errorMessage: " + r.errorMessage());
        assertTrue(r.errorMessage().contains("999"), "errorMessage should contain duration: " + r.errorMessage());
    }

    @Test
    @DisplayName("configError(msg) → exitCode=-1, success=false, errorMessage 含 'config error' 和原始 msg")
    void configError_factory_setsConfigErrorMessage() {
        RemoteResult r = RemoteResult.configError("bad cfg");

        assertEquals(-1, r.exitCode());
        assertFalse(r.success());
        assertEquals("", r.stdout());
        assertEquals("", r.stderr());
        assertEquals(0L, r.durationMs());
        assertTrue(r.errorMessage().contains("config error"), "errorMessage: " + r.errorMessage());
        assertTrue(r.errorMessage().contains("bad cfg"), "errorMessage: " + r.errorMessage());
    }

    @Test
    @DisplayName("compact constructor 把 null stdout/stderr/errorMessage 归一化为空串")
    void compactConstructor_nullNormalizesToEmpty() {
        RemoteResult r = new RemoteResult(0, null, null, 0L, true, null);

        assertEquals("", r.stdout(), "null stdout → ''");
        assertEquals("", r.stderr(), "null stderr → ''");
        assertEquals("", r.errorMessage(), "null errorMessage → ''");
        assertTrue(r.success());
    }
}
