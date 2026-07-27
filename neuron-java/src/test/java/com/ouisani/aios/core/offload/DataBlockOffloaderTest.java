package com.ouisani.aios.core.offload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link DataBlockOffloader} 单元测试 — 覆盖 base64 DataBlock 提取/内联、
 * 阈值判断、内容寻址去重、best-effort 容错。
 */
class DataBlockOffloaderTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private DataBlockOffloader offloader;

    @BeforeEach
    void setUp() {
        dataDir = tempDir.resolve("data");
        // 阈值设为 32 字节，方便测试小 payload
        offloader = new DataBlockOffloader(dataDir, 32);
    }

    // ════════════════════════════════════════════════════════════════
    //  offload
    // ════════════════════════════════════════════════════════════════

    @Test
    void offloadReturnsOriginalForNull() {
        assertNull(offloader.offload(null));
        assertEquals("", offloader.offload(""));
    }

    @Test
    void offloadReturnsOriginalWhenNoDataBlock() {
        String content = "hello world, no data blocks here";
        assertEquals(content, offloader.offload(content));
    }

    @Test
    void offloadKeepsSmallBlockInlineBelowThreshold() throws Exception {
        // 16 字节 payload < 32 字节阈值 → 保持内联
        String payload = Base64.getEncoder().encodeToString(new byte[16]);
        String content = "data:image/png;base64," + payload;
        String result = offloader.offload(content);
        assertEquals(content, result, "小 payload 应保持内联");
        // data 目录应为空（或不存在）
        assertTrue(Files.notExists(dataDir) || countFiles(dataDir) == 0);
    }

    @Test
    void offloadExtractsLargeBlockToFileUrl() throws Exception {
        // 64 字节 payload > 32 字节阈值 → offload
        byte[] binary = new byte[64];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) i;
        String payload = Base64.getEncoder().encodeToString(binary);
        String content = "before data:image/png;base64," + payload + " after";

        String result = offloader.offload(content);

        assertNotEquals(content, result, "大 payload 应被 offload");
        assertTrue(result.startsWith("before file://"), "应改写为 file:// URL");
        assertTrue(result.endsWith(" after"), "尾部上下文应保留");

        // blob 文件应存在
        try (var stream = Files.list(dataDir)) {
            long count = stream.count();
            assertEquals(1, count, "应创建 1 个 blob 文件");
        }
    }

    @Test
    void offloadDeduplicatesIdenticalContent() throws Exception {
        // 相同 base64 payload 两次 offload → 同一个 blob 文件（内容寻址去重）
        byte[] binary = new byte[64];
        String payload = Base64.getEncoder().encodeToString(binary);
        String content1 = "data:image/png;base64," + payload;
        String content2 = "other text data:image/jpeg;base64," + payload + " end";

        String r1 = offloader.offload(content1);
        String r2 = offloader.offload(content2);

        // 两个 file:// URL 应指向同一文件
        String url1 = r1;
        String url2 = r2.replace("other text ", "").replace(" end", "");
        assertEquals(url1, url2, "相同内容应去重到同一 file:// URL");

        try (var stream = Files.list(dataDir)) {
            assertEquals(1, stream.count(), "去重后应只有 1 个 blob 文件");
        }
    }

    @Test
    void offloadHandlesMultipleBlocksInOneString() throws Exception {
        byte[] b1 = new byte[64];
        byte[] b2 = new byte[128];
        for (int i = 0; i < b2.length; i++) b2[i] = (byte) (i + 1);
        String p1 = Base64.getEncoder().encodeToString(b1);
        String p2 = Base64.getEncoder().encodeToString(b2);

        String content = "a data:image/png;base64," + p1 + " b data:audio/wav;base64," + p2 + " c";
        String result = offloader.offload(content);

        assertTrue(result.contains("file://"), "应含 file:// URL");
        assertEquals(2, countOccurrences(result, "file://"),
                "应 offload 两个 DataBlock");

        try (var stream = Files.list(dataDir)) {
            assertEquals(2, stream.count(), "应创建 2 个 blob 文件");
        }
    }

    @Test
    void offloadLeavesInvalidBase64Inline() {
        // 非法 base64 → 保持原样
        String content = "data:image/png;base64,!!!not-base64!!!";
        String result = offloader.offload(content);
        assertEquals(content, result, "非法 base64 应保持原样");
    }

    @Test
    void offloadDoesNotTouchTextDataUrl() {
        // data URL 不带 ;base64 的应不被匹配
        String content = "data:text/plain,hello-world";
        String result = offloader.offload(content);
        assertEquals(content, result);
    }

    // ════════════════════════════════════════════════════════════════
    //  inline
    // ════════════════════════════════════════════════════════════════

    @Test
    void inlineReturnsOriginalForNull() {
        assertNull(offloader.inline(null));
        assertEquals("", offloader.inline(""));
    }

    @Test
    void inlineReturnsOriginalWhenNoFileUrl() {
        String content = "no file urls here";
        assertEquals(content, offloader.inline(content));
    }

    @Test
    void inlineRoundTripsOffloadedContent() {
        byte[] binary = new byte[64];
        for (int i = 0; i < binary.length; i++) binary[i] = (byte) (i * 2);
        String payload = Base64.getEncoder().encodeToString(binary);
        String original = "ctx data:image/png;base64," + payload + " end";

        String offloaded = offloader.offload(original);
        String restored = offloader.inline(offloaded);

        // 还原后 mime 统一为 application/octet-stream（offload 时不持久化 mime）
        assertTrue(restored.contains("data:application/octet-stream;base64," + payload),
                "应还原为 base64 DataBlock（mime 统一为 octet-stream）。实际: " + restored);
        assertTrue(restored.startsWith("ctx "), "上下文应保留");
        assertTrue(restored.endsWith(" end"), "上下文应保留");
    }

    @Test
    void inlineKeepsUrlWhenFileMissing() {
        String content = "see file:///nonexistent/path/blob.bin here";
        String result = offloader.inline(content);
        // 文件不存在 → 保留 URL 原样
        assertEquals(content, result);
    }

    @Test
    void inlineHandlesMultipleUrls() {
        byte[] b1 = new byte[64];
        byte[] b2 = new byte[64];
        for (int i = 0; i < b2.length; i++) b2[i] = (byte) 99;
        String p1 = Base64.getEncoder().encodeToString(b1);
        String p2 = Base64.getEncoder().encodeToString(b2);

        String original = "a data:image/png;base64," + p1 + " b data:image/png;base64," + p2;
        String offloaded = offloader.offload(original);
        String restored = offloader.inline(offloaded);

        assertEquals(2, countOccurrences(restored, "data:application/octet-stream;base64,"),
                "应还原两个 DataBlock");
    }

    // ════════════════════════════════════════════════════════════════
    //  访问器
    // ════════════════════════════════════════════════════════════════

    @Test
    void accessorsReturnConfiguredValues() {
        assertEquals(dataDir, offloader.dataDir());
        assertEquals(32, offloader.thresholdBytes());
    }

    @Test
    void defaultConstructorUsesDefaultThreshold() {
        DataBlockOffloader o = new DataBlockOffloader(dataDir);
        assertEquals(DataBlockOffloader.DEFAULT_THRESHOLD_BYTES, o.thresholdBytes());
    }

    // ════════════════════════════════════════════════════════════════
    //  helpers
    // ════════════════════════════════════════════════════════════════

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static long countFiles(Path dir) throws Exception {
        try (var stream = Files.list(dir)) {
            return stream.count();
        }
    }
}
