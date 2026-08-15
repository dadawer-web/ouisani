package com.ouisani.aios.core.task;

import java.util.*;

/**
 * 任务分配策略接口 — 借鉴 OMA (open-multi-agent) 的 Scheduler 设计。
 * <p>
 * 解决"多个待执行任务如何分配给多个可用 Agent"的问题。
 * 四种策略:
 * <ul>
 *   <li>{@link RoundRobinStrategy} — 按序轮分,均匀分布</li>
 *   <li>{@link LeastBusyStrategy} — 分配给当前 in_progress 任务最少的 Agent</li>
 *   <li>{@link CapabilityMatchStrategy} — 任务描述与 Agent role 的关键词重叠打分</li>
 *   <li>{@link DependencyFirstStrategy} — 优先分配阻塞 dependents 最多的任务 (关键路径优先,默认)</li>
 * </ul>
 * <p>
 * OS 类比:相当于 Linux 调度器的 task placement policy — 决定新进程在哪个 CPU 核上运行。
 */
public interface TaskAssignmentStrategy {

    /**
     * 将待分配任务映射到可用 Agent。
     *
     * @param tasks  所有任务快照 (含已完成/进行中,用于依赖和负载计算)
     * @param agents 可用 Agent 列表
     * @return taskId → agentName 映射 (仅含本次新分配的任务)
     */
    Map<String, String> assign(List<AssignableTask> tasks, List<AgentInfo> agents);

    /**
     * 待分配任务 — 对标 OMA 的 Task 类型。
     *
     * @param id         任务 ID
     * @param title      任务标题 (简短描述)
     * @param description 任务详细描述
     * @param status     任务状态: pending / in_progress / done / failed
     * @param assignee   已分配的 Agent 名 (null 表示未分配)
     * @param dependsOn  依赖的任务 ID 列表 (必须全部完成后此任务才能开始)
     */
    record AssignableTask(
            String id,
            String title,
            String description,
            String status,
            String assignee,
            List<String> dependsOn
    ) {
        public AssignableTask {
            if (dependsOn == null) dependsOn = List.of();
        }
    }

    /**
     * Agent 信息 — 对标 OMA 的 AgentConfig 类型。
     *
     * @param name Agent 名称 (唯一标识)
     * @param role Agent 角色/职责描述 (用于 capability-match)
     * @param model Agent 使用的模型 (如 gpt-4o, 用于关键词提取)
     */
    record AgentInfo(String name, String role, String model) {}

    /**
     * 根据策略名创建实例。
     *
     * @param name 策略名: round-robin / least-busy / capability-match / dependency-first
     * @return 策略实例
     */
    static TaskAssignmentStrategy of(String name) {
        return switch (name) {
            case "round-robin"        -> new RoundRobinStrategy();
            case "least-busy"         -> new LeastBusyStrategy();
            case "capability-match"   -> new CapabilityMatchStrategy();
            case "dependency-first"   -> new DependencyFirstStrategy();
            default -> throw new IllegalArgumentException(
                    "Unknown strategy: " + name + " (allowed: round-robin/least-busy/capability-match/dependency-first)");
        };
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Round-Robin — 按序轮分
// ═══════════════════════════════════════════════════════════════════════

/**
 * 轮询策略 — 按序将任务分配给 Agent,循环到末尾后从头开始。
 * <p>
 * cursor 在多次调用间持续前进,避免总是从 agent[0] 开始。
 */
final class RoundRobinStrategy implements TaskAssignmentStrategy {

    private int cursor = 0;

    @Override
    public Map<String, String> assign(List<AssignableTask> tasks, List<AgentInfo> agents) {
        if (agents.isEmpty()) return Map.of();

        List<AssignableTask> unassigned = filterUnassigned(tasks);
        Map<String, String> result = new LinkedHashMap<>();

        for (AssignableTask task : unassigned) {
            AgentInfo agent = agents.get(cursor % agents.size());
            result.put(task.id(), agent.name());
            cursor = (cursor + 1) % agents.size();
        }
        return result;
    }

    static List<AssignableTask> filterUnassigned(List<AssignableTask> tasks) {
        return tasks.stream()
                .filter(t -> "pending".equals(t.status()) && t.assignee() == null)
                .toList();
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Least-Busy — 负载均衡
// ═══════════════════════════════════════════════════════════════════════

/**
 * 最闲优先策略 — 分配给当前 in_progress 任务最少的 Agent。
 * <p>
 * 负载从 allTasks 的 in_progress + assignee 统计。同负载时选 agents 数组中靠前的。
 * 每分配一个任务,模拟负载 +1,避免后续任务堆积到同一 Agent。
 */
final class LeastBusyStrategy implements TaskAssignmentStrategy {

    @Override
    public Map<String, String> assign(List<AssignableTask> tasks, List<AgentInfo> agents) {
        if (agents.isEmpty()) return Map.of();

        List<AssignableTask> unassigned = RoundRobinStrategy.filterUnassigned(tasks);

        // 统计每个 Agent 当前 in_progress 任务数
        Map<String, Integer> load = new HashMap<>();
        for (AgentInfo agent : agents) {
            load.put(agent.name(), 0);
        }
        for (AssignableTask task : tasks) {
            if ("in_progress".equals(task.status()) && task.assignee() != null) {
                load.merge(task.assignee(), 1, Integer::sum);
            }
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (AssignableTask task : unassigned) {
            // 选负载最低的 Agent
            AgentInfo best = agents.get(0);
            int bestLoad = load.getOrDefault(best.name(), 0);
            for (int i = 1; i < agents.size(); i++) {
                int agentLoad = load.getOrDefault(agents.get(i).name(), 0);
                if (agentLoad < bestLoad) {
                    bestLoad = agentLoad;
                    best = agents.get(i);
                }
            }
            result.put(task.id(), best.name());
            // 模拟负载 +1,避免后续任务继续选同一个
            load.merge(best.name(), 1, Integer::sum);
        }
        return result;
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Capability-Match — 专长匹配
// ═══════════════════════════════════════════════════════════════════════

/**
 * 能力匹配策略 — 任务描述与 Agent role 的关键词重叠打分,最高分获胜。
 * <p>
 * 双向打分: task keywords vs agent text + agent keywords vs task text。
 * 无正分时 fallback 到数组第一个 Agent。
 */
final class CapabilityMatchStrategy implements TaskAssignmentStrategy {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "a", "an", "of", "for", "with", "and", "to", "in", "on", "at",
            "is", "are", "be", "by", "this", "that", "it", "from", "or", "as",
            "的", "了", "在", "是", "和", "与", "或", "对", "从", "到", "用", "为"
    );

    @Override
    public Map<String, String> assign(List<AssignableTask> tasks, List<AgentInfo> agents) {
        if (agents.isEmpty()) return Map.of();

        List<AssignableTask> unassigned = RoundRobinStrategy.filterUnassigned(tasks);

        // 预计算每个 Agent 的关键词
        Map<String, Set<String>> agentKeywords = new HashMap<>();
        for (AgentInfo agent : agents) {
            String agentText = agent.name() + " " + (agent.role() != null ? agent.role() : "")
                    + " " + (agent.model() != null ? agent.model() : "");
            agentKeywords.put(agent.name(), extractKeywords(agentText));
        }

        Map<String, String> result = new LinkedHashMap<>();
        for (AssignableTask task : unassigned) {
            String taskText = task.title() + " " + (task.description() != null ? task.description() : "");
            Set<String> taskKeywords = extractKeywords(taskText);

            AgentInfo best = agents.get(0);
            int bestScore = -1;

            for (AgentInfo agent : agents) {
                String agentText = agent.name() + " " + (agent.role() != null ? agent.role() : "");
                int scoreA = keywordOverlap(agentText, taskKeywords);
                int scoreB = keywordOverlap(taskText, agentKeywords.get(agent.name()));
                int score = scoreA + scoreB;

                if (score > bestScore) {
                    bestScore = score;
                    best = agent;
                }
            }
            result.put(task.id(), best.name());
        }
        return result;
    }

    /** 提取关键词:小写化 + 分词 + 过滤停用词 + 去重 + 中文 2-gram */
    static Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> keywords = new HashSet<>();
        // 按非字母数字汉字分割
        for (String word : text.toLowerCase().split("[^a-z0-9\\u4e00-\\u9fa5]+")) {
            if (word.length() <= 1 || STOP_WORDS.contains(word)) continue;

            if (word.matches("[a-z0-9]+")) {
                // 英文/数字:直接加入
                keywords.add(word);
            } else {
                // 含中文:保留完整词 + 提取 2-gram (解决中文无空格分词问题)
                keywords.add(word);
                for (int i = 0; i < word.length() - 1; i++) {
                    keywords.add(word.substring(i, i + 2));
                }
            }
        }
        return keywords;
    }

    /** 计算 text 中包含多少个 keywords 中的词 */
    static int keywordOverlap(String text, Set<String> keywords) {
        if (text == null || keywords.isEmpty()) return 0;
        String lower = text.toLowerCase();
        int count = 0;
        for (String kw : keywords) {
            if (lower.contains(kw)) count++;
        }
        return count;
    }
}

// ═══════════════════════════════════════════════════════════════════════
//  Dependency-First — 关键路径优先 (默认策略)
// ═══════════════════════════════════════════════════════════════════════

/**
 * 依赖优先策略 — 优先分配阻塞 dependents 最多的任务 (关键路径启发式)。
 * <p>
 * 算法:
 * <ol>
 *   <li>构建反向邻接表: dependencyId → 依赖它的任务列表</li>
 *   <li>对每个待分配任务做 BFS,统计传递性阻塞的 dependents 数</li>
 *   <li>按 dependents 数降序排序,高优先级任务先选 Agent</li>
 *   <li>同优先级内用 round-robin 选 Agent,避免单 Agent 过载</li>
 * </ol>
 * <p>
 * 这是最安全的默认策略 — 关键路径上的任务先跑,整体完成时间最短。
 */
final class DependencyFirstStrategy implements TaskAssignmentStrategy {

    private int cursor = 0;

    @Override
    public Map<String, String> assign(List<AssignableTask> tasks, List<AgentInfo> agents) {
        if (agents.isEmpty()) return Map.of();

        List<AssignableTask> unassigned = RoundRobinStrategy.filterUnassigned(tasks);
        if (unassigned.isEmpty()) return Map.of();

        // 按 blockedDependents 数降序排序
        List<AssignableTask> ranked = new ArrayList<>(unassigned);
        ranked.sort((a, b) -> {
            int critA = countBlockedDependents(a.id(), tasks);
            int critB = countBlockedDependents(b.id(), tasks);
            return Integer.compare(critB, critA);  // 降序
        });

        Map<String, String> result = new LinkedHashMap<>();
        for (AssignableTask task : ranked) {
            AgentInfo agent = agents.get(cursor % agents.size());
            result.put(task.id(), agent.name());
            cursor = (cursor + 1) % agents.size();
        }
        return result;
    }

    /**
     * 统计传递性阻塞的 dependents 数 — 前向 BFS。
     * <p>
     * 构建 reverse adjacency: for each task, for each depId in dependsOn,
     * depId → task.id。然后从 taskId 出发 BFS,统计可达节点数 (不含自身)。
     */
    static int countBlockedDependents(String taskId, List<AssignableTask> allTasks) {
        // 构建反向邻接表: dependencyId → [dependentTaskId, ...]
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, AssignableTask> idToTask = new HashMap<>();
        for (AssignableTask t : allTasks) {
            idToTask.put(t.id(), t);
            for (String depId : t.dependsOn()) {
                dependents.computeIfAbsent(depId, k -> new ArrayList<>()).add(t.id());
            }
        }

        // BFS
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(taskId);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String depId : dependents.getOrDefault(current, List.of())) {
                if (!visited.contains(depId) && idToTask.containsKey(depId)) {
                    visited.add(depId);
                    queue.add(depId);
                }
            }
        }
        // 排除种子任务自身
        visited.remove(taskId);
        return visited.size();
    }
}
