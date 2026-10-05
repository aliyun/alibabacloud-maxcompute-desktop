package com.aliyun.odps.agentic.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 不可变消息记录，是运行循环中的核心数据单元。
 * 它统一表示用户消息、助手消息及其相关元数据。
 *
 * @param id              消息 ID
 * @param sessionId       所属会话 ID
 * @param role            消息角色
 * @param parentMessageId 父消息 ID
 * @param tokens          Token 使用统计
 * @param parts           消息片段列表
 * @param agent           关联代理名称
 * @param model           关联模型信息
 * @param finish          结束原因
 * @param error           错误信息
 * @param summary         压缩摘要
 * @param tools           工具权限映射
 * @param cost            成本信息
 * @param createdAt       创建时间
 */
public record Message(
    String id,
    String sessionId,
    Role role,
    String parentMessageId,
    Tokens tokens,
    List<MessagePart> parts,
    String agent,
    ModelRef model,
    String finish,
    String error,
    String summary,
    Map<String, Boolean> tools,
    Double cost,
    Instant createdAt,
    Map<String, Object> hostMetadata
) {
    /** Existing SDK callers need no host metadata. */
    public Message(String id, String sessionId, Role role, String parentMessageId,
                   Tokens tokens, List<MessagePart> parts, String agent, ModelRef model,
                   String finish, String error, String summary, Map<String, Boolean> tools,
                   Double cost, Instant createdAt) {
        this(id, sessionId, role, parentMessageId, tokens, parts, agent, model,
            finish, error, summary, tools, cost, createdAt, Map.of());
    }

    public Message {
        hostMetadata = hostMetadata == null ? Map.of() : Map.copyOf(hostMetadata);
    }

    /**
     * 使用常见默认值创建消息。
     *
     * @param id        消息 ID
     * @param sessionId 所属会话 ID
     * @param role      消息角色
     * @param parts     消息片段列表
     */
    public Message(String id, String sessionId, Role role, List<MessagePart> parts) {
        this(id, sessionId, role, null, null, parts, null, null, null, null, null, null, null,
            Instant.now(), Map.of());
    }

    /**
     * 判断当前消息是否已经被上下文压缩。
     *
     * @return 存在摘要时返回 true
     */
    @JsonIgnore
    public boolean isCompacted() {
        return summary != null;
    }

    /**
     * 提取消息中所有文本片段并拼接成纯文本内容。
     *
     * @return 文本内容
     */
    @JsonIgnore
    public String getTextContent() {
        if (parts == null) return "";
        return parts.stream()
            .filter(p -> p instanceof MessagePart.TextPart)
            .map(p -> ((MessagePart.TextPart) p).text())
            .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
            .toString();
    }

    /**
     * 判断当前消息是否包含待执行的工具调用。
     *
     * @return 存在待执行工具调用时返回 true
     */
    @JsonIgnore
    public boolean hasPendingToolCalls() {
        if (parts == null) return false;
        return parts.stream()
            .filter(p -> p instanceof MessagePart.ToolPart)
            .map(p -> (MessagePart.ToolPart) p)
            .anyMatch(tp -> tp.state() instanceof ToolCallState.Pending || tp.state() instanceof ToolCallState.Running);
    }

    /**
     * 获取消息中的全部工具片段。
     *
     * @return 工具片段列表
     */
    @JsonIgnore
    public List<MessagePart.ToolPart> getToolParts() {
        if (parts == null) return List.of();
        return parts.stream()
            .filter(p -> p instanceof MessagePart.ToolPart)
            .map(p -> (MessagePart.ToolPart) p)
            .toList();
    }

    /**
     * 模型引用信息。
     *
     * @param providerID 提供者 ID
     * @param modelID    模型 ID
     * @param variant    模型变体
     */
    public record ModelRef(
        String providerID,
        String modelID,
        String variant
    ) {}

    /**
     * 单条消息的 Token 使用统计。
     *
     * @param input  输入 Token 数
     * @param output 输出 Token 数
     * @param cache  缓存 Token 数
     */
    public record Tokens(
        int input,
        int output,
        int cache
    ) {}
}
