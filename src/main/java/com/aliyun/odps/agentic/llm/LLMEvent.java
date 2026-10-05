package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * LLM 流式事件的密封接口。
 *
 * <p>该接口统一描述文本生成、推理内容、工具调用、用量统计、
 * 结束状态与提供者错误等流式传输事件。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = LLMEvent.TextStart.class, name = "text-start"),
    @JsonSubTypes.Type(value = LLMEvent.TextDelta.class, name = "text-delta"),
    @JsonSubTypes.Type(value = LLMEvent.TextEnd.class, name = "text-end"),
    @JsonSubTypes.Type(value = LLMEvent.ReasoningStart.class, name = "reasoning-start"),
    @JsonSubTypes.Type(value = LLMEvent.ReasoningDelta.class, name = "reasoning-delta"),
    @JsonSubTypes.Type(value = LLMEvent.ReasoningEnd.class, name = "reasoning-end"),
    @JsonSubTypes.Type(value = LLMEvent.ToolInputStart.class, name = "tool-input-start"),
    @JsonSubTypes.Type(value = LLMEvent.ToolInputDelta.class, name = "tool-input-delta"),
    @JsonSubTypes.Type(value = LLMEvent.ToolCall.class, name = "tool-call"),
    @JsonSubTypes.Type(value = LLMEvent.ToolResult.class, name = "tool-result"),
    @JsonSubTypes.Type(value = LLMEvent.Usage.class, name = "usage"),
    @JsonSubTypes.Type(value = LLMEvent.Finish.class, name = "finish"),
    @JsonSubTypes.Type(value = LLMEvent.ProviderError.class, name = "provider-error")
})
public sealed interface LLMEvent
    permits LLMEvent.TextStart, LLMEvent.TextDelta, LLMEvent.TextEnd,
            LLMEvent.ReasoningStart, LLMEvent.ReasoningDelta, LLMEvent.ReasoningEnd,
            LLMEvent.ToolInputStart, LLMEvent.ToolInputDelta, LLMEvent.ToolCall,
            LLMEvent.ToolResult, LLMEvent.Usage,
            LLMEvent.Finish, LLMEvent.ProviderError {

    /**
     * 文本输出开始事件。
     *
     * @param modelId 模型标识，可能为空
     */
    record TextStart(String modelId) implements LLMEvent {}

    /**
     * 文本增量事件。
     *
     * @param delta 新收到的文本片段
     */
    record TextDelta(String delta) implements LLMEvent {}

    /** 文本输出结束事件。 */
    record TextEnd() implements LLMEvent {}

    /**
     * 推理内容开始事件。
     *
     * @param id 推理片段标识
     */
    record ReasoningStart(String id) implements LLMEvent {}

    /**
     * 推理内容增量事件。
     *
     * @param delta 新收到的推理文本片段
     */
    record ReasoningDelta(String delta) implements LLMEvent {}

    /**
     * 推理内容结束事件。
     *
     * @param id        推理片段标识
     * @param signature 提供者附带的签名信息，可为空
     */
    record ReasoningEnd(String id, String signature) implements LLMEvent {}

    /**
     * 工具输入开始事件。
     *
     * @param callId 工具调用标识
     * @param tool   工具名称
     */
    record ToolInputStart(String callId, String tool) implements LLMEvent {}

    /**
     * 工具输入增量事件。
     *
     * @param delta 工具参数 JSON 增量片段
     */
    record ToolInputDelta(String delta) implements LLMEvent {}

    /**
     * 工具调用完成事件。
     *
     * @param callId 工具调用标识
     * @param tool   工具名称
     * @param input  完整聚合后的 JSON 输入参数
     */
    record ToolCall(String callId, String tool, String input) implements LLMEvent {
        /**
         * 创建一个不带输入参数的工具调用事件。
         */
        public ToolCall(String callId, String tool) {
            this(callId, tool, null);
        }
    }

    /**
     * 工具执行结果事件。
     *
     * @param callId  工具调用标识
     * @param output  工具输出内容
     * @param isError 是否为错误结果
     */
    record ToolResult(String callId, String output, boolean isError) implements LLMEvent {}

    /**
     * Token 用量更新事件。
     *
     * @param usage 用量信息
     */
    record Usage(com.aliyun.odps.agentic.llm.Usage usage) implements LLMEvent {}

    /**
     * 流结束事件。
     *
     * @param reason 停止原因
     */
    record Finish(String reason) implements LLMEvent {}

    /**
     * 提供者错误事件。
     *
     * @param error 错误信息
     */
    record ProviderError(String error) implements LLMEvent {
        /**
         * 使用错误信息和状态码构造提供者错误事件。
         */
        public ProviderError(String error, int statusCode) {
            this(error + " (status " + statusCode + ")");
        }
    }
}
