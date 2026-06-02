package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public non-sealed class SemanticNode implements VfsNode {

    private static final Logger log = LoggerFactory.getLogger(SemanticNode.class);

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AIOS kernel assistant.";

    private final String path;
    private final LlmProvider llmProvider;
    private final LinkedBlockingQueue<String> responseQueue;
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong totalResponses = new AtomicLong(0);
    private volatile String lastResult = "";
    private int ownerUid;
    private int permissions;

    public SemanticNode(String path, LlmProvider llmProvider) {
        this(path, llmProvider, 16, 0, 0644);
    }

    public SemanticNode(String path, LlmProvider llmProvider, int queueCapacity,
                        int ownerUid, int permissions) {
        this.path = path;
        this.llmProvider = llmProvider;
        this.responseQueue = new LinkedBlockingQueue<>(queueCapacity);
        this.ownerUid = ownerUid;
        this.permissions = permissions;
        log.info("SemanticNode created: path={}, provider={}, queueCapacity={}",
                path, llmProvider.name(), queueCapacity);
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.SEMANTIC;
    }

    @Override
    public String path() {
        return path;
    }

    @Override
    public int ownerUid() {
        return ownerUid;
    }

    @Override
    public void setOwnerUid(int uid) {
        this.ownerUid = uid;
    }

    @Override
    public int permissions() {
        return permissions;
    }

    @Override
    public void setPermissions(int perm) {
        this.permissions = perm;
    }

    @Override
    public String read() {
        try {
            String result = responseQueue.take();
            totalResponses.incrementAndGet();
            log.debug("SemanticNode.read: path={}, responseLen={}", path, result.length());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SemanticNode.read interrupted: path={}", path);
            return "";
        }
    }

    @Override
    public boolean write(String data) {
        long queryId = totalQueries.incrementAndGet();
        log.info("SemanticNode.write: path={}, queryId={}, inputLen={}", path, queryId, data.length());

        try {
            String result = llmProvider.think(data, DEFAULT_SYSTEM_PROMPT);
            lastResult = result;
            responseQueue.put(result);
            log.info("SemanticNode LLM response: path={}, queryId={}, responseLen={}",
                    path, queryId, result.length());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SemanticNode.write interrupted: path={}, queryId={}", path, queryId);
            return false;
        }
    }

    public String lastResult() {
        return lastResult;
    }

    public long totalQueries() {
        return totalQueries.get();
    }

    public long totalResponses() {
        return totalResponses.get();
    }

    public int pendingResponses() {
        return responseQueue.size();
    }

    @Override
    public String toString() {
        return "SemanticNode{path='%s', provider=%s, queries=%d, responses=%d, pending=%d}"
                .formatted(path, llmProvider.name(), totalQueries.get(),
                        totalResponses.get(), responseQueue.size());
    }
}
