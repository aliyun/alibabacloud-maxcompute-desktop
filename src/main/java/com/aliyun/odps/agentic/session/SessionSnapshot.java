package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.model.Tokens;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话状态的结构化快照，可用于外部持久化与审计。
 *
 * <p>快照以 {@link Message} 历史作为跨运行记忆的权威来源，而不是扁平化后的提示词字符串。
 *
 * @param sessionId 会话 ID
 * @param agentName 代理名称
 * @param modelId 模型 ID
 * @param messages 会话消息历史
 * @param cost 累计成本
 * @param tokens 累计 Token 用量
 * @param createdAt 会话创建时间
 * @param updatedAt 会话更新时间
 * @param metadata 扩展元数据
 */
public record SessionSnapshot(
    String sessionId,
    String agentName,
    String modelId,
    List<Message> messages,
    Double cost,
    Tokens tokens,
    Instant createdAt,
    Instant updatedAt,
    Map<String, Object> metadata
) {
    /**
     * 从 {@link Session} 构建快照。
     *
     * @param session 会话对象
     * @return 会话快照
     */
    public static SessionSnapshot from(Session session) {
        return new SessionSnapshot(
            session.id(),
            session.agent(),
            session.model() != null ? session.model().apiId() : null,
            session.messages() != null ? List.copyOf(session.messages()) : List.of(),
            session.cost(),
            session.tokens(),
            session.createdAt(),
            session.updatedAt(),
            Map.of()
        );
    }
}
