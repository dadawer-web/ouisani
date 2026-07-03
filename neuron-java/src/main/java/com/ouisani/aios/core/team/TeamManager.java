package com.ouisani.aios.core.team;

import com.ouisani.aios.core.network.EventBus;
import com.ouisani.aios.core.telemetry.TelemetryService;
import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队管理器 — 对标 oh-my-openagent 的 Team Mode v4.0。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>team_create — 从 TeamSpec 创建团队 + 成员会话</li>
 *   <li>team_delete — 拆除状态、邮箱、任务列表</li>
 *   <li>team_send_message — 成员间通信</li>
 *   <li>team_task_create / list / update / get — 共享任务列表</li>
 *   <li>team_status — 团队运行状态</li>
 *   <li>team_shutdown_request / approve / reject — 优雅关闭</li>
 * </ul>
 * <p>
 * 存储布局：内存中维护，通过 EventBus 广播状态变更。
 * <p>
 * 关键不变量：
 * - 任务认领是原子操作（synchronized）
 * - 只有 eligible 的 Agent 才能加入团队
 * - 禁止嵌套团队
 *
 * @see TeamSpec
 * @see TeamState
 * @see TeamTask
 */
public class TeamManager {

    private static final Logger log = LoggerFactory.getLogger(TeamManager.class);
    private static final TeamManager INSTANCE = new TeamManager();

    /** Generation 完成事件频道 — 当一个 generation 内所有任务完成时广播 */
    public static final String EVENT_GENERATION_COMPLETED = "team.generation.completed";

    /** 活跃团队：teamName → TeamState */
    private final ConcurrentHashMap<String, TeamState> activeTeams = new ConcurrentHashMap<>();

    /** Generation 批量任务追踪器 — 借鉴 Apix 的 generation_tasks 机制 */
    private final GenerationTracker generationTracker = new GenerationTracker();

    private TeamManager() {
        // 设置 Generation 完成回调：通过 EventBus 广播，Lead Agent 可订阅以查询结果
        generationTracker.setOnGenerationCompleted((generationId, historyId) -> {
            String payload = String.format(
                    "{\"eventType\":\"GENERATION_COMPLETED\", \"generationId\":\"%s\", \"historyId\":\"%s\", \"timestamp\":%d}",
                    generationId, historyId != null ? historyId : "", System.currentTimeMillis());
            EventBus.instance().broadcast(EVENT_GENERATION_COMPLETED, payload);
            log.info("[TeamManager] Generation 已完成: gen={}, history={}", generationId, historyId);
        });
    }

    public static TeamManager instance() {
        return INSTANCE;
    }

    // ════════════════════════════════════════════════════════════════
    //  团队生命周期
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建团队 — 从 TeamSpec 生成团队和成员。
     *
     * @param spec 团队规格
     * @return 创建的团队状态
     */
    public TeamState createTeam(TeamSpec spec) {
        if (activeTeams.containsKey(spec.name())) {
            throw new IllegalStateException("Team '" + spec.name() + "' already exists");
        }

        TeamState team = new TeamState(
                spec.name(),
                spec.leadAgentId(),
                new ConcurrentHashMap<>(),  // members
                new ArrayList<>(),           // tasks
                TeamStatus.ACTIVE,
                System.currentTimeMillis(),
                spec.objective()
        );

        // 注册 Lead
        team.members().put(spec.leadAgentId(), new TeamMember(spec.leadAgentId(), MemberRole.LEAD, MemberStatus.ONLINE));

        // 注册成员
        for (String memberId : spec.memberIds()) {
            team.members().put(memberId, new TeamMember(memberId, MemberRole.WORKER, MemberStatus.PENDING));
        }

        activeTeams.put(spec.name(), team);

        // 广播团队创建事件
        broadcastTeamEvent("TEAM_CREATED", spec.name(), spec.leadAgentId(),
                "Team created with " + spec.memberIds().size() + " members");

        // 遥测
        TelemetryService.instance().logEvent("team_created", Map.of(
                "teamName", spec.name(),
                "leadAgentId", spec.leadAgentId(),
                "memberCount", spec.memberIds().size()
        ));

        log.info("[TeamManager] 团队已创建 '{}'。Lead: {}, Members: {}",
                spec.name(), spec.leadAgentId(), spec.memberIds());

        return team;
    }

    /**
     * 删除团队 — 拆除所有状态。
     */
    public void deleteTeam(String teamName, String requesterId) {
        TeamState team = activeTeams.get(teamName);
        if (team == null) {
            throw new IllegalStateException("Team '" + teamName + "' not found");
        }
        if (!team.leadAgentId().equals(requesterId)) {
            throw new SecurityException("Only the lead can delete the team");
        }

        // 向所有成员发送 POISON_PILL
        for (String memberId : team.members().keySet()) {
            AbstractAgent agent = TeamRegistry.getInstance().findAgent(memberId);
            if (agent != null) {
                agent.getMailbox().deliver(new MailMessage("TeamManager", memberId,
                        MailMessage.MessageType.POISON_PILL, "Team '" + teamName + "' disbanded"));
            }
        }

        activeTeams.remove(teamName);

        broadcastTeamEvent("TEAM_DELETED", teamName, requesterId, "Team disbanded");

        log.info("[TeamManager] Team '{}' deleted by {}", teamName, requesterId);
    }

    // ════════════════════════════════════════════════════════════════
    //  团队通信
    // ════════════════════════════════════════════════════════════════

    /**
     * 发送团队消息 — 点对点或广播。
     *
     * @param teamName  团队名
     * @param senderId  发送者
     * @param targetId  接收者（"*" 表示广播）
     * @param content   消息内容
     */
    public void sendMessage(String teamName, String senderId, String targetId, String content) {
        TeamState team = getTeamOrThrow(teamName);
        assertMember(team, senderId);

        if ("*".equals(targetId)) {
            // 广播
            for (String memberId : team.members().keySet()) {
                if (!memberId.equals(senderId)) {
                    dispatchMessage(senderId, memberId, content);
                }
            }
            log.debug("[TeamManager] Broadcast from {} in team {}", senderId, teamName);
        } else {
            // 点对点
            assertMember(team, targetId);
            dispatchMessage(senderId, targetId, content);
        }
    }

    private void dispatchMessage(String senderId, String targetId, String content) {
        AbstractAgent target = TeamRegistry.getInstance().findAgent(targetId);
        if (target != null) {
            target.getMailbox().deliver(new MailMessage(senderId, targetId,
                    MailMessage.MessageType.STATUS_UPDATE, content));
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  共享任务列表
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建团队任务。
     */
    public TeamTask createTask(String teamName, String creatorId, String description, String assigneeId) {
        TeamState team = getTeamOrThrow(teamName);
        assertMember(team, creatorId);

        TeamTask task = new TeamTask(
                "task_" + UUID.randomUUID().toString().substring(0, 8),
                description,
                assigneeId != null ? assigneeId : "unassigned",
                creatorId,
                TaskStatus.PENDING,
                System.currentTimeMillis()
        );

        synchronized (team.tasks()) {
            team.tasks().add(task);
        }

        // 如果指定了执行者，发送任务分配邮件
        if (assigneeId != null && !"unassigned".equals(assigneeId)) {
            AbstractAgent assignee = TeamRegistry.getInstance().findAgent(assigneeId);
            if (assignee != null) {
                assignee.getMailbox().deliver(new MailMessage(creatorId, assigneeId,
                        MailMessage.MessageType.TASK_ASSIGN, task));
            }
        }

        broadcastTeamEvent("TASK_CREATED", teamName, creatorId,
                "Task '" + task.taskId() + "' created: " + description);

        log.info("[TeamManager] 任务已在团队中创建 '{}' in team '{}' by {}", task.taskId(), teamName, creatorId);
        return task;
    }

    // ════════════════════════════════════════════════════════════════
    //  Generation 批量任务管理（借鉴 Apix team_task_manager.py）
    // ════════════════════════════════════════════════════════════════

    /**
     * 创建新的 Generation — 用于将一次用户请求产生的所有子任务归为一组。
     * <p>
     * Lead Agent 收到用户请求后调用此方法获取 generationId，
     * 然后通过 {@link #createTaskWithGeneration} 创建多个并行子任务。
     * 当所有子任务完成时，会通过 EventBus 广播 {@link #EVENT_GENERATION_COMPLETED} 事件。
     *
     * @return 新的 generationId
     * @see GenerationTracker
     */
    public String createGeneration() {
        return generationTracker.createGeneration();
    }

    /**
     * 创建关联到 Generation 的团队任务 — 借鉴 Apix 的 {@code submit_task}。
     * <p>
     * 创建的任务会注册到 GenerationTracker，当任务进入终态
     * （COMPLETED/FAILED/CANCELLED）时自动从 generation 集合中移除。
     * 当 generation 内所有任务均完成时，触发完成事件。
     *
     * @param teamName      团队名
     * @param creatorId     创建者 ID（通常是 Lead）
     * @param description   任务描述
     * @param assigneeId    执行者 ID（可为 null 表示未分配）
     * @param generationId  关联的 generation ID（通过 {@link #createGeneration} 获取）
     * @return 创建的任务
     */
    public TeamTask createTaskWithGeneration(String teamName, String creatorId,
                                              String description, String assigneeId,
                                              String generationId) {
        TeamTask task = createTask(teamName, creatorId, description, assigneeId);
        generationTracker.registerTask(generationId, task.taskId());
        log.info("[TeamManager] 任务已关联到 generation: task={}, gen={}", task.taskId(), generationId);
        return task;
    }

    /**
     * 标记任务完成 — 更新状态并触发 Generation 完成检测。
     * <p>
     * 这是 {@link #updateTask} 的便捷方法，专门用于任务完成场景。
     * 当任务完成导致其所属 generation 全部完成时，会广播
     * {@link #EVENT_GENERATION_COMPLETED} 事件。
     *
     * @param teamName  团队名
     * @param taskId    任务 ID
     * @param updaterId 更新者 ID
     * @param historyId 关联的会话历史 ID（用于完成回调，可为 null）
     * @return 更新后的任务
     */
    public TeamTask markTaskCompleted(String teamName, String taskId, String updaterId, String historyId) {
        TeamTask updated = updateTask(teamName, taskId, updaterId, TaskStatus.COMPLETED, null);
        emitGenerationCompletedIfNeeded(taskId, historyId);
        return updated;
    }

    /**
     * 标记任务失败 — 更新状态并触发 Generation 完成检测。
     *
     * @param teamName  团队名
     * @param taskId    任务 ID
     * @param updaterId 更新者 ID
     * @param historyId 关联的会话历史 ID（可为 null）
     * @return 更新后的任务
     */
    public TeamTask markTaskFailed(String teamName, String taskId, String updaterId, String historyId) {
        TeamTask updated = updateTask(teamName, taskId, updaterId, TaskStatus.FAILED, null);
        emitGenerationCompletedIfNeeded(taskId, historyId);
        return updated;
    }

    /**
     * 标记任务取消 — 更新状态并触发 Generation 完成检测。
     *
     * @param teamName  团队名
     * @param taskId    任务 ID
     * @param updaterId 更新者 ID
     * @param historyId 关联的会话历史 ID（可为 null）
     * @return 更新后的任务
     */
    public TeamTask markTaskCancelled(String teamName, String taskId, String updaterId, String historyId) {
        TeamTask updated = updateTask(teamName, taskId, updaterId, TaskStatus.CANCELLED, null);
        emitGenerationCompletedIfNeeded(taskId, historyId);
        return updated;
    }

    /**
     * 触发 Generation 完成检测 — 从 generation 集合中移除任务，
     * 当集合为空时通过回调广播完成事件。
     *
     * @param taskId    已完成的任务 ID
     * @param historyId 关联的会话历史 ID
     */
    private void emitGenerationCompletedIfNeeded(String taskId, String historyId) {
        GenerationTracker.GenerationFinishResult result = generationTracker.finishTask(taskId, historyId);
        if (result != null && result.generationFinished()) {
            log.info("[TeamManager] Generation 全部完成: gen={}, history={}",
                    result.generationId(), historyId);
        }
    }

    /**
     * 获取 Generation 中尚未完成的任务数量。
     *
     * @param generationId generation ID
     * @return 剩余任务数
     */
    public int getGenerationRemainingCount(String generationId) {
        return generationTracker.getRemainingTaskCount(generationId);
    }

    /**
     * 获取 Generation 中尚未完成的任务 ID 集合。
     *
     * @param generationId generation ID
     * @return 任务 ID 集合
     */
    public Set<String> getGenerationTasks(String generationId) {
        return generationTracker.getGenerationTasks(generationId);
    }

    /**
     * 获取 Generation 追踪器（用于高级场景直接操作）。
     *
     * @return GenerationTracker 实例
     */
    public GenerationTracker getGenerationTracker() {
        return generationTracker;
    }

    /**
     * 列出团队任务。
     */
    public List<TeamTask> listTasks(String teamName, TaskStatus filter) {
        TeamState team = getTeamOrThrow(teamName);
        synchronized (team.tasks()) {
            if (filter == null) {
                return new ArrayList<>(team.tasks());
            }
            return team.tasks().stream()
                    .filter(t -> t.status() == filter)
                    .toList();
        }
    }

    /**
     * 更新任务状态 — 认领/完成/删除（原子操作）。
     */
    public TeamTask updateTask(String teamName, String taskId, String updaterId,
                                TaskStatus newStatus, String newAssignee) {
        TeamState team = getTeamOrThrow(teamName);
        assertMember(team, updaterId);

        synchronized (team.tasks()) {
            for (TeamTask task : team.tasks()) {
                if (task.taskId().equals(taskId)) {
                    TeamTask updated = new TeamTask(
                            task.taskId(),
                            task.description(),
                            newAssignee != null ? newAssignee : task.assigneeId(),
                            task.creatorId(),
                            newStatus,
                            task.createdAt()
                    );
                    team.tasks().remove(task);
                    team.tasks().add(updated);

                    broadcastTeamEvent("TASK_UPDATED", teamName, updaterId,
                            "Task '" + taskId + "' → " + newStatus);

                    return updated;
                }
            }
        }
        throw new IllegalArgumentException("Task '" + taskId + "' not found in team '" + teamName + "'");
    }

    /**
     * 获取单个任务。
     */
    public TeamTask getTask(String teamName, String taskId) {
        TeamState team = getTeamOrThrow(teamName);
        synchronized (team.tasks()) {
            return team.tasks().stream()
                    .filter(t -> t.taskId().equals(taskId))
                    .findFirst()
                    .orElse(null);
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  团队关闭协议
    // ════════════════════════════════════════════════════════════════

    /**
     * 请求关闭团队 — 成员或 Lead 发起。
     */
    public void requestShutdown(String teamName, String requesterId, String reason) {
        TeamState team = getTeamOrThrow(teamName);
        assertMember(team, requesterId);

        // 通知 Lead
        AbstractAgent lead = TeamRegistry.getInstance().findAgent(team.leadAgentId());
        if (lead != null) {
            lead.getMailbox().deliver(new MailMessage(requesterId, team.leadAgentId(),
                    MailMessage.MessageType.QUESTION,
                    "Shutdown request: " + reason));
        }

        log.info("[TeamManager] Shutdown requested for team '{}' by {}: {}", teamName, requesterId, reason);
    }

    /**
     * 批准关闭团队。
     */
    public void approveShutdown(String teamName, String leadId) {
        TeamState team = getTeamOrThrow(teamName);
        if (!team.leadAgentId().equals(leadId)) {
            throw new SecurityException("Only the lead can approve shutdown");
        }
        deleteTeam(teamName, leadId);
    }

    /**
     * 拒绝关闭团队。
     */
    public void rejectShutdown(String teamName, String leadId, String reason) {
        TeamState team = getTeamOrThrow(teamName);
        if (!team.leadAgentId().equals(leadId)) {
            throw new SecurityException("Only the lead can reject shutdown");
        }
        // 广播拒绝原因
        sendMessage(teamName, leadId, "*", "Shutdown rejected: " + reason);
    }

    // ════════════════════════════════════════════════════════════════
    //  状态查询
    // ════════════════════════════════════════════════════════════════

    /**
     * 获取团队状态。
     */
    public TeamStatus getTeamStatus(String teamName) {
        TeamState team = activeTeams.get(teamName);
        return team != null ? team.status() : null;
    }

    /**
     * 获取完整团队状态。
     */
    public TeamState getTeamState(String teamName) {
        return activeTeams.get(teamName);
    }

    /**
     * 列出所有活跃团队。
     */
    public List<TeamState> listTeams() {
        return new ArrayList<>(activeTeams.values());
    }

    /**
     * 成员上线 — Agent 加入团队后标记为 ONLINE。
     */
    public void memberOnline(String teamName, String agentId) {
        TeamState team = activeTeams.get(teamName);
        if (team != null) {
            TeamMember member = team.members().get(agentId);
            if (member != null) {
                team.members().put(agentId, new TeamMember(agentId, member.role(), MemberStatus.ONLINE));
                log.info("[TeamManager] Member '{}' online in team '{}'", agentId, teamName);
            }
        }
    }

    // ── 内部工具方法 ──

    private TeamState getTeamOrThrow(String teamName) {
        TeamState team = activeTeams.get(teamName);
        if (team == null) {
            throw new IllegalStateException("Team '" + teamName + "' not found");
        }
        return team;
    }

    private void assertMember(TeamState team, String agentId) {
        if (!team.members().containsKey(agentId)) {
            throw new SecurityException("Agent '" + agentId + "' is not a member of team '" + team.name() + "'");
        }
    }

    private void broadcastTeamEvent(String eventType, String teamName, String agentId, String message) {
        try {
            String payload = String.format(
                    "{\"eventType\":\"%s\", \"teamName\":\"%s\", \"agentId\":\"%s\", \"message\":\"%s\", \"timestamp\":%d}",
                    eventType, teamName, agentId,
                    message.replace("\"", "'").replace("\n", " "),
                    System.currentTimeMillis()
            );
            EventBus.instance().broadcast("sys.telemetry.events", payload);
        } catch (Exception ignore) {}
    }

    // ════════════════════════════════════════════════════════════════
    //  数据类
    // ════════════════════════════════════════════════════════════════

    /** 团队规格 — 创建团队时的参数 */
    public record TeamSpec(
            String name,
            String leadAgentId,
            List<String> memberIds,
            String objective
    ) {}

    /** 团队状态 */
    public record TeamState(
            String name,
            String leadAgentId,
            ConcurrentHashMap<String, TeamMember> members,
            List<TeamTask> tasks,
            TeamStatus status,
            long createdAt,
            String objective
    ) {}

    /** 团队成员 */
    public record TeamMember(
            String agentId,
            MemberRole role,
            MemberStatus status
    ) {}

    /** 成员角色 */
    public enum MemberRole {
        LEAD,   // 领队 — 可以批准/拒绝关闭，删除团队
        WORKER  // 工人 — 执行任务
    }

    /** 成员状态 */
    public enum MemberStatus {
        PENDING,  // 已邀请但未上线
        ONLINE,   // 在线
        OFFLINE   // 离线
    }

    /** 团队状态 */
    public enum TeamStatus {
        ACTIVE,    // 活跃
        SHUTTING_DOWN,  // 关闭中
        COMPLETED  // 已完成
    }

    /** 团队任务 */
    public record TeamTask(
            String taskId,
            String description,
            String assigneeId,
            String creatorId,
            TaskStatus status,
            long createdAt
    ) {}

    /** 任务状态 */
    public enum TaskStatus {
        PENDING,       // 待认领
        IN_PROGRESS,   // 执行中
        COMPLETED,     // 已完成
        FAILED,        // 失败
        CANCELLED      // 已取消
    }
}
