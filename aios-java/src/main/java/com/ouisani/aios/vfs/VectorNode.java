package com.ouisani.aios.vfs;

import com.ouisani.aios.core.VfsNode;
import com.ouisani.aios.core.llm.LlmProvider;
import com.ouisani.aios.core.llm.VectorMath;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public non-sealed class VectorNode implements VfsNode {

    private final String path;
    private final LlmProvider llmProvider;
    private final List<VectorRecord> records;
    private int ownerUid;
    private int permissions;

    public VectorNode(String path, LlmProvider llmProvider) {
        this(path, llmProvider, 0, 0666);
    }

    public VectorNode(String path, LlmProvider llmProvider, int ownerUid, int permissions) {
        this.path = path;
        this.llmProvider = llmProvider;
        this.records = new CopyOnWriteArrayList<>();
        this.ownerUid = ownerUid;
        this.permissions = permissions;
    }

    @Override
    public VfsNodeType nodeType() {
        return VfsNodeType.VECTOR;
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
        StringBuilder sb = new StringBuilder();
        sb.append("{\"path\":\"").append(path).append("\",");
        sb.append("\"recordCount\":").append(records.size()).append(",");
        sb.append("\"records\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(",");
            VectorRecord r = records.get(i);
            sb.append("{\"id\":").append(i).append(",")
              .append("\"text\":\"").append(escape(r.text)).append("\",")
              .append("\"dimensions\":").append(r.vector.length).append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public boolean write(String payload) {
        if (payload == null || payload.isBlank()) return false;

        float[] vector = llmProvider.embed(payload);
        records.add(new VectorRecord(payload, vector));

        System.out.printf("  [VectorNode] %s: embedded %d chars → %d-dim vector (total records: %d)%n",
                path, payload.length(), vector.length, records.size());
        return true;
    }

    public String search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return "[]";
        }

        float[] queryVec = llmProvider.embed(query);
        System.out.printf("  [VectorNode] %s: searching '%s' across %d records (topK=%d)%n",
                path, query.substring(0, Math.min(40, query.length())), records.size(), topK);

        List<SearchResult> results = new ArrayList<>();
        for (int i = 0; i < records.size(); i++) {
            VectorRecord record = records.get(i);
            float similarity = VectorMath.cosineSimilarity(queryVec, record.vector);
            results.add(new SearchResult(i, record.text, similarity));
        }

        results.sort(Comparator.comparingDouble(SearchResult::similarity).reversed());

        int count = Math.min(topK, results.size());
        StringBuilder sb = new StringBuilder();
        sb.append("{\"query\":\"").append(escape(query)).append("\",");
        sb.append("\"topK\":").append(topK).append(",");
        sb.append("\"results\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            SearchResult r = results.get(i);
            sb.append("{\"rank\":").append(i + 1).append(",")
              .append("\"id\":").append(r.id).append(",")
              .append("\"similarity\":").append(String.format("%.6f", r.similarity)).append(",")
              .append("\"text\":\"").append(escape(r.text)).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    public int recordCount() {
        return records.size();
    }

    public List<VectorRecord> getRecords() {
        return List.copyOf(records);
    }

    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    public record VectorRecord(String text, float[] vector) {}

    private record SearchResult(int id, String text, float similarity) {}
}
