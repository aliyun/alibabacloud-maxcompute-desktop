package com.aliyun.odps.agentic.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话事件投影器。
 *
 * <p>将 {@link SessionEvent} 增量事件应用到 {@link MessageStore}，保持消息存储与事件流一致。
 */
public class Projector {
    private static final Logger log = LoggerFactory.getLogger(Projector.class);
    private final MessageStore store;

    /**
     * 使用给定消息存储创建投影器。
     *
     * @param store 消息存储
     */
    public Projector(MessageStore store) {
        this.store = store;
    }

    /**
     * 处理会话事件并更新存储状态。
     *
     * @param event 会话事件
     */
    public void handle(SessionEvent event) {
        switch (event) {
            case SessionEvent.MessageUpdated mu -> {
                store.updateMessage(mu.sessionId(), mu.message());
            }
            case SessionEvent.MessageRemoved mr -> {
                store.removeMessage(mr.sessionId(), mr.messageId());
            }
            case SessionEvent.PartUpdated pu -> {
                log.debug("Part updated: session={} message={}", pu.sessionId(), pu.messageId());
            }
            case SessionEvent.PartRemoved pr -> {
                log.debug("Part removed: session={} message={}", pr.sessionId(), pr.messageId());
            }
        }
    }
}
