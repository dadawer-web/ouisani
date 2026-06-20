package com.ouisani.aios.user.container;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
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
    private final List<String> enabledSkills;
    private final List<String> enabledRoles;
    private final String agentType;

    private AppManifest(Builder builder) {
        this.appName = builder.appName;
        this.spawnCount = builder.spawnCount;
        this.tokenBudget = builder.tokenBudget;
        this.mounts = Collections.unmodifiableMap(new LinkedHashMap<>(builder.mounts));
        this.entrypoint = builder.entrypoint;
        this.enabledSkills = Collections.unmodifiableList(new ArrayList<>(builder.enabledSkills));
        this.enabledRoles = Collections.unmodifiableList(new ArrayList<>(builder.enabledRoles));
        this.agentType = builder.agentType;
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

    /**
     * 获取按需装载的技能模块列表。
     * <p>
     * 例如 ["skills.web_scraper", "skills.file_ops"] 表示只挂载这两个技能模块。
     * 空列表表示不挂载任何外部技能。
     *
     * @return 不可修改的技能模块名列表
     */
    public List<String> enabledSkills() {
        return enabledSkills;
    }

    /**
     * 获取按需装载的角色列表。
     * <p>
     * 例如 ["System_Architect", "Python_Coder"] 表示只挂载这两个角色。
     * 空列表表示不挂载任何特殊工程角色。
     *
     * @return 不可修改的角色名列表
     */
    public List<String> enabledRoles() {
        return enabledRoles;
    }

    /**
     * 获取内核下发的母体路由标签。
     * <p>
     * "operator" 表示路由到 OperatorAgent（物理操作），
     * "omni" 表示路由到 OmniMotherAgent（代码生成）。
     *
     * @return 母体路由标签，默认 "omni"
     */
    public String agentType() {
        return agentType;
    }

    @Override
    public String toString() {
        return "AppManifest{" +
                "appName='" + appName + '\'' +
                ", spawnCount=" + spawnCount +
                ", tokenBudget=" + tokenBudget +
                ", mounts=" + mounts +
                ", entrypoint='" + entrypoint + '\'' +
                ", enabledSkills=" + enabledSkills +
                ", enabledRoles=" + enabledRoles +
                ", agentType='" + agentType + '\'' +
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
        private final List<String> enabledSkills = new ArrayList<>();
        private final List<String> enabledRoles = new ArrayList<>();
        private String agentType = "omni";

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

        public Builder enabledSkill(String skillModule) {
            this.enabledSkills.add(skillModule);
            return this;
        }

        public Builder enabledSkills(List<String> skills) {
            this.enabledSkills.addAll(skills);
            return this;
        }

        public Builder enabledRole(String roleName) {
            this.enabledRoles.add(roleName);
            return this;
        }

        public Builder enabledRoles(List<String> roles) {
            this.enabledRoles.addAll(roles);
            return this;
        }

        public Builder agentType(String agentType) {
            this.agentType = agentType;
            return this;
        }

        public AppManifest build() {
            return new AppManifest(this);
        }
    }
}
