package com.ouisani.aios.core.vfs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * VFS 路径锁管理器 — 树状细粒度文件锁，防止多 Agent 并发文件访问冲突。
 * <p>
 * 借鉴 Apix 的 {@code file_system_manager.py} 设计，并适配 Java 21 虚拟线程模型。
 * <p>
 * <b>核心规则（树状冲突检测）：</b>
 * <ul>
 *   <li><b>祖先破坏性锁</b>：若任何祖先路径被 {@code DELETE}/{@code MOVE} 锁定，
 *       则当前路径无法获取新锁（祖先正在被删除/移动，子节点操作无意义）</li>
 *   <li><b>后代锁</b>：对于 {@code DELETE}/{@code MOVE} 操作，
 *       需等待所有后代路径解锁（不能删除正在被使用的目录）</li>
 *   <li><b>精确路径锁</b>：其他操作只需等待精确路径解锁</li>
 * </ul>
 * <p>
 * <b>多文件锁避免死锁</b>：{@link #multiFileLock} 对所有路径排序后按序获取，逆序释放，
 * 避免经典的 AB-BA 死锁。
 * <p>
 * <b>OS 类比</b>：相当于 Linux 的 {@code flock()} + {@code fcntl(F_SETLK)} 的增强版，
 * 增加了基于路径树拓扑的冲突检测。
 *
 * @see VfsLockManager.FileLockRecord
 * @see VfsLockManager.LockEvent
 */
public final class VfsLockManager {

    private static final Logger log = LoggerFactory.getLogger(VfsLockManager.class);

    private static final class Holder {
        static final VfsLockManager INSTANCE = new VfsLockManager();
    }

    public static VfsLockManager instance() {
        return Holder.INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  锁事件类型
    // ════════════════════════════════════════════════════════════════

    /**
     * 锁事件类型 — 描述持锁者正在执行的操作语义。
     * <p>
     * {@code DELETE} 和 {@code MOVE} 是<b>破坏性操作</b>，
     * 会阻塞所有后代路径的任何操作。
     */
    public enum LockEvent {
        /** 创建文件/目录 */
        CREATE,
        /** 删除文件/目录（破坏性） */
        DELETE,
        /** 修改文件内容 */
        MODIFY,
        /** 读取文件（共享） */
        READ,
        /** 移动/重命名（破坏性） */
        MOVE;

        boolean isDestructive() {
            return this == DELETE || this == MOVE;
        }
    }

    /**
     * 文件锁记录 — 描述一条已获取的路径锁。
     *
     * @param path      已规范化的绝对路径
     * @param owner     持锁者名称（通常是 agentId）
     * @param event     锁事件类型
     * @param condition 用于通知等待者的 Condition（与 {@link #registryLock} 关联）
     */
    public record FileLockRecord(
            String path,
            String owner,
            LockEvent event,
            Condition condition
    ) {}

    /** 路径锁注册表 — 规范化路径 → 锁记录 */
    private final Map<String, FileLockRecord> lockRegistry = new ConcurrentHashMap<>();

    /** 注册表全局锁 — 保护 lockRegistry 的读改写操作和 Condition 通知 */
    private final ReentrantLock registryLock = new ReentrantLock();

    private VfsLockManager() {}

    // ════════════════════════════════════════════════════════════════
    //  路径工具
    // ════════════════════════════════════════════════════════════════

    /**
     * 规范化锁路径 — 类比 Apix 的 {@code _normalize_lock_path}。
     * 将路径解析为绝对、无 {@code .}/{@code ..} 的规范形式。
     */
    private String normalizeLockPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Empty file path");
        }
        // 使用 Path.normalize() 处理 . 和 ..
        Path p = Path.of(path);
        if (!p.isAbsolute()) {
            // 相对路径以 "/" 为根
            p = Path.of("/").resolve(p);
        }
        return p.normalize().toString();
    }

    /**
     * 判断 parent 是否等于 child 或是 child 的祖先。
     * 两者必须是已规范化的绝对路径。
     */
    private boolean isSameOrParent(String parent, String child) {
        if (parent.equals(child)) return true;
        // parent 是 child 的祖先：child 以 parent + "/" 开头
        return child.startsWith(parent) && child.length() > parent.length()
                && child.charAt(parent.length()) == '/';
    }

    /**
     * 查找后代冲突 — 在锁注册表中查找 target 本身或其后代路径的锁。
     * <p>
     * <b>调用约定</b>：必须在持有 {@link #registryLock} 时调用。
     *
     * @param normalizedTarget 已规范化的目标路径
     * @return 冲突的锁记录，无冲突返回 null
     */
    private FileLockRecord findDescendantConflictUnlocked(String normalizedTarget) {
        for (Map.Entry<String, FileLockRecord> entry : lockRegistry.entrySet()) {
            if (isSameOrParent(normalizedTarget, entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /**
     * 查找祖先破坏性冲突 — 在锁注册表中查找 target 及其祖先路径上的 DELETE/MOVE 锁。
     * <p>
     * <b>调用约定</b>：必须在持有 {@link #registryLock} 时调用。
     *
     * @param normalizedTarget 已规范化的目标路径
     * @return 冲突的锁记录，无冲突返回 null
     */
    private FileLockRecord findAncestorDestructiveConflictUnlocked(String normalizedTarget) {
        Path target = Path.of(normalizedTarget);
        // 遍历 target 本身及其所有祖先路径
        for (Path ancestor = target; ancestor != null; ancestor = ancestor.getParent()) {
            FileLockRecord record = lockRegistry.get(ancestor.toString());
            if (record != null && record.event().isDestructive()) {
                return record;
            }
        }
        return null;
    }

    // ════════════════════════════════════════════════════════════════
    //  核心 API：加锁 / 解锁
    // ════════════════════════════════════════════════════════════════

    /**
     * 加锁 — 在指定路径上获取锁。
     * <p>
     * <b>规则</b>：
     * <ul>
     *   <li>若任何祖先路径被 DELETE/MOVE 锁定，抛出异常</li>
     *   <li>若精确路径已被锁定，抛出异常</li>
     * </ul>
     *
     * @param path   要锁定的路径
     * @param owner  持锁者名称（如 agentId）
     * @param event  锁事件类型
     * @throws IllegalStateException 存在冲突锁时
     */
    public void lockFile(String path, String owner, LockEvent event) {
        String normalized = normalizeLockPath(path);

        registryLock.lock();
        try {
            FileLockRecord ancestorConflict = findAncestorDestructiveConflictUnlocked(normalized);
            if (ancestorConflict != null) {
                throw new IllegalStateException(String.format(
                        "Cannot lock path: %s. Ancestor path is locked for destructive operation: %s (owner=%s, event=%s)",
                        normalized, ancestorConflict.path(), ancestorConflict.owner(), ancestorConflict.event()));
            }

            FileLockRecord existing = lockRegistry.get(normalized);
            if (existing != null) {
                throw new IllegalStateException(String.format(
                        "Path already locked: %s (owner=%s, event=%s)",
                        normalized, existing.owner(), existing.event()));
            }

            FileLockRecord record = new FileLockRecord(
                    normalized, owner, event, registryLock.newCondition());
            lockRegistry.put(normalized, record);
            log.trace("[VfsLock] 已加锁: {} (owner={}, event={})", normalized, owner, event);
        } finally {
            registryLock.unlock();
        }
    }

    /**
     * 解锁 — 释放指定路径的锁，并通知所有等待者。
     *
     * @param path 要解锁的路径
     */
    public void unlockFile(String path) {
        String normalized = normalizeLockPath(path);

        registryLock.lock();
        try {
            FileLockRecord removed = lockRegistry.remove(normalized);
            if (removed != null) {
                // signalAll 通知所有等待该路径解锁的协程/线程
                removed.condition().signalAll();
                log.trace("[VfsLock] 已解锁: {} (owner={}, event={})",
                        normalized, removed.owner(), removed.event());
            }
        } finally {
            registryLock.unlock();
        }
    }

    /**
     * 等待路径可操作 — 阻塞当前线程直到路径无冲突，或超时。
     * <p>
     * <b>行为</b>：
     * <ul>
     *   <li>对于 DELETE/MOVE：等待直到目标路径及所有后代路径均无锁，
     *       且无祖先路径被 DELETE/MOVE 锁定</li>
     *   <li>对于其他事件（或 event 为 null）：等待直到精确路径解锁，
     *       且无祖先路径被 DELETE/MOVE 锁定</li>
     * </ul>
     * <p>
     * 适用于虚拟线程场景 — 阻塞的是虚拟线程，不会阻塞载体线程。
     *
     * @param path    要操作的路径
     * @param event   操作事件类型（可为 null，表示仅检查祖先冲突）
     * @param timeout 超时时间
     * @throws VfsLockTimeoutException 超时未获取到操作权
     */
    public void getFileLock(String path, LockEvent event, Duration timeout)
            throws VfsLockTimeoutException {
        String normalized = normalizeLockPath(path);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        registryLock.lock();
        try {
            while (true) {
                FileLockRecord conflictRecord = null;
                String conflictType = null;

                // 1. 检查祖先破坏性锁
                FileLockRecord ancestorConflict = findAncestorDestructiveConflictUnlocked(normalized);
                if (ancestorConflict != null) {
                    conflictType = "ancestor_destructive_lock";
                    conflictRecord = ancestorConflict;
                } else if (event != null && event.isDestructive()) {
                    // 2. 破坏性操作：检查后代锁
                    FileLockRecord descendantConflict = findDescendantConflictUnlocked(normalized);
                    if (descendantConflict != null) {
                        conflictType = "descendant_lock";
                        conflictRecord = descendantConflict;
                    }
                } else {
                    // 3. 普通操作：检查精确路径锁
                    FileLockRecord exactConflict = lockRegistry.get(normalized);
                    if (exactConflict != null) {
                        conflictType = "exact_path_lock";
                        conflictRecord = exactConflict;
                    }
                }

                if (conflictRecord == null) {
                    // 无冲突，路径可操作
                    return;
                }

                // 计算剩余等待时间
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    throw new VfsLockTimeoutException(buildLockTimeoutMessage(
                            normalized, event, timeout, conflictType, conflictRecord));
                }

                // 在冲突记录的 Condition 上等待
                try {
                    boolean signaled = conflictRecord.condition().await(remainingNanos, TimeUnit.NANOSECONDS);
                    // 被唤醒或超时后重新检查（循环回到顶部）
                    if (!signaled) {
                        // await 超时，再检查一次（可能在等待期间锁已被释放）
                        continue;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new VfsLockTimeoutException("Interrupted while waiting for file lock: " + normalized, e);
                }
            }
        } finally {
            registryLock.unlock();
        }
    }

    /**
     * 断言路径操作允许 — 非阻塞检查，不允许则抛异常。
     * <p>
     * <b>规则</b>：
     * <ul>
     *   <li>任何操作：若祖先被 DELETE/MOVE 锁定则拒绝</li>
     *   <li>DELETE/MOVE：若目标本身或任何后代被锁定则拒绝</li>
     *   <li>其他：若精确路径被锁定则拒绝</li>
     * </ul>
     *
     * @param path  要检查的路径
     * @param event 操作事件类型
     * @throws IllegalStateException 操作被阻塞时
     */
    public void assertPathOperationAllowed(String path, LockEvent event) {
        String normalized = normalizeLockPath(path);

        registryLock.lock();
        try {
            FileLockRecord ancestorConflict = findAncestorDestructiveConflictUnlocked(normalized);
            if (ancestorConflict != null) {
                throw new IllegalStateException(String.format(
                        "Path operation blocked: %s %s. Ancestor path is locked for destructive operation: %s (owner=%s, event=%s)",
                        event, normalized, ancestorConflict.path(), ancestorConflict.owner(), ancestorConflict.event()));
            }

            if (event != null && event.isDestructive()) {
                FileLockRecord descendantConflict = findDescendantConflictUnlocked(normalized);
                if (descendantConflict != null) {
                    throw new IllegalStateException(String.format(
                            "Path operation blocked: %s %s. Locked descendant exists: %s (owner=%s, event=%s)",
                            event, normalized, descendantConflict.path(), descendantConflict.owner(), descendantConflict.event()));
                }
            } else {
                FileLockRecord exactConflict = lockRegistry.get(normalized);
                if (exactConflict != null) {
                    throw new IllegalStateException(String.format(
                            "Path operation blocked: %s %s. Path is locked by %s for %s",
                            event, normalized, exactConflict.owner(), exactConflict.event()));
                }
            }
        } finally {
            registryLock.unlock();
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  查询 API
    // ════════════════════════════════════════════════════════════════

    /** 路径是否已被锁定 */
    public boolean isFileLocked(String path) {
        String normalized = normalizeLockPath(path);
        registryLock.lock();
        try {
            return lockRegistry.containsKey(normalized);
        } finally {
            registryLock.unlock();
        }
    }

    /**
     * 获取路径的锁信息。
     *
     * @return [owner, event] 或 null（未锁定时）
     */
    public String[] getFileLockInfo(String path) {
        String normalized = normalizeLockPath(path);
        registryLock.lock();
        try {
            FileLockRecord record = lockRegistry.get(normalized);
            if (record == null) return null;
            return new String[]{record.owner(), record.event().name()};
        } finally {
            registryLock.unlock();
        }
    }

    /**
     * 获取目标路径子树下所有锁信息（含自身）。
     *
     * @return 锁信息列表，每项为 [path, owner, event]
     */
    public List<String[]> getSubtreeLockInfo(String path) {
        String normalized = normalizeLockPath(path);
        List<String[]> result = new ArrayList<>();
        registryLock.lock();
        try {
            for (Map.Entry<String, FileLockRecord> entry : lockRegistry.entrySet()) {
                if (isSameOrParent(normalized, entry.getKey())) {
                    FileLockRecord r = entry.getValue();
                    result.add(new String[]{r.path(), r.owner(), r.event().name()});
                }
            }
        } finally {
            registryLock.unlock();
        }
        return result;
    }

    /** 当前活跃锁数量 */
    public int activeLockCount() {
        return lockRegistry.size();
    }

    // ════════════════════════════════════════════════════════════════
    //  组合 API：单文件锁上下文 + 多文件锁上下文
    // ════════════════════════════════════════════════════════════════

    /**
     * 单文件锁上下文 — AutoCloseable，支持 try-with-resources。
     * <p>
     * 用法：
     * <pre>{@code
     * try (var lock = VfsLockManager.instance().fileLock("/factory/app.py", "agent_1", LockEvent.MODIFY)) {
     *     // 在锁保护下操作文件
     *     VfsManager.instance().writeText("/factory/app.py", content);
     * }
     * }</pre>
     *
     * @param path    要锁定的路径
     * @param owner   持锁者
     * @param event   锁事件类型
     * @param timeout 等待超时
     * @return AutoCloseable 锁句柄，close 时自动释放
     */
    public AutoCloseable fileLock(String path, String owner, LockEvent event, Duration timeout) {
        getFileLock(path, event, timeout);
        lockFile(path, owner, event);
        return () -> unlockFile(path);
    }

    /** 单文件锁上下文（默认 30s 超时） */
    public AutoCloseable fileLock(String path, String owner, LockEvent event) {
        return fileLock(path, owner, event, Duration.ofSeconds(30));
    }

    /**
     * 多文件锁上下文 — 按路径排序获取，避免 AB-BA 死锁。
     * <p>
     * 用法：
     * <pre>{@code
     * try (var lock = VfsLockManager.instance().multiFileLock(List.of(
     *         new LockItem("/factory/src.txt", LockEvent.MOVE),
     *         new LockItem("/factory/dst.txt", LockEvent.CREATE)
     * ), "agent_1")) {
     *     // 原子地移动文件
     * }
     * }</pre>
     *
     * @param items   锁项列表
     * @param owner   持锁者
     * @param timeout 总超时预算（用于获取所有锁）
     * @return AutoCloseable 锁句柄，close 时逆序释放
     */
    public AutoCloseable multiFileLock(List<LockItem> items, String owner, Duration timeout) {
        // 去重 + 规范化，检测同路径冲突事件
        Map<String, LockEvent> eventMap = new LinkedHashMap<>();
        for (LockItem item : items) {
            String normalized = normalizeLockPath(item.path());
            LockEvent existing = eventMap.get(normalized);
            if (existing != null) {
                if (existing != item.event()) {
                    throw new IllegalStateException(String.format(
                            "Conflicting lock request for same path: %s. events=(%s, %s), owner=%s",
                            normalized, existing, item.event(), owner));
                }
                continue;
            }
            eventMap.put(normalized, item.event());
        }

        // 按路径字典序排序，确保全局一致的加锁顺序
        List<Map.Entry<String, LockEvent>> sorted = new ArrayList<>(eventMap.entrySet());
        sorted.sort(Comparator.comparing(Map.Entry::getKey));

        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        List<String> acquired = new ArrayList<>();

        boolean failed = false;
        RuntimeException failureEx = null;

        for (Map.Entry<String, LockEvent> entry : sorted) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                failed = true;
                failureEx = new VfsLockTimeoutException(String.format(
                        "Timed out waiting %s for multi-file lock acquisition. current_path=%s, owner=%s",
                        timeout, entry.getKey(), owner));
                break;
            }
            try {
                Duration remaining = Duration.ofNanos(remainingNanos);
                getFileLock(entry.getKey(), entry.getValue(), remaining);
                lockFile(entry.getKey(), owner, entry.getValue());
                acquired.add(entry.getKey());
            } catch (RuntimeException e) {
                failed = true;
                failureEx = e;
                break;
            }
        }

        if (failed) {
            // 逆序释放已获取的锁
            for (int i = acquired.size() - 1; i >= 0; i--) {
                try {
                    unlockFile(acquired.get(i));
                } catch (Exception ignore) {
                    // 释放时忽略异常，确保不掩盖原始失败
                }
            }
            throw failureEx;
        }

        log.trace("[VfsLock] 多文件锁已获取: {} 个路径 (owner={})", acquired.size(), owner);

        // 返回 AutoCloseable，close 时逆序释放
        return () -> {
            for (int i = acquired.size() - 1; i >= 0; i--) {
                unlockFile(acquired.get(i));
            }
            log.trace("[VfsLock] 多文件锁已释放: {} 个路径 (owner={})", acquired.size(), owner);
        };
    }

    /** 多文件锁上下文（默认 30s 超时） */
    public AutoCloseable multiFileLock(List<LockItem> items, String owner) {
        return multiFileLock(items, owner, Duration.ofSeconds(30));
    }

    // ════════════════════════════════════════════════════════════════
    //  辅助类型
    // ════════════════════════════════════════════════════════════════

    /**
     * 锁项 — 多文件锁的单个条目。
     *
     * @param path  路径
     * @param event 锁事件类型
     */
    public record LockItem(String path, LockEvent event) {}

    /** 构建锁超时错误消息 */
    private String buildLockTimeoutMessage(
            String requestedPath, LockEvent requestedEvent, Duration waited,
            String conflictType, FileLockRecord conflictRecord) {
        return String.format(
                "Timed out waiting %.2fs for file lock. requested_path=%s, requested_event=%s, " +
                        "conflict_type=%s, conflict_path=%s, lock_owner=%s, lock_event=%s",
                waited.toMillis() / 1000.0,
                requestedPath,
                requestedEvent,
                conflictType,
                conflictRecord.path(),
                conflictRecord.owner(),
                conflictRecord.event());
    }

    /**
     * VFS 锁超时异常 — 等待文件锁时超时。
     */
    public static class VfsLockTimeoutException extends RuntimeException {
        public VfsLockTimeoutException(String message) {
            super(message);
        }

        public VfsLockTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
