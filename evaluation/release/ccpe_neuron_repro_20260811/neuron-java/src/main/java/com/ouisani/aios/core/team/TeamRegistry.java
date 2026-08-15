package com.ouisani.aios.core.team;

import com.ouisani.aios.user.sdk.AbstractAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 数字员工花名册与中央邮局。
 * <p>
 * 负责 Agent 的注册、销毁以及邮件的精准路由。
 * 对标 oh-my-openagent 的 Team 编排中心：所有 Agent 启动时在此注册，
 * 通过 dispatch() 实现跨 Agent 的异步消息投递。
 * <p>
 * 路由流程：
 * <pre>
 *   Agent A → TeamRegistry.dispatch(message) → Agent B.mailbox.deliver()
 * </pre>
 *
 * @see MailMessage
 * @see AgentMailbox
 */
public class TeamRegistry {

    private static final Logger log = LoggerFactory.getLogger(TeamRegistry.class);

    private static final TeamRegistry INSTANCE = new TeamRegistry();

    /** 花名册：agentId → Agent 实例 */
    private final ConcurrentHashMap<String, AbstractAgent> agents = new ConcurrentHashMap<>();

    /** 暂存消息：agentId → 待投递邮件列表（接收者离线时缓存，注册时投递） */
    private final ConcurrentHashMap<String, List<MailMessage>> pendingMessages = new ConcurrentHashMap<>();

    public static TeamRegistry getInstance() {
        return INSTANCE;
    }

    private TeamRegistry() {}

    /**
     * 注册 Agent — Agent 上班打卡。
     * 若该 Agent 上线前有暂存消息，注册时自动投递。
     */
    public void register(AbstractAgent agent) {
        agents.put(agent.getAgentId(), agent);
        log.info("[TeamRegistry] Agent {} clocked in. Team size: {}", agent.getAgentId(), agents.size());

        // 投递该 Agent 上线前暂存的消息
        List<MailMessage> pending = pendingMessages.remove(agent.getAgentId());
        if (pending != null && !pending.isEmpty()) {
            log.info("[TeamRegistry] 向 Agent {} 投递 {} 条暂存消息",
                    agent.getAgentId(), pending.size());
            for (MailMessage msg : pending) {
                agent.getMailbox().deliver(msg);
            }
        }
    }

    /**
     * 注销 Agent — Agent 下班销毁。
     * 同时向其信箱发送 POISON_PILL，确保事件循环优雅退出。
     */
    public void unregister(String agentId) {
        AbstractAgent agent = agents.remove(agentId);
        if (agent != null) {
            // 发送死亡药丸，确保事件循环退出
            agent.getMailbox().deliver(new MailMessage("TeamRegistry", agentId,
                    MailMessage.MessageType.POISON_PILL, "Shutdown"));
            log.info("[TeamRegistry] Agent {} clocked out. Team size: {}", agentId, agents.size());
        } else {
            log.warn("[TeamRegistry] Agent 未找到，无法注销: {}", agentId);
        }
    }

    /**
     * 精准路由 — 将邮件投递到目标 Agent 的信箱。
     * 若接收者不存在，消息将暂存到 pendingMessages，待该 Agent 注册时自动投递。
     */
    public void dispatch(MailMessage message) {
        AbstractAgent receiver = agents.get(message.getReceiverId());
        if (receiver != null) {
            receiver.getMailbox().deliver(message);
            log.debug("[TeamRegistry] Routed: {} -> {} (Type: {})",
                    message.getSenderId(), message.getReceiverId(), message.getType());
        } else {
            // 接收者不存在，暂存消息待 Agent 注册时投递
            pendingMessages.computeIfAbsent(message.getReceiverId(),
                    k -> new CopyOnWriteArrayList<>()).add(message);
            log.warn("[TeamRegistry] 接收者 {} 未找到或离线，消息已暂存 (pendingTotal={})",
                    message.getReceiverId(), getPendingMessageCount());
        }
    }

    /**
     * 广播 — 向所有已注册的 Agent 发送消息。
     */
    public void broadcast(MailMessage.MessageType type, Object payload, String senderId) {
        for (String agentId : agents.keySet()) {
            if (!agentId.equals(senderId)) { // 不发给自己
                dispatch(new MailMessage(senderId, agentId, type, payload));
            }
        }
    }

    /**
     * 查找 Agent。
     */
    public AbstractAgent findAgent(String agentId) {
        return agents.get(agentId);
    }

    /**
     * 获取所有已注册的 Agent。
     */
    public Collection<AbstractAgent> getAllAgents() {
        return agents.values();
    }

    /**
     * 当前团队规模。
     */
    public int teamSize() {
        return agents.size();
    }

    /**
     * 当前暂存（因接收者离线而未投递）的消息总数 — 用于监控。
     */
    public int getPendingMessageCount() {
        int count = 0;
        for (List<MailMessage> list : pendingMessages.values()) {
            count += list.size();
        }
        return count;
    }

    /**
     * 检查 Agent 是否在线。
     */
    public boolean isOnline(String agentId) {
        return agents.containsKey(agentId);
    }
}
