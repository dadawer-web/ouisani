package com.ouisani.aios.user.container;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 应用清单 — AIOS 的 "package.json"。
 * <p>
 * 描述通用 OS 应用应如何被生成，包括并发数、Token 预算、VFS 挂载和入口命令。
 *
 * <h3>示例清单</h3>
 * <pre>
 * APP_NAME data_pipeline
 * SPAWN worker 50
 * BUDGET 5000
 * MOUNT /shared/data:/var/mem
 * ENTRYPOINT python3 /app/main.py
 * </pre>
 */
public class AppManifest {

    private final String appName;
    private final int spawnCount;
    private final int tokenBudget;
    private final Map<String, String> mounts;
    private final String entrypoint;

    private AppManifest(Builder builder) {
        this.appName = builder.appName;
        this.spawnCount = builder.spawnCount;
        this.tokenBudget = builder.tokenBudget;
        this.mounts = Collections.unmodifiableMap(new LinkedHashMap<>(builder.mounts));
        this.entrypoint = builder.entrypoint;
    }

    public String appName() {
        return appName;
    }

    public int spawnCount() {
        return spawnCount;
    }

    public int tokenBudget() {
        return tokenBudget;
    }

    public Map<String, String> mounts() {
        return mounts;
    }

    public String entrypoint() {
        return entrypoint;
    }

    @Override
    public String toString() {
        return "AppManifest{" +
                "appName='" + appName + '\'' +
                ", spawnCount=" + spawnCount +
                ", tokenBudget=" + tokenBudget +
                ", mounts=" + mounts +
                ", entrypoint='" + entrypoint + '\'' +
                '}';
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String appName = "unnamed_app";
        private int spawnCount = 1;
        private int tokenBudget = 10000;
        private final Map<String, String> mounts = new LinkedHashMap<>();
        private String entrypoint = "";

        public Builder appName(String appName) {
            this.appName = appName;
            return this;
        }

        public Builder spawnCount(int spawnCount) {
            this.spawnCount = spawnCount;
            return this;
        }

        public Builder tokenBudget(int tokenBudget) {
            this.tokenBudget = tokenBudget;
            return this;
        }

        public Builder mount(String host, String container) {
            this.mounts.put(host, container);
            return this;
        }

        public Builder entrypoint(String entrypoint) {
            this.entrypoint = entrypoint;
            return this;
        }

        public AppManifest build() {
            return new AppManifest(this);
        }
    }
}
