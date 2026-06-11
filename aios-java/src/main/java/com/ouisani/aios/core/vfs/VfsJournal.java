package com.ouisani.aios.core.vfs;

import com.ouisani.aios.core.VfsManager;
import com.ouisani.aios.core.VfsNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VFS 写前日志（WAL）— AIOS 虚拟文件系统的日志系统。
 * <p>
 * 每次变更 VFS 的操作先以 {@code DSYNC} 模式追加到持久化日志文件，
 * 然后才应用到内存结构。启动时 {@link #recoverAll()} 重放日志以恢复崩溃一致性。
 * <p>
 * 日志文件: {@code /tmp/aios_vfs.journal}<br>
 * 记录格式: {@code TIMESTAMP|NODE_PATH|OPERATION|PAYLOAD\n}
 * <p>
 * OS 类比: ext4 的 jbd2 日志系统 / PostgreSQL 的 WAL 机制。
 */
public final class VfsJournal {

    private static final Logger log = LoggerFactory.getLogger(VfsJournal.class);

    private static final String JOURNAL_PATH = "/tmp/aios_vfs.journal";
    private static final String SEPARATOR = "|";
    private static final byte[] LINE_END = "\n".getBytes(StandardCharsets.UTF_8);

    private static final class Holder {
        static final VfsJournal INSTANCE = new VfsJournal();
    }

    public static VfsJournal getInstance() {
        return Holder.INSTANCE;
    }

    private FileChannel channel;
    private volatile boolean open = false;
    private final AtomicLong totalAppended = new AtomicLong(0);

    private VfsJournal() {}

    /**
     * Open the journal file for appending.
     */
    public synchronized void open() {
        if (open) return;
        try {
            Path path = Path.of(JOURNAL_PATH);
            channel = FileChannel.open(path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.DSYNC);
            open = true;
            log.info("[VFS Journal] Opened: {} (size={} bytes)", JOURNAL_PATH, Files.size(path));
        } catch (IOException e) {
            log.error("[VFS Journal] Failed to open journal: {}", e.getMessage());
        }
    }

    /**
     * Close the journal file.
     */
    public synchronized void close() {
        if (!open) return;
        try {
            if (channel != null) {
                channel.close();
            }
            open = false;
            log.info("[VFS Journal] Closed. Total entries appended: {}", totalAppended.get());
        } catch (IOException e) {
            log.error("[VFS Journal] Failed to close journal: {}", e.getMessage());
        }
    }

    /**
     * Append a WAL entry for a VFS write operation.
     * The entry is synchronously flushed to disk (DSYNC) before returning.
     *
     * @param nodePath  the VFS path being written
     * @param operation the operation type (e.g. "WRITE")
     * @param payload   the data being written
     */
    public void appendLog(String nodePath, String operation, String payload) {
        if (!open) return;

        long timestamp = System.currentTimeMillis();
        // Escape newlines in payload to keep one-record-per-line
        String safePayload = payload.replace("\n", "\\n").replace("\r", "");
        String record = timestamp + SEPARATOR + nodePath + SEPARATOR + operation + SEPARATOR + safePayload;

        ByteBuffer buffer = ByteBuffer.wrap(
                (record + "\n").getBytes(StandardCharsets.UTF_8));

        try {
            channel.write(buffer);
            totalAppended.incrementAndGet();
            log.debug("[VFS Journal] Appended: op={}, path={}, payloadLen={}",
                    operation, nodePath, safePayload.length());
        } catch (IOException e) {
            log.error("[VFS Journal] Write failed: {}", e.getMessage());
        }
    }

    /**
     * Replay all journal entries to restore crash consistency.
     * Called during VfsManager initialization.
     *
     * @return the number of operations replayed
     */
    public int recoverAll() {
        Path path = Path.of(JOURNAL_PATH);
        if (!Files.exists(path)) {
            log.info("[VFS Journal] No journal file found at {}, skipping recovery", JOURNAL_PATH);
            return 0;
        }

        List<JournalEntry> entries = readJournal(path);
        if (entries.isEmpty()) {
            log.info("[VFS Journal] Journal is empty, nothing to replay");
            return 0;
        }

        log.info("[VFS Journal] Replaying {} ops from WAL... Crash consistency restored!", entries.size());

        int replayed = 0;
        for (JournalEntry entry : entries) {
            try {
                var nodeOpt = VfsManager.instance().resolve(entry.nodePath);
                if (nodeOpt.isPresent()) {
                    VfsNode node = nodeOpt.get();
                    node.write(entry.payload);
                    replayed++;
                    log.debug("[VFS Journal] Replayed: op={}, path={}", entry.operation, entry.nodePath);
                } else {
                    log.warn("[VFS Journal] Replay skipped: path '{}' not found in VFS", entry.nodePath);
                }
            } catch (Exception e) {
                log.warn("[VFS Journal] Replay error for path '{}': {}", entry.nodePath, e.getMessage());
            }
        }

        log.info("[VFS Journal] Recovery complete: {}/{} entries replayed successfully", replayed, entries.size());
        return replayed;
    }

    /**
     * Read all entries from the journal file.
     */
    private List<JournalEntry> readJournal(Path path) {
        List<JournalEntry> entries = new ArrayList<>();
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            for (String line : lines) {
                JournalEntry entry = parseLine(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException e) {
            log.error("[VFS Journal] Failed to read journal: {}", e.getMessage());
        }
        return entries;
    }

    /**
     * Parse a single journal line: TIMESTAMP|NODE_PATH|OPERATION|PAYLOAD
     */
    private JournalEntry parseLine(String line) {
        if (line == null || line.isBlank()) return null;

        int firstSep = line.indexOf(SEPARATOR);
        if (firstSep < 0) return null;

        int secondSep = line.indexOf(SEPARATOR, firstSep + 1);
        if (secondSep < 0) return null;

        int thirdSep = line.indexOf(SEPARATOR, secondSep + 1);
        if (thirdSep < 0) return null;

        try {
            long timestamp = Long.parseLong(line.substring(0, firstSep));
            String nodePath = line.substring(firstSep + 1, secondSep);
            String operation = line.substring(secondSep + 1, thirdSep);
            String payload = line.substring(thirdSep + 1).replace("\\n", "\n");

            return new JournalEntry(timestamp, nodePath, operation, payload);
        } catch (NumberFormatException e) {
            log.warn("[VFS Journal] Malformed journal entry: {}", line.substring(0, Math.min(80, line.length())));
            return null;
        }
    }

    /**
     * Truncate the journal file (e.g. after successful checkpoint).
     */
    public void truncate() {
        try {
            if (channel != null && open) {
                channel.truncate(0);
                totalAppended.set(0);
                log.info("[VFS Journal] Journal truncated (checkpoint)");
            } else {
                Files.deleteIfExists(Path.of(JOURNAL_PATH));
                log.info("[VFS Journal] Journal file deleted");
            }
        } catch (IOException e) {
            log.error("[VFS Journal] Truncate failed: {}", e.getMessage());
        }
    }

    public long totalAppended() {
        return totalAppended.get();
    }

    public boolean isOpen() {
        return open;
    }

    record JournalEntry(long timestamp, String nodePath, String operation, String payload) {}
}
