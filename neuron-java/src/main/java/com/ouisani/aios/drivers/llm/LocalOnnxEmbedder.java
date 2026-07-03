package com.ouisani.aios.drivers.llm;

import ai.djl.ModelException;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.TextEmbeddingTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.TranslateException;
import ai.djl.translate.Translator;
import com.ouisani.aios.core.config.AiosPaths;
import com.ouisani.aios.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 本地 ONNX Embedding Provider — 镜像 jcode-embedding lib.rs:171-244。
 * <p>
 * all-MiniLM-L6-v2 经 DJL（OnnxRuntime 引擎）本地推理，输出 mean-pool + L2 normalize
 * 后的 384 维向量。完全离线、零外部 API，消除对 EMBEDDING_API_KEY（SiliconFlow）的依赖。
 * <p>
 * <b>线程安全</b>：DJL {@link Predictor} 非线程安全 + 项目用虚拟线程 → {@link #embed(String)}
 * 用 {@code synchronized} 串行化推理。LRU 缓存（{@link EmbeddingCache}）在命中时无需加锁，
 * 仅推理路径串行。
 * <p>
 * <b>加载策略</b>：fail-fast。模型文件（model.onnx + tokenizer.json）缺失或加载失败时
 * {@link #isAvailable()} 返回 false，bootstrap 回退 OpenAiAdapter（EMBEDDING_API_KEY）。
 * 不自动下载 90MB 模型——用 {@code AIOS_MODELS_DIR} 环境变量指定已放置模型的目录。
 * <p>
 * <b>chat 路径</b>：本 Provider 仅实现 embed，think*() 抛
 * {@link UnsupportedOperationException}；由 {@link EmbeddingRoutingProvider} 把 think 路由到 chat Provider。
 */
public class LocalOnnxEmbedder implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LocalOnnxEmbedder.class);

    /** all-MiniLM-L6-v2 输出维度 — 与 jcode EMBEDDING_DIM 一致 */
    static final int EMBEDDING_DIM = 384;
    /** 最大序列长度 — 与 jcode MAX_SEQ_LENGTH 一致 */
    static final int MAX_SEQ_LENGTH = 256;

    private final Path modelDir;
    private final EmbeddingCache cache;

    private volatile boolean initialized = false;
    private volatile boolean available = false;
    private ZooModel<String, float[]> model;
    private Predictor<String, float[]> predictor;

    public LocalOnnxEmbedder() {
        this(Path.of(AiosPaths.modelsDir()));
    }

    /** 测试/自定义路径注入构造器。 */
    public LocalOnnxEmbedder(Path modelDir) {
        this.modelDir = modelDir;
        this.cache = new EmbeddingCache();
    }

    @Override
    public String name() {
        return "local_onnx";
    }

    @Override
    public float[] embed(String text) {
        float[] cached = cache.get(text);
        if (cached != null) {
            return cached;
        }
        if (!available) {
            throw new IllegalStateException("LocalOnnxEmbedder 未加载（isAvailable=false）");
        }
        float[] result;
        synchronized (this) {
            try {
                result = predictor.predict(text);
            } catch (TranslateException e) {
                throw new RuntimeException("ONNX embedding 推理失败：" + e.getMessage(), e);
            }
        }
        cache.put(text, result);
        return result;
    }

    @Override
    public String think(String prompt, String systemPrompt) {
        throw new UnsupportedOperationException(
                "LocalOnnxEmbedder is embed-only; use EmbeddingRoutingProvider to route think()");
    }

    @Override
    public String thinkWithHistory(List<ChatMessage> messages, String systemPrompt) {
        throw new UnsupportedOperationException(
                "LocalOnnxEmbedder is embed-only; use EmbeddingRoutingProvider to route think()");
    }

    /**
     * 模型是否可用 — lazy 加载，捕获所有异常返回 false（不阻塞 bootstrap）。
     * 首次调用执行加载；后续调用直接返回缓存结果。
     */
    @Override
    public synchronized boolean isAvailable() {
        if (initialized) {
            return available;
        }
        try {
            loadModel();
        } catch (Exception e) {
            log.warn("[LocalOnnxEmbedder] 模型不可用：{} — embedding 将回退 OpenAI", e.getMessage());
            available = false;
            initialized = true;
        }
        return available;
    }

    /** 加载 ONNX 模型 + tokenizer + 构造 Predictor。fail-fast：文件缺失或加载失败抛异常。 */
    private void loadModel() throws ModelException, IOException {
        if (!Files.exists(modelDir.resolve("model.onnx"))
                || !Files.exists(modelDir.resolve("tokenizer.json"))) {
            throw new IllegalStateException("模型文件缺失：" + modelDir
                    + "（需 model.onnx + tokenizer.json；用 AIOS_MODELS_DIR 指定目录）");
        }
        // tokenizer：pad 到 MAX_SEQ_LENGTH 固定形状 + 截断超长输入
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.builder()
                .optTokenizerPath(modelDir.resolve("tokenizer.json"))
                .optMaxLength(MAX_SEQ_LENGTH)
                .optPadding(true)
                .optPadToMaxLength()
                .optTruncation(true)
                .build();
        // translator：mean-pool（attention_mask 加权）+ L2 normalize — 等价 jcode lib.rs:210-235
        Translator<String, float[]> translator = TextEmbeddingTranslator.builder(tokenizer)
                .optPoolingMode("MEAN")
                .optNormalize(true)
                .build();
        Criteria<String, float[]> criteria = Criteria.<String, float[]>builder()
                .setTypes(String.class, float[].class)
                .optModelPath(modelDir)
                .optEngine("OnnxRuntime")
                .optTranslator(translator)
                .build();
        model = criteria.loadModel();
        predictor = model.newPredictor();
        available = true;
        initialized = true;
        log.info("[LocalOnnxEmbedder] all-MiniLM-L6-v2 已加载（{} 维，{}）", EMBEDDING_DIM, modelDir);
    }
}
