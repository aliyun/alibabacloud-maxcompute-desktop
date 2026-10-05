package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;

import java.util.List;
import java.util.Optional;

/**
 * 会话消息存储接口。
 *
 * <p>负责消息的增删查改，并为不同持久化实现提供统一抽象。
 */
public interface MessageStore {
    /**
     * 更新或插入一条消息。
     *
     * @param sessionId 会话 ID
     * @param message 消息对象
     */
    void updateMessage(String sessionId, Message message);

    /**
     * 获取指定会话的全部消息。
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<Message> getMessages(String sessionId);

    /**
     * 删除指定消息。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     */
    void removeMessage(String sessionId, String messageId);

    /**
     * 清空指定会话的消息。
     *
     * @param sessionId 会话 ID
     */
    void clear(String sessionId);

    /**
     * 批量持久化消息。
     * 默认实现会逐条调用 {@link #updateMessage}。
     *
     * @param sessionId 会话 ID
     * @param messages 消息列表
     */
    default void updateMessages(String sessionId, List<Message> messages) {
        if (messages == null) return;
        for (Message message : messages) {
            updateMessage(sessionId, message);
        }
    }

    /**
     * 按消息 ID 查询单条消息。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     * @return 查询结果
     */
    default Optional<Message> getMessage(String sessionId, String messageId) {
        return getMessages(sessionId).stream()
            .filter(m -> m.id().equals(messageId))
            .findFirst();
    }
}
