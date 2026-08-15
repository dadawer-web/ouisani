package com.ouisani.aios.core.network;

import java.time.Duration;
import java.util.function.Consumer;

/**
 * 责任链 handler 注册项 — 携带优先级、超时、错误控制策略的 handler 描述。
 * <p>
 * 借鉴 Apix 的 {@code HandlerEntry}，适配 Java record 模型。
 * <p>
 * <b>字段语义</b>：
 * <ul>
 *   <li>{@code priority}：优先级，数值越大越先执行。同优先级按 {@code registerOrder} 先注册先执行</li>
 *   <li>{@code timeout}：单 handler 执行超时，超时后该 handler 被中断但链继续（除非 stopWhenError）</li>
 *   <li>{@code stopWhenError}：handler 抛异常时是否中断后续链</li>
 *   <li>{@code callback}：实际处理函数，接收 {@link ChainEventItem}，可通过 {@code accept()} 中断链</li>
 * </ul>
 *
 * @param priority       优先级（越大越先执行）
 * @param registerOrder  注册顺序（同优先级时先注册先执行）
 * @param timeout        单 handler 超时
 * @param stopWhenError  handler 异常时是否中断链
 * @param callback       处理函数
 * @see ChainEventDispatcher
 */
public record ChainHandlerEntry(
        int priority,
        long registerOrder,
        Duration timeout,
        boolean stopWhenError,
        Consumer<ChainEventItem> callback
) {}
