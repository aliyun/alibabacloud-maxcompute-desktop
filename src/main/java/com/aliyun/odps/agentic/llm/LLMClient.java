package com.aliyun.odps.agentic.llm;

import java.util.function.Consumer;

/**
 * LLM 客户端接口，抽象与 LLM 提供者的通信。
 *
 * <p>实现类负责 SSE 流式传输、提供者特定的 API 格式转换，
 * 并将提供者响应统一转换为 {@link LLMEvent} 事件流。
 */
public interface LLMClient {

    /**
     * 以流式方式发起聊天补全请求，将事件推送至消费者。
     *
     * @param request       包含模型、消息、工具的 LLM 请求
     * @param eventConsumer LLM 事件消费者
     */
    void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer);

    /** Host protocol adapters may consume source messages instead of SDK provider maps. */
    default boolean requiresProviderProjection() { return true; }

    /**
     * 检查此客户端是否支持指定的提供者。
     *
     * @param providerId 提供者标识
     * @return 支持时返回 {@code true}
     */
    boolean supports(String providerId);
}
