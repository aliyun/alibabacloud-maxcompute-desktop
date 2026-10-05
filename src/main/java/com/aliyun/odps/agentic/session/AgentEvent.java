package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.SessionStatus;
import com.aliyun.odps.agentic.model.Tokens;
import com.aliyun.odps.agentic.permission.PermissionReply;
import com.aliyun.odps.agentic.permission.PermissionRequest;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * 代理运行循环在执行过程中发出的事件集合。
 *
 * <p>涵盖文本流、推理流、工具调用、步骤生命周期、上下文压缩、权限和成本等方面的通知。
 */
public sealed interface AgentEvent
    permits AgentEvent.TextDelta, AgentEvent.TextEnd,
            AgentEvent.ReasoningStart, AgentEvent.ReasoningDelta, AgentEvent.ReasoningEnd,
            AgentEvent.ToolCallStarted, AgentEvent.ToolCallCompleted, AgentEvent.ToolCallFailed, AgentEvent.ToolCallProgress,
            AgentEvent.StepStart, AgentEvent.StepFinish,
            AgentEvent.CompactionStart, AgentEvent.CompactionEnd,
            AgentEvent.StatusUpdate, AgentEvent.LlmStart,
            AgentEvent.TitleGenerated,
            AgentEvent.PermissionAsked, AgentEvent.PermissionReplied,
            AgentEvent.Finished, AgentEvent.Suspended, AgentEvent.Error,
            AgentEvent.SessionAborted, AgentEvent.BeforeToolCall, AgentEvent.AfterToolCall,
            AgentEvent.BeforeLLMCall, AgentEvent.AfterLLMCall,
            AgentEvent.MessagePersisted, AgentEvent.HostToolResultSettled,
            AgentEvent.UndoCompleted, AgentEvent.CostUpdate,
            AgentEvent.PromptPrepared, AgentEvent.Retrying, AgentEvent.DoomLoopDetected {

    /** 模型输出文本增量。 */
    record TextDelta(String delta) implements AgentEvent {}
    /** 模型输出文本流结束。 */
    record TextEnd() implements AgentEvent {}

    /** 推理流开始。 */
    record ReasoningStart(String id) implements AgentEvent {}
    /** 推理文本增量。 */
    record ReasoningDelta(String id, String delta) implements AgentEvent {}
    /** 推理流结束。 */
    record ReasoningEnd(String id, String text) implements AgentEvent {}

    /** 工具调用开始。 */
    record ToolCallStarted(String tool, String callId, String argsJson) implements AgentEvent {
        public ToolCallStarted(String tool, String callId) { this(tool, callId, null); }
    }
    /** 工具调用完成。 */
    record ToolCallCompleted(String tool, String callId, String title, String output) implements AgentEvent {}
    /** 工具调用失败。 */
    record ToolCallFailed(String tool, String callId, String error) implements AgentEvent {}
    /** 工具调用进度更新。 */
    record ToolCallProgress(String tool, String callId, String title, Map<String, Object> metadata) implements AgentEvent {}

    /** 步骤开始。 */
    record StepStart(String sessionId, int step) implements AgentEvent {}
    /** 步骤结束。 */
    record StepFinish(String sessionId, int step) implements AgentEvent {}

    /** 上下文压缩开始。 */
    record CompactionStart(String sessionId, CompactionReason reason, boolean overflow) implements AgentEvent {}
    /** 上下文压缩结束。 */
    record CompactionEnd(String sessionId, CompactionReason reason, boolean overflow, String summary) implements AgentEvent {}

    /** 会话状态变更。 */
    record StatusUpdate(String sessionId, SessionStatus status) implements AgentEvent {}
    /** LLM 调用开始标记。 */
    record LlmStart(String sessionId, int step) implements AgentEvent {}

    /** 会话运行完成。 */
    record Finished(String reason, int totalSteps) implements AgentEvent {}
    /** Run released its worker and may be resumed after external state changes. */
    record Suspended(String sessionId, String reason, int totalSteps) implements AgentEvent {}
    /** 运行过程中发生错误。 */
    record Error(String message, Throwable cause) implements AgentEvent {}
    /** 会话标题生成完成。 */
    record TitleGenerated(String sessionId, String title) implements AgentEvent {}

    /** 发起权限请求。 */
    record PermissionAsked(PermissionRequest request) implements AgentEvent {}
    /** 权限请求已回复。 */
    record PermissionReplied(String requestId, PermissionReply reply) implements AgentEvent {}

    // ── 会话管理增强事件 ──

    /** 会话被中止。 */
    record SessionAborted(String sessionId) implements AgentEvent {}

    /** 工具调用执行前事件。 */
    record BeforeToolCall(String sessionId, String tool, String callId, String input) implements AgentEvent {}

    /** 工具调用完成后事件。 */
    record AfterToolCall(String sessionId, String tool, String callId, ToolResult result) implements AgentEvent {}

    /**
     * LLM 调用前发出的提示词审计快照。
     * 仅用于审计和回放，不构成会话记忆。
     */
    record PromptPrepared(PromptSnapshot snapshot) implements AgentEvent {}

    /** LLM 调用前事件。 */
    record BeforeLLMCall(String sessionId, int step) implements AgentEvent {}

    /** LLM 调用完成后事件。 */
    record AfterLLMCall(String sessionId, int step, Message assistantMessage) implements AgentEvent {}

    /** 消息已持久化到存储中。 */
    record MessagePersisted(String sessionId, Message message) implements AgentEvent {}

    /** Host tool completed before its batch is committed in call order. */
    record HostToolResultSettled(MessagePart.ToolResultPart result, int batchIndex)
        implements AgentEvent {}

    /** 最后一轮助手消息已被撤销。 */
    record UndoCompleted(String sessionId, int messagesRemoved) implements AgentEvent {}

    /** 成本与 Token 用量更新。 */
    record CostUpdate(String sessionId, double stepCost, double totalCost, Tokens tokens) implements AgentEvent {}

    /**
     * LLM 调用失败后即将重试（0.4.0）。
     *
     * <p>让前端能显示「重试中」而非黑屏卡住。{@code attempt} 从 1 开始（第几次重试），
     * {@code delayMs} 为退避毫秒数，{@code reason} 为失败原因。
     */
    record Retrying(String sessionId, int attempt, long delayMs, String reason) implements AgentEvent {}

    /**
     * 检测到重复循环（doom loop）（0.4.0）。
     *
     * <p>此前命中后直接静默 break，前端只看到「无理由停住」。现在发出本事件，
     * 并在可恢复时给模型一次纠偏提示。{@code tool} 为被重复调用的工具名（可为 {@code null}）。
     */
    record DoomLoopDetected(String sessionId, String tool) implements AgentEvent {}

    // ── 便捷工厂方法 ──

    /** 创建步骤开始事件。 */
    static StepStart stepStart(String sessionId, int step) { return new StepStart(sessionId, step); }
    /** 创建步骤结束事件。 */
    static StepFinish stepFinish(String sessionId, int step) { return new StepFinish(sessionId, step); }
    /** 创建状态变更事件。 */
    static StatusUpdate statusUpdate(String sessionId, SessionStatus status) { return new StatusUpdate(sessionId, status); }
    /** 创建 LLM 调用开始事件。 */
    static LlmStart llmStart(String sessionId, int step) { return new LlmStart(sessionId, step); }
}
