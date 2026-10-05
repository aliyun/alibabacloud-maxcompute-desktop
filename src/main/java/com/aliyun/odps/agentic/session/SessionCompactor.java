package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.model.Message;

import java.util.List;
import java.util.function.Consumer;

/**
 * 会话上下文压缩协调器。
 *
 * <p>负责发出压缩事件、调用压缩引擎生成摘要，并在成功后将压缩结果写回消息存储。
 */
public class SessionCompactor {
    private final LLMClient llmClient;
    private final CompactionEngine compactionEngine;
    private final MessageStore messageStore;
    private final Consumer<AgentEvent> eventConsumer;

    /**
     * 创建会话压缩协调器。
     *
     * @param llmClient 用于生成摘要的 LLM 客户端
     * @param compactionEngine 上下文压缩引擎
     * @param messageStore 消息存储
     * @param eventConsumer 事件消费者
     */
    public SessionCompactor(
        LLMClient llmClient,
        CompactionEngine compactionEngine,
        MessageStore messageStore,
        Consumer<AgentEvent> eventConsumer
    ) {
        this.llmClient = llmClient;
        this.compactionEngine = compactionEngine;
        this.messageStore = messageStore;
        this.eventConsumer = eventConsumer;
    }

    /**
     * 对指定会话消息执行一次上下文压缩。
     *
     * @param sessionId 会话 ID
     * @param messages 原始消息列表
     * @param model 压缩使用的模型
     * @param reason 压缩原因
     * @param overflow 是否由上下文溢出触发
     * @return 压缩结果
     */
    public ManualCompactionResult compact(
        String sessionId,
        List<Message> messages,
        Model model,
        CompactionReason reason,
        boolean overflow
    ) {
        eventConsumer.accept(new AgentEvent.CompactionStart(sessionId, reason, overflow));
        List<Message> compacted = compactionEngine.compactWithSummary(messages, llmClient, model, eventConsumer);
        boolean changed = compacted != messages && compacted.size() < messages.size();
        Message summaryMessage = changed && !compacted.isEmpty() ? compacted.getFirst() : null;
        String summary = summaryMessage != null ? summaryMessage.getTextContent() : null;
        if (changed) {
            messageStore.clear(sessionId);
            for (Message message : compacted) {
                messageStore.updateMessage(sessionId, message);
            }
        }
        eventConsumer.accept(new AgentEvent.CompactionEnd(sessionId, reason, overflow, summary));
        return new ManualCompactionResult(summaryMessage, List.copyOf(compacted), changed, overflow, reason, summary);
    }
}
