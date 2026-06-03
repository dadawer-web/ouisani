package com.ouisani.aios.core.telemetry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Semantic ETW (Event Tracing for Windows) — zero-overhead event tracing for AIOS.
 * <p>
 * Uses a lock-free ring buffer to capture kernel events at maximum throughput.
 * No locks, no disk I/O, no console output during recording — just a single
 * atomic index increment and an array store.
 * <p>
 * Component conventions:
 * <ul>
 *   <li>"LLM" — LLM Provider (latency, model selection)</li>
 *   <li>"CGROUP" — Cgroup (token consumption, OOM events)</li>
 *   <li>"SCHEDULER" — Scheduler (context switches, spawn/cancel)</li>
 *   <li>"VFS" — VFS (read/write operations)</li>
 *   <li>"SECURITY" — Security (handle grants, denials)</li>
 *   <li>"WATCHDOG" — Watchdog (deadline exceeded)</li>
 * </ul>
 */
public final class SemanticEtw {

    private static final int BUFFER_SIZE = 16384;
    private static final int INDEX_MASK = BUFFER_SIZE - 1; // BUFFER_SIZE must be power of 2

    private static final class Holder {
        static final SemanticEtw INSTANCE = new SemanticEtw();
    }

    public static SemanticEtw getInstance() {
        return Holder.INSTANCE;
    }

    private final EventRecord[] ringBuffer = new EventRecord[BUFFER_SIZE];
    private final AtomicInteger cursor = new AtomicInteger(0);
    private final AtomicLong totalEvents = new AtomicLong(0);
    private volatile boolean enabled = true;

    private SemanticEtw() {}

    /**
     * Zero-overhead event write. No locks, no I/O, no console output.
     * Uses bitwise AND for fast modulo (BUFFER_SIZE is power of 2).
     *
     * @param component the event source (e.g. "LLM", "CGROUP", "SCHEDULER")
     * @param type      the event type (e.g. "CALL", "CONSUME", "SWITCH")
     * @param payload   the event description
     */
    public void logEvent(String component, String type, String payload) {
        if (!enabled) return;
        int idx = cursor.getAndIncrement() & INDEX_MASK;
        ringBuffer[idx] = new EventRecord(System.nanoTime(), component, type, payload);
        totalEvents.incrementAndGet();
    }

    /**
     * Flush all buffered events to a consumer (e.g. WebSocket handler).
     * Returns events in insertion order (oldest first).
     */
    public List<EventRecord> flushToConsumer() {
        long total = totalEvents.get();
        int count = (int) Math.min(total, BUFFER_SIZE);

        List<EventRecord> result = new ArrayList<>(count);

        if (total <= BUFFER_SIZE) {
            int end = cursor.get() & INDEX_MASK;
            for (int i = 0; i < end; i++) {
                EventRecord r = ringBuffer[i];
                if (r != null) result.add(r);
            }
        } else {
            int start = cursor.get() & INDEX_MASK;
            for (int i = 0; i < BUFFER_SIZE; i++) {
                int idx = (start + i) & INDEX_MASK;
                EventRecord r = ringBuffer[idx];
                if (r != null) result.add(r);
            }
        }

        return result;
    }

    /**
     * Fetch the most recent N events from the ring buffer.
     * Traverses backwards from the current cursor position.
     *
     * @param count the number of recent events to fetch
     * @return list of recent events, newest last
     */
    public List<EventRecord> fetchRecent(int count) {
        long total = totalEvents.get();
        int available = (int) Math.min(total, BUFFER_SIZE);
        int fetchCount = Math.min(count, available);

        List<EventRecord> result = new ArrayList<>(fetchCount);

        int currentCursor = cursor.get();
        for (int i = fetchCount - 1; i >= 0; i--) {
            int idx = (currentCursor - 1 - i) & INDEX_MASK;
            EventRecord r = ringBuffer[idx];
            if (r != null) result.add(r);
        }

        return result;
    }

    /**
     * Clear the ring buffer.
     */
    public void clear() {
        for (int i = 0; i < BUFFER_SIZE; i++) {
            ringBuffer[i] = null;
        }
        cursor.set(0);
        totalEvents.set(0);
    }

    public long totalEvents() {
        return totalEvents.get();
    }

    public int bufferSize() {
        return BUFFER_SIZE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
