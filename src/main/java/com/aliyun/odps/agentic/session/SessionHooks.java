package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.tool.ToolResult;

/**
 * 会话生命周期钩子接口。
 *
 * <p>调用方可按需覆写关键阶段回调，以接入审计、监控、日志或自定义控制逻辑。
 */
public interface SessionHooks {

    /**
     * 在工具调用执行前触发。
     *
     * @param sessionId 会话 ID
     * @param tool 工具名称
     * @param callId 工具调用 ID
     * @param input 工具原始输入 JSON
     */
    default void beforeToolCall(String sessionId, String tool, String callId, String input) {}

    /**
     * 在工具调用完成后触发。
     *
     * @param sessionId 会话 ID
     * @param tool 工具名称
     * @param callId 工具调用 ID
     * @param result 工具执行结果
     */
    default void afterToolCall(String sessionId, String tool, String callId, ToolResult result) {}

    /**
     * 在发起 LLM 调用前触发。
     *
     * @param sessionId 会话 ID
     * @param step 当前步数
     */
    default void beforeLLMCall(String sessionId, int step) {}

    /**
     * 在 LLM 调用完成后触发。
     *
     * @param sessionId 会话 ID
     * @param step 当前步数
     * @param assistantMessage 完成的助手消息
     */
    default void afterLLMCall(String sessionId, int step, Message assistantMessage) {}

    /**
     * 在消息写入存储后触发。
     *
     * @param sessionId 会话 ID
     * @param message 已持久化消息
     */
    default void onMessagePersisted(String sessionId, Message message) {}

    /**
     * 在会话被中止时触发。
     *
     * @param sessionId 会话 ID
     */
    default void onAbort(String sessionId) {}

    /**
     * 在成本与 Token 用量更新后触发。
     *
     * @param sessionId 会话 ID
     * @param stepCost 当前步的成本
     * @param totalCost 累计成本
     * @param tokens 当前步的 Token 用量
     */
    default void onCostUpdate(String sessionId, double stepCost, double totalCost,
                              com.aliyun.odps.agentic.model.Tokens tokens) {}
}
