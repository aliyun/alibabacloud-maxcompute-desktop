package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;

/**
 * 表示会话消息存储层的领域事件。
 *
 * <p>这些事件用于描述消息或消息片段的增删改变化，便于投影和持久化处理。
 */
public sealed interface SessionEvent {
    /**
     * 表示一条消息已更新。
     *
     * @param sessionId 会话 ID
     * @param message 更新后的消息
     */
    record MessageUpdated(String sessionId, Message message) implements SessionEvent {}

    /**
     * 表示一条消息中的片段已更新。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     * @param part 更新后的消息片段
     */
    record PartUpdated(String sessionId, String messageId, MessagePart part) implements SessionEvent {}

    /**
     * 表示一条消息已删除。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     */
    record MessageRemoved(String sessionId, String messageId) implements SessionEvent {}

    /**
     * 表示一条消息中的片段已删除。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     * @param partId 片段 ID
     */
    record PartRemoved(String sessionId, String messageId, String partId) implements SessionEvent {}
}
