package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存的 {@link MessageStore} 实现。
 *
 * <p>适用于测试或短生命周期场景，不提供进程外持久化能力。
 */
public class InMemoryMessageStore implements MessageStore {
    private final ConcurrentHashMap<String, List<Message>> store = new ConcurrentHashMap<>();

    /**
     * 更新或插入一条消息；按创建时间排序，同时间保留首次写入顺序。
     *
     * @param sessionId 会话 ID
     * @param message 消息对象
     */
    @Override
    public void updateMessage(String sessionId, Message message) {
        store.computeIfAbsent(sessionId, k -> Collections.synchronizedList(new ArrayList<>()));
        List<Message> msgs = store.get(sessionId);
        synchronized (msgs) {
            for (int i = 0; i < msgs.size(); i++) {
                if (msgs.get(i).id().equals(message.id())) {
                    msgs.set(i, message);
                    msgs.sort(Comparator.comparing(Message::createdAt));
                    return;
                }
            }
            msgs.add(message);
            msgs.sort(Comparator.comparing(Message::createdAt));
        }
    }

    /**
     * 获取指定会话的全部消息副本。
     *
     * @param sessionId 会话 ID
     * @return 按创建时间及首次写入顺序排列的消息列表
     */
    @Override
    public List<Message> getMessages(String sessionId) {
        List<Message> msgs = store.get(sessionId);
        if (msgs == null) return List.of();
        synchronized (msgs) {
            return new ArrayList<>(msgs);
        }
    }

    /**
     * 删除指定消息。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     */
    @Override
    public void removeMessage(String sessionId, String messageId) {
        List<Message> msgs = store.get(sessionId);
        if (msgs != null) {
            synchronized (msgs) {
                msgs.removeIf(m -> m.id().equals(messageId));
            }
        }
    }

    /**
     * 清空指定会话的全部消息。
     *
     * @param sessionId 会话 ID
     */
    @Override
    public void clear(String sessionId) {
        store.remove(sessionId);
    }
}
