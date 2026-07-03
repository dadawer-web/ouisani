package com.ouisani.aios.drivers.llm;

import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LocalOnnxEmbedder 集成测试。
 * <p>
 * 需要真实 all-MiniLM-L6-v2 模型（model.onnx + tokenizer.json）。
 * 模型缺失时通过 {@link Assumptions#assumeTrue} 自动 skip（不阻塞 CI）。
 * 设置 {@code AIOS_MODELS_DIR} 环境变量或默认路径放置模型后可启用。
 */
class LocalOnnxEmbedderTest {

    private static Path modelDir() {
        String env = System.getenv("AIOS_MODELS_DIR");
        String dir = (env != null && !env.isEmpty())
                ? env
                : com.ouisani.aios.core.config.AiosPaths.modelsDir();
        return Path.of(dir);
    }

    private static boolean modelPresent() {
        Path d = modelDir();
        return Files.exists(d.resolve("model.onnx"))
                && Files.exists(d.resolve("tokenizer.json"));
    }

    @Test
    void is_available_false_when_model_missing() {
        // 不存在的目录 → isAvailable 应返回 false（不抛）
        LocalOnnxEmbedder embedder = new LocalOnnxEmbedder(Path.of("/nonexistent/model/dir"));
        assertFalse(embedder.isAvailable());
    }

    @Test
    void embed_returns_384_dim_normalized() {
        Assumptions.assumeTrue(modelPresent(), "all-MiniLM-L6-v2 模型未就绪，跳过集成测试");
        LocalOnnxEmbedder embedder = new LocalOnnxEmbedder(modelDir());
        assertTrue(embedder.isAvailable());

        float[] vec = embedder.embed("machine learning embedding test");
        assertNotNull(vec);
        assertEquals(LocalOnnxEmbedder.EMBEDDING_DIM, vec.length);
        // L2 normalized → 范数 ≈ 1.0
        float norm = VectorMath.l2Norm(vec);
        assertEquals(1.0f, norm, 1e-4f);
    }

    @Test
    void cache_hit_returns_same_ref() {
        Assumptions.assumeTrue(modelPresent(), "all-MiniLM-L6-v2 模型未就绪，跳过集成测试");
        LocalOnnxEmbedder embedder = new LocalOnnxEmbedder(modelDir());
        embedder.isAvailable();
        float[] first = embedder.embed("cached text");
        float[] second = embedder.embed("cached text");
        // 命中缓存 → 同一引用
        assertSame(first, second);
    }

    @Test
    void unrelated_texts_differ() {
        Assumptions.assumeTrue(modelPresent(), "all-MiniLM-L6-v2 模型未就绪，跳过集成测试");
        LocalOnnxEmbedder embedder = new LocalOnnxEmbedder(modelDir());
        embedder.isAvailable();
        float[] a = embedder.embed("database transaction");
        float[] b = embedder.embed("neural network training");
        assertEquals(LocalOnnxEmbedder.EMBEDDING_DIM, a.length);
        assertEquals(LocalOnnxEmbedder.EMBEDDING_DIM, b.length);
        assertNotSame(a, b);
        float cos = VectorMath.cosineSimilarity(a, b);
        // 语义无关文本的 cosine 应明显 < 1（非完全相同）
        assertTrue(cos < 0.99f, "unrelated texts should not be near-identical, cos=" + cos);
    }

    @Test
    void think_throws_embed_only() {
        LocalOnnxEmbedder embedder = new LocalOnnxEmbedder(Path.of("/nonexistent"));
        assertThrows(UnsupportedOperationException.class,
                () -> embedder.think("hi", ""));
        assertThrows(UnsupportedOperationException.class,
                () -> embedder.thinkWithHistory(java.util.List.of(LlmProvider.ChatMessage.user("hi")), ""));
    }
}
