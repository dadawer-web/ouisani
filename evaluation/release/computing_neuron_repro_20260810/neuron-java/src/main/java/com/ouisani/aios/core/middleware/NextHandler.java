package com.ouisani.aios.core.middleware;

/**
 * 洋葱中间件的「下游续延」— 对标 AgentScope 2.0 {@code middleware/_base.py} 的 {@code next_handler}。
 * <p>
 * 中间件在 {@code on_acting} / {@code on_model_call} 等 onion hook 中拿到 {@code next}，
 * 决定是否调下游（调 {@link #proceed()} = 进入内层中间件或 leaf；不调 = 短路）。
 * <p>
 * <b>同步 await 语义</b>（项目记忆约束「所有 middleware 必须用 async/await，无 callback 风格」）：
 * Java 无原生 async/await，但 {@code next.proceed()} 内联调用并直接返回 {@code T} 就是 await——
 * 中间件在调用点「等待」结果。{@code QueryEngine} 跑在虚拟线程上，阻塞 {@code proceed()} 释放载体线程、
 * 成本极低。同步还保证 {@code UpstreamMetaContext} / {@code DelegationGuard} scope 等 ThreadLocal
 * 在洋葱内全程可见——异步会破坏 ThreadLocal 传播。
 * <p>
 * OS 类比：相当于 Linux 内核的「调用下一层驱动」入口 — 中间件可以拦截、包裹、短路，
 * 也可以原样放行（调 {@code next.proceed()} 返回其结果）。
 *
 * @param <T> onion hook 的产出类型（如 {@code ToolOutput} / {@code String}）
 */
@FunctionalInterface
public interface NextHandler<T> {
    /**
     * 调用下游（内层中间件或 leaf）。
     *
     * @return 下游产出
     * @throws Exception leaf（如 {@code tool.call}）的异常向上传播——链构建器不吞 leaf 异常
     */
    T proceed() throws Exception;
}
