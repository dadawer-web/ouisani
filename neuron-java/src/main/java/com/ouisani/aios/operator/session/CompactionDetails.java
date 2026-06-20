package com.ouisani.aios.operator.session;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩/分支摘要的文件操作详情。
 * <p>
 * 对标 OpenClaw 的 CompactionDetails / BranchSummaryDetails。
 *
 * @param readFiles      被读取过的文件路径列表
 * @param modifiedFiles  被修改过的文件路径列表
 */
public record CompactionDetails(
        List<String> readFiles,
        List<String> modifiedFiles
) {
    public CompactionDetails {
        if (readFiles == null) readFiles = new ArrayList<>();
        if (modifiedFiles == null) modifiedFiles = new ArrayList<>();
    }

    public CompactionDetails() {
        this(new ArrayList<>(), new ArrayList<>());
    }

    /** 合并两个详情 */
    public CompactionDetails merge(CompactionDetails other) {
        List<String> mergedRead = new ArrayList<>(readFiles);
        List<String> mergedMod = new ArrayList<>(modifiedFiles);
        if (other != null) {
            for (String f : other.readFiles) {
                if (!mergedRead.contains(f)) mergedRead.add(f);
            }
            for (String f : other.modifiedFiles) {
                if (!mergedMod.contains(f)) mergedMod.add(f);
            }
        }
        return new CompactionDetails(mergedRead, mergedMod);
    }

    /** 追加到摘要末尾的文件操作信息 */
    public String toSummaryAppendix() {
        StringBuilder sb = new StringBuilder();
        if (!readFiles.isEmpty() || !modifiedFiles.isEmpty()) {
            sb.append("\n\n## File Operations\n");
            if (!readFiles.isEmpty()) {
                sb.append("- Read: ").append(String.join(", ", readFiles)).append("\n");
            }
            if (!modifiedFiles.isEmpty()) {
                sb.append("- Modified: ").append(String.join(", ", modifiedFiles)).append("\n");
            }
        }
        return sb.toString();
    }
}
