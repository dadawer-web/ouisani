package com.ouisani.aios.core.pipeline;

/**
 * 内容转换器接口 — 借鉴 Firecrawl 的 Transformer Stack。
 * <p>
 * 每个转换器处理一次内容，可以像 Langflow 的组件一样按需组装。
 * <p>
 * OS 类比：Linux 的管道（pipe）— 每个转换器是一个进程，
 * 前一个的 stdout 是后一个的 stdin。
 */
@FunctionalInterface
public interface ContentTransformer {

    /**
     * 转换内容。
     *
     * @param content 输入内容
     * @param context 转换上下文
     * @return 转换后的内容
     */
    String transform(String content, TransformContext context);

    /**
     * 转换器名称。
     */
    default String name() { return this.getClass().getSimpleName(); }

    /**
     * 是否为必需转换器（失败时中断管道）。
     */
    default boolean required() { return false; }
}
