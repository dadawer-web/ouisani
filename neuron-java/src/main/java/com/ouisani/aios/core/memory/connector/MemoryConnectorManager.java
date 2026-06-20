package com.ouisani.aios.core.memory.connector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连接器管理器 — 维护适配器注册表，按 URL schema 创建连接器。
 * <p>
 * 参考 LMCache 的 {@code ConnectorManager}。
 * <p>
 * 支持两种注册方式：
 * <ol>
 *   <li><b>手动注册</b>：{@link #register(MemoryConnectorAdapter)}</li>
 *   <li><b>自动发现</b>：通过 PluginDiscovery 扫描（由上层在初始化时调用 register）</li>
 * </ol>
 * <p>
 * {@link #createConnector(String, MemoryConnectorContext)} 遍历适配器列表，
 * 找到第一个 {@link MemoryConnectorAdapter#canParse(String)} 返回 {@code true}
 * 的适配器，调用其 {@code createConnector}，然后用
 * {@link InstrumentedMemoryConnector} 包装返回。找不到时抛出
 * {@link IllegalArgumentException}。
 * <p>
 * 该类是线程安全的（使用 {@link CopyOnWriteArrayList}）。
 */
public final class MemoryConnectorManager {

    private static final Logger log = LoggerFactory.getLogger(MemoryConnectorManager.class);

    private final List<MemoryConnectorAdapter> adapters = new CopyOnWriteArrayList<>();

    /**
     * 注册一个适配器。
     *
     * @param adapter 适配器实例，不能为 {@code null}
     * @throws IllegalArgumentException adapter 为 {@code null} 时抛出
     */
    public void register(MemoryConnectorAdapter adapter) {
        if (adapter == null) {
            throw new IllegalArgumentException("adapter must not be null");
        }
        adapters.add(adapter);
        log.info("[MemoryConnectorManager] 已注册适配器: schema='{}', class={}",
                adapter.schema(), adapter.getClass().getSimpleName());
    }

    /**
     * 按 URL 创建连接器，返回 {@link InstrumentedMemoryConnector} 包装。
     * <p>
     * 遍历已注册适配器，首个能解析该 URL 的适配器负责创建连接器，
     * 随后用指标装饰器包装。
     *
     * @param url     连接 URL
     * @param context 创建上下文
     * @return 带指标采集的连接器
     * @throws IllegalArgumentException 找不到匹配的适配器时抛出
     */
    public MemoryConnector createConnector(String url, MemoryConnectorContext context) {
        for (MemoryConnectorAdapter adapter : adapters) {
            if (adapter.canParse(url)) {
                log.info("[MemoryConnectorManager] 匹配适配器 schema='{}' 处理 URL: {}",
                        adapter.schema(), url);
                MemoryConnector connector = adapter.createConnector(context);
                return new InstrumentedMemoryConnector(connector);
            }
        }
        throw new IllegalArgumentException("No adapter found for URL: " + url);
    }

    /**
     * 获取已注册的适配器列表（只读快照视图）。
     *
     * @return 不可变的适配器列表
     */
    public List<MemoryConnectorAdapter> registeredAdapters() {
        return List.copyOf(adapters);
    }
}
