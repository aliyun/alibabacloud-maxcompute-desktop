package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.Tokens;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolRegistry;
import com.aliyun.odps.agentic.tool.ToolResult;
import com.aliyun.odps.agentic.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 流式处理器。
 *
 * <p>该类负责将 LLM 事件流组装为助手消息，并执行消息中包含的工具调用。
 * 核心流程：收集文本/推理/工具调用增量 -> 构建消息 -> 执行工具调用 -> 检测重复循环。
 */
public class StreamProcessor {

    private static final Logger log = LoggerFactory.getLogger(StreamProcessor.class);
    private static final int DOOM_LOOP_THRESHOLD = 3;
    // 复用同一 ObjectMapper（线程安全用于读），避免每次工具调用重建。
    private static final com.fasterxml.jackson.databind.ObjectMapper ARG_MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final MessageStore messageStore;

    /**
     * 创建不带消息存储的流式处理器。
     *
     * @param toolRegistry 工具注册表
     */
    public StreamProcessor(ToolRegistry toolRegistry) {
        this(toolRegistry, null);
    }

    /**
     * 创建带消息存储的流式处理器。
     *
     * @param toolRegistry 工具注册表
     * @param messageStore 消息存储
     */
    public StreamProcessor(ToolRegistry toolRegistry, MessageStore messageStore) {
        this.toolRegistry = toolRegistry;
        this.messageStore = messageStore;
    }

    // ── 阶段一：事件流组装为助手消息 ──

    /**
     * 将 LLM 事件流处理为一条完整的助手消息。
     *
     * @param events 事件列表
     * @param sessionId 会话 ID
     * @param agentName 代理名称
     * @param eventConsumer 中间事件消费者
     * @return 构建完成的助手消息
     */
    public Message processEvents(List<LLMEvent> events, String sessionId, String agentName,
                                  Consumer<AgentEvent> eventConsumer) {
        return processEvents(events, sessionId, agentName, eventConsumer,
            UUID.randomUUID().toString(), Instant.now());
    }

    /**
     * 将 LLM 事件流处理为一条完整的助手消息，使用调用方预先确定的消息 ID 和创建时间。
     *
     * <p>与 OpenCode 对齐：助手消息的身份在 LLM 调用开始前确定，使调用期间
     * steer 注入的用户消息在按时间排序的存储中位于助手消息之后，
     * 运行循环的退出判断因此能感知到「还有未响应的用户消息」。
     *
     * @param events 事件列表
     * @param sessionId 会话 ID
     * @param agentName 代理名称
     * @param eventConsumer 中间事件消费者
     * @param messageId 预先分配的消息 ID
     * @param createdAt 预先确定的创建时间
     * @return 构建完成的助手消息
     */
    public Message processEvents(List<LLMEvent> events, String sessionId, String agentName,
                                   Consumer<AgentEvent> eventConsumer, String messageId, Instant createdAt) {
        return processEvents(events, sessionId, agentName, eventConsumer, messageId, createdAt, null);
    }

    /**
     * 将 LLM 事件流处理为一条完整的助手消息，并携带模型用于按真实单价计费。
     *
     * @param events 事件列表
     * @param sessionId 会话 ID
     * @param agentName 代理名称
     * @param eventConsumer 中间事件消费者
     * @param messageId 预先分配的消息 ID
     * @param createdAt 预先确定的创建时间
     * @param model 模型定义（用于成本计算；可为 {@code null}，此时用默认费率）
     * @return 构建完成的助手消息
     */
    public Message processEvents(List<LLMEvent> events, String sessionId, String agentName,
                                   Consumer<AgentEvent> eventConsumer, String messageId, Instant createdAt,
                                   com.aliyun.odps.agentic.llm.Model model) {
        return processEvents(events, sessionId, agentName, eventConsumer,
            messageId, createdAt, model, true);
    }

    /** Defer persistence when the run policy must inspect a candidate tool batch first. */
    public Message processEvents(List<LLMEvent> events, String sessionId, String agentName,
                                   Consumer<AgentEvent> eventConsumer, String messageId, Instant createdAt,
                                   com.aliyun.odps.agentic.llm.Model model, boolean persist) {
        StringBuilder textBuffer = new StringBuilder();
        StringBuilder reasoningBuffer = new StringBuilder();
        String reasoningSignature = null;
        List<MessagePart.ToolCallPart> toolCalls = new ArrayList<>();
        String currentToolCallId = null;
        String currentToolName = null;
        StringBuilder currentToolInput = new StringBuilder();
        String finishReason = null;
        // 0.4.0：记录错误信息，使 RunLoop 能据此识别上下文溢出并触发压缩恢复；
        // 此前错误只进正文（[Error: ...]）而 error 字段恒为 null，溢出恢复永远打不着。
        String errorMessage = null;
        com.aliyun.odps.agentic.llm.Usage usage = null;

        for (LLMEvent event : events) {
            switch (event) {
                case LLMEvent.TextStart ts -> {
                    // Anthropic 可能发送多个文本块，不清空缓冲区。
                }

                case LLMEvent.TextDelta td -> {
                    textBuffer.append(td.delta());
                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.TextDelta(td.delta()));
                    }
                }

                case LLMEvent.TextEnd te -> {
                }

                case LLMEvent.ReasoningStart rs -> {
                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.ReasoningStart(rs.id()));
                    }
                }

                case LLMEvent.ReasoningDelta rd -> {
                    reasoningBuffer.append(rd.delta());
                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.ReasoningDelta("", rd.delta()));
                    }
                }

                case LLMEvent.ReasoningEnd re -> {
                    reasoningSignature = re.signature();
                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.ReasoningEnd(re.id(), re.signature()));
                    }
                }

                case LLMEvent.ToolInputStart tis -> {
                    currentToolCallId = tis.callId();
                    currentToolName = tis.tool();
                    currentToolInput.setLength(0);
                }

                case LLMEvent.ToolInputDelta tid -> {
                    currentToolInput.append(tid.delta());
                }

                case LLMEvent.ToolCall tc -> {
                    // A provider may stream multiple tool calls in parallel. In that case
                    // currentToolCallId/currentToolName point at the most recently started call,
                    // not necessarily the ToolCall event currently being finalized. Prefer the
                    // identity carried by the completed event and use the streaming accumulator
                    // only as a fallback for providers (such as Anthropic) that omit final input.
                    String finalCallId = tc.callId() != null && !tc.callId().isBlank()
                        ? tc.callId() : currentToolCallId;
                    String finalToolName = tc.tool() != null && !tc.tool().isBlank()
                        ? tc.tool() : currentToolName;
                    // 优先使用 ToolCall 事件携带的完整参数，
                    // 否则回退到处理器级别累积的输入。
                    String finalInput;
                    if (tc.input() != null && !tc.input().isEmpty()) {
                        finalInput = tc.input();
                    } else if (Objects.equals(finalCallId, currentToolCallId)
                            && currentToolInput.length() > 0) {
                        finalInput = currentToolInput.toString();
                    } else {
                        finalInput = "{}";
                    }
                    toolCalls.add(new MessagePart.ToolCallPart(
                        finalCallId,
                        finalToolName,
                        finalInput
                    ));
                    if (Objects.equals(finalCallId, currentToolCallId)) {
                        currentToolCallId = null;
                        currentToolName = null;
                        currentToolInput.setLength(0);
                    }

                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.ToolCallStarted(
                            toolCalls.getLast().name(),
                            toolCalls.getLast().callID(),
                            toolCalls.getLast().input()
                        ));
                    }
                }

                case LLMEvent.Finish f -> {
                    finishReason = f.reason();
                }

                case LLMEvent.Usage u -> {
                    // 合并使用量——Anthropic 将输入和输出 Token 拆分为两次使用量事件发送。
                    if (usage == null) {
                        usage = u.usage();
                    } else {
                        usage = new com.aliyun.odps.agentic.llm.Usage(
                            Math.max(usage.inputTokens(), u.usage().inputTokens()),
                            Math.max(usage.outputTokens(), u.usage().outputTokens()),
                            Math.max(usage.cacheReadInputTokens(), u.usage().cacheReadInputTokens()),
                            Math.max(usage.cacheCreationInputTokens(), u.usage().cacheCreationInputTokens()),
                            Math.max(usage.reasoningTokens(), u.usage().reasoningTokens())
                        );
                    }
                }

                case LLMEvent.ProviderError pe -> {
                    textBuffer.append("[Error: ").append(pe.error()).append("]");
                    finishReason = "error";
                    errorMessage = pe.error();
                    if (eventConsumer != null) {
                        eventConsumer.accept(new AgentEvent.Error(pe.error(), null));
                    }
                }

                default -> {}
            }
        }

        // 构建消息片段
        List<MessagePart> parts = new ArrayList<>();

        if (!reasoningBuffer.isEmpty()) {
            parts.add(new MessagePart.ReasoningPart(reasoningBuffer.toString(), null,
                reasoningSignature));
        }

        if (!textBuffer.isEmpty()) {
            parts.add(new MessagePart.TextPart(textBuffer.toString()));
        }

        parts.addAll(toolCalls);

        if (parts.isEmpty()) {
            parts.add(new MessagePart.TextPart(""));
        }

        // 构建 Token 信息
        Message.Tokens tokens = null;
        if (usage != null) {
            tokens = new Message.Tokens(
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cacheReadInputTokens() + usage.cacheCreationInputTokens()
            );
        }

        double cost = 0.0;
        if (usage != null) {
            cost = calculateCost(usage, model);
        }

        Message assistantMsg = new Message(
            messageId,
            sessionId,
            Role.ASSISTANT,
            null,
            tokens,
            parts,
            agentName,
            null,
            finishReason,
            errorMessage,
            null,
            null,
            cost,
            createdAt
        );

        // 持久化完成的助手消息
        if (persist && messageStore != null) {
            messageStore.updateMessage(sessionId, assistantMsg);
            if (eventConsumer != null) {
                eventConsumer.accept(new AgentEvent.MessagePersisted(sessionId, assistantMsg));
            }
        }

        return assistantMsg;
    }

    /**
     * 从事件列表中提取合并后的使用量信息。
     *
     * @param events LLM 事件列表
     * @return 合并使用量；无使用量事件则返回 {@code null}
     */
    public com.aliyun.odps.agentic.llm.Usage extractUsage(List<LLMEvent> events) {
        com.aliyun.odps.agentic.llm.Usage usage = null;
        for (LLMEvent event : events) {
            if (event instanceof LLMEvent.Usage u) {
                if (usage == null) {
                    usage = u.usage();
                } else {
                    // 0.4.0：此前用四参兼容构造合并，reasoningTokens 在 fromUsage 之前就被归零，
                    // 导致 b9df2ef 修复的 reasoning 记账在最后一跳丢失。改用五参构造保留真值。
                    usage = new com.aliyun.odps.agentic.llm.Usage(
                        Math.max(usage.inputTokens(), u.usage().inputTokens()),
                        Math.max(usage.outputTokens(), u.usage().outputTokens()),
                        Math.max(usage.cacheReadInputTokens(), u.usage().cacheReadInputTokens()),
                        Math.max(usage.cacheCreationInputTokens(), u.usage().cacheCreationInputTokens()),
                        Math.max(usage.reasoningTokens(), u.usage().reasoningTokens())
                    );
                }
            }
        }
        return usage;
    }

    // ── 阶段二：工具调用执行 ──

    /**
     * 使用共享上下文执行助手消息中的工具调用。
     *
     * @param assistantMessage 包含工具调用的助手消息
     * @param context 工具执行上下文
     * @return 工具结果列表
     */
    public List<MessagePart.ToolResultPart> executeToolCalls(Message assistantMessage,
                                                              ToolContext context) {
        return executeToolCalls(assistantMessage, (toolName, callId) -> context);
    }

    /**
     * 使用每调用独立上下文工厂执行工具调用。
     *
     * @param assistantMessage 包含工具调用的助手消息
     * @param contextFactory 为每对 (工具名, 调用 ID) 创建上下文的工厂
     * @return 工具结果列表
     */
    public List<MessagePart.ToolResultPart> executeToolCalls(Message assistantMessage,
                                                              java.util.function.BiFunction<String, String, ToolContext> contextFactory) {
        return executeToolCalls(assistantMessage, contextFactory, null, null, null);
    }

    /**
     * 执行工具调用，支持中止检查和生命周期钩子。
     *
     * @param assistantMessage 包含工具调用的助手消息
     * @param contextFactory 为每对 (工具名, 调用 ID) 创建上下文的工厂
     * @param abortCheck 返回 {@code true} 表示会话已中止的检查器（可为 {@code null}）
     * @param hooks 生命周期钩子（可为 {@code null}）
     * @return 工具结果列表
     */
    public List<MessagePart.ToolResultPart> executeToolCalls(
            Message assistantMessage,
            java.util.function.BiFunction<String, String, ToolContext> contextFactory,
            java.util.function.BooleanSupplier abortCheck,
            SessionHooks hooks) {
        return executeToolCalls(assistantMessage, contextFactory, abortCheck, hooks, null);
    }

    /**
     * 执行工具调用，并优先从本次运行的 scoped tools 解析工具。
     * 这让同一 HarnessEngine 上的并发 Agent 可以安全使用同名、不同上下文的工具实现。
     */
    public List<MessagePart.ToolResultPart> executeToolCalls(
            Message assistantMessage,
            java.util.function.BiFunction<String, String, ToolContext> contextFactory,
            java.util.function.BooleanSupplier abortCheck,
            SessionHooks hooks,
            Map<String, ToolDef> scopedTools) {
        // 收集本轮全部工具调用（保持消息中的声明顺序）。
        List<MessagePart.ToolCallPart> calls = new ArrayList<>();
        for (MessagePart part : assistantMessage.parts()) {
            if (part instanceof MessagePart.ToolCallPart tcp) {
                calls.add(tcp);
            }
        }

        // 结果按调用索引落位，保证与声明顺序一致（即便并行执行）。
        MessagePart.ToolResultPart[] ordered = new MessagePart.ToolResultPart[calls.size()];

        int i = 0;
        while (i < calls.size()) {
            // 终止检查（逐段进行，段内并行任务启动前也会再查一次）。
            if (abortCheck != null && abortCheck.getAsBoolean()) {
                for (int k = i; k < calls.size(); k++) {
                    MessagePart.ToolCallPart tcp = calls.get(k);
                    log.info("Tool execution aborted before {}", tcp.name());
                    ordered[k] = new MessagePart.ToolResultPart(
                        tcp.callID(), tcp.name(), "Tool execution aborted", true);
                }
                break;
            }

            // 划分一段连续的只读调用；只读段可并行，其余（写工具）保持串行。
            int j = i;
            if (isReadOnly(calls.get(i), scopedTools)) {
                while (j < calls.size() && isReadOnly(calls.get(j), scopedTools)) {
                    j++;
                }
            } else {
                j = i + 1; // 单个写工具，串行
            }

            if (j - i == 1) {
                // 单调用（或写工具）：串行执行
                ordered[i] = executeOne(calls.get(i), assistantMessage, contextFactory, abortCheck, hooks, scopedTools);
            } else {
                // 连续多个只读调用：虚拟线程并行执行，按索引回收结果。
                // 整个批次共用一个 executor 并随用随关，不再为每个任务新建。
                int from = i, to = j;
                try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                    List<java.util.concurrent.CompletableFuture<Void>> futures = new ArrayList<>();
                    for (int k = from; k < to; k++) {
                        final int idx = k;
                        futures.add(java.util.concurrent.CompletableFuture.runAsync(() ->
                            ordered[idx] = executeOne(calls.get(idx), assistantMessage, contextFactory,
                                abortCheck, hooks, scopedTools),
                            executor));
                    }
                    for (var f : futures) {
                        try {
                            f.join();
                        } catch (Exception e) {
                            // executeOne 内部已把异常封装为 ToolResult.error，这里不应到达
                            log.warn("Parallel read-only tool execution join failed: {}", e.getMessage());
                        }
                    }
                }
            }

            i = j; // 前进到下一段——漏掉这句会让 i 停在原地，整段死循环（0.4.0 自审发现并修复）
        }

        List<MessagePart.ToolResultPart> results = new ArrayList<>(ordered.length);
        for (MessagePart.ToolResultPart r : ordered) {
            if (r != null) results.add(r);
        }
        return results;
    }

    /** 判断工具是否只读（可并行）。解析失败（未知工具）保守视为有副作用。 */
    private boolean isReadOnly(MessagePart.ToolCallPart tcp, Map<String, ToolDef> scopedTools) {
        ToolDef tool = scopedTools != null ? scopedTools.get(tcp.name()) : null;
        if (tool == null) tool = toolRegistry.resolve(tcp.name());
        return tool != null && tool.isReadOnly();
    }

    /**
     * 执行单个工具调用（含中止检查与前后钩子），并把输出组装成 {@link MessagePart.ToolResultPart}。
     * 抽取此方法以便只读段并行复用。
     */
    private MessagePart.ToolResultPart executeOne(
            MessagePart.ToolCallPart tcp,
            Message assistantMessage,
            java.util.function.BiFunction<String, String, ToolContext> contextFactory,
            java.util.function.BooleanSupplier abortCheck,
            SessionHooks hooks,
            Map<String, ToolDef> scopedTools) {
        if (abortCheck != null && abortCheck.getAsBoolean()) {
            log.info("Tool execution aborted before {}", tcp.name());
            return new MessagePart.ToolResultPart(tcp.callID(), tcp.name(), "Tool execution aborted", true);
        }

        if (hooks != null) {
            hooks.beforeToolCall(assistantMessage.sessionId(), tcp.name(), tcp.callID(), tcp.input());
        }

        ToolContext ctx = contextFactory.apply(tcp.name(), tcp.callID());
        ToolResult result = executeTool(tcp, ctx, scopedTools);

        if (hooks != null) {
            hooks.afterToolCall(assistantMessage.sessionId(), tcp.name(), tcp.callID(), result);
        }

        String output = result.output() != null ? result.output() : "";
        if (result.title() != null && !result.title().isEmpty()) {
            output = result.title() + "\n" + output;
        }
        return new MessagePart.ToolResultPart(tcp.callID(), tcp.name(), output,
            result.isError(), result.metadata());
    }

    /**
     * 执行单个工具调用。
     */
    private ToolResult executeTool(MessagePart.ToolCallPart tcp, ToolContext context,
                                   Map<String, ToolDef> scopedTools) {
        String toolName = tcp.name();
        String input = tcp.input();

        ToolDef tool = scopedTools != null ? scopedTools.get(toolName) : null;
        // P1(复核 2026-09-28):执行层硬白名单——scopedTools 非空时【禁止】回退
        // global registry(RunLoop 已过滤局部 toolCalls,但原始 assistantMsg
        // 仍传到这里;scoped 中找不到的禁用调用经 registry 回退仍可执行)。
        // 回退只在 scopedTools 为 null(全局无限制模式)时才允许。
        // S3 收口(2026-09-28 外部审计):空 map 不再回退——「白名单配置了但零
        // 命中」(拼写错/全排除)曾借 registry 回退把 14 个 builtin(含 shell/
        // write)全开。空集语义二分:AgentDef.getIncludedTools 的空=「未配置,
        // 全放行」由 RunLoop 在装配层消化(装满 agent 全部工具);装配后仍为
        // 空 map = 配置过但零命中 = 拒绝全部。7bdfa6e3b 修过的默认重载已传
        // null(不限制),不受影响。
        if (tool == null && scopedTools == null) {
            tool = toolRegistry.resolve(toolName);
        }
        if (tool == null) {
            if (scopedTools != null) {
                return ToolResult.error("Tool '" + toolName + "' not in the allowed tool set for this session"
                    + (scopedTools.isEmpty() ? " (tool whitelist matched zero tools — check includedTools spelling)" : ""));
            }
            return ToolResult.error("Unknown tool: " + toolName);
        }

        try {
            com.fasterxml.jackson.databind.JsonNode args = ARG_MAPPER.readTree(input != null ? input : "{}");
            log.info("Tool {} raw args: {}", toolName, args);
            return tool.execute(args, context);
        } catch (NullPointerException e) {
            log.warn("NPE in tool {}: {}", toolName, e.getMessage(), e);
            return ToolResult.error("Tool '" + toolName + "' received incomplete arguments. A required parameter is missing.");
        } catch (Exception e) {
            log.warn("Tool {} execution failed: {}", toolName, e.getMessage(), e);
            return ToolResult.error("Tool execution failed: " + e.getMessage());
        }
    }

    // ── 重复循环检测 ──

    /**
     * 检测消息序列中是否出现重复循环（相同工具以相同参数被连续调用）。
     *
     * <p>0.4.0 修复：改为"按角色分组、比较最近 {@code windowSize} 条同角色消息"。
     * 旧实现按固定步长比较 {@code messages.get(i)} 与 {@code messages.get(i + windowSize)}，
     * 但真实循环里消息是 ASSISTANT/USER 交替的，同一条工具调用序列间隔为 2，
     * 与奇数窗口（3）错位——{@code role() } 不一致被 continue 跳过，导致 doom loop
     * 在主循环里几乎永远不触发（单元测试用的是连续 ASSISTANT 消息才碰巧通过）。
     *
     * @param messages 消息列表
     * @param windowSize 需要连续重复的助手消息条数（如 3 = 连续 3 次相同工具调用）
     * @return 检测到重复循环返回 {@code true}
     */
    public boolean isDoomLoop(List<Message> messages, int windowSize) {
        // 只看助手消息里的工具调用序列——doom loop 是"模型反复用相同参数调同一工具"。
        List<Message> assistants = messages.stream()
            .filter(m -> m.role() == Role.ASSISTANT)
            .toList();
        if (assistants.size() < windowSize) return false;

        // 最近 windowSize 条助手消息，每条都必须恰好是同一组工具调用（同名同参）。
        List<Message> recent = assistants.subList(assistants.size() - windowSize, assistants.size());
        List<MessagePart.ToolCallPart> reference = toolCallsFrom(recent.get(0));
        if (reference.isEmpty()) return false;

        for (Message m : recent) {
            List<MessagePart.ToolCallPart> tc = toolCallsFrom(m);
            if (tc.size() != reference.size()) return false;
            for (int j = 0; j < tc.size(); j++) {
                if (!tc.get(j).name().equals(reference.get(j).name())
                    || !Objects.equals(tc.get(j).input(), reference.get(j).input())) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<MessagePart.ToolCallPart> toolCallsFrom(Message msg) {
        return msg.parts().stream()
            .filter(p -> p instanceof MessagePart.ToolCallPart)
            .map(p -> (MessagePart.ToolCallPart) p)
            .toList();
    }

    // ── 步骤事件 ──

    /**
     * 发出步骤开始事件。
     */
    public void emitStepStart(String sessionId, String messageId, int step, Consumer<AgentEvent> eventConsumer) {
        if (eventConsumer != null) {
            eventConsumer.accept(AgentEvent.stepStart(sessionId, step));
        }
    }

    /**
     * 发出步骤结束事件。
     */
    public void emitStepFinish(String sessionId, String messageId, int step, String reason,
                               Message.Tokens tokens, double cost, Consumer<AgentEvent> eventConsumer) {
        if (eventConsumer != null) {
            eventConsumer.accept(AgentEvent.stepFinish(sessionId, step));
        }
    }

    // ── 成本计算 ──

    /**
     * 根据 Token 使用量计算调用成本。
     *
     * <p>0.4.0 起按模型实际单价计费：优先从 {@code ModelCatalog} 按
     * {@code model.apiId()} 查价；查不到时回退到 Sonnet 档默认费率并打一次警告——
     * 此前无论跑什么模型都硬编码按 Sonnet（3.0/15.0）计价，跑 qwen/DashScope 时
     * 账面数字是错的。
     *
     * @param usage Token 使用量
     * @param model 模型定义；为 {@code null} 或目录查不到时使用默认费率
     * @return 估算成本（美元）
     */
    public static double calculateCost(com.aliyun.odps.agentic.llm.Usage usage, com.aliyun.odps.agentic.llm.Model model) {
        if (usage == null) return 0.0;

        // 默认费率（Sonnet 档）——仅在查不到模型定价时的兜底
        double inputRate = 3.0;
        double outputRate = 15.0;
        double cacheReadRate = 0.3;
        double cacheWriteRate = 3.75;

        if (model != null) {
            var catalogModel = com.aliyun.odps.agentic.llm.provider.ModelCatalog
                .getModel(model.providerId(), model.apiId())
                .or(() -> com.aliyun.odps.agentic.llm.provider.ModelCatalog.getModel(model.apiId()));
            if (catalogModel.isPresent() && catalogModel.get().cost() != null) {
                var cost = catalogModel.get().cost();
                inputRate = cost.input();
                outputRate = cost.output();
                if (cost.cache() != null) {
                    cacheReadRate = cost.cache().read();
                    cacheWriteRate = cost.cache().write();
                }
            } else {
                log.debug("No catalog pricing for model {}/{}; falling back to default rates",
                    model.providerId(), model.apiId());
            }
        }

        double inputTokens = Math.max(0, usage.inputTokens() - usage.cacheReadInputTokens() - usage.cacheCreationInputTokens());
        double cost = (inputTokens * inputRate + usage.outputTokens() * outputRate
                     + usage.cacheReadInputTokens() * cacheReadRate
                     + usage.cacheCreationInputTokens() * cacheWriteRate) / 1_000_000.0;
        return cost;
    }

    // ── 清理 ──

    /**
     * 清理会话/消息对应的流式处理状态。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     */
    public void cleanup(String sessionId, String messageId) {
        log.info("Cleaning up stream state for session={} message={}", sessionId, messageId);
    }

    // ── 单消息重复循环检测 ──

    /**
     * 检测单条助手消息中是否包含重复的工具调用。
     *
     * @param assistantMessage 助手消息
     * @return 检测到重复循环返回 {@code true}
     */
    public boolean isDoomLoop(Message assistantMessage) {
        if (assistantMessage == null) return false;
        List<MessagePart.ToolCallPart> toolCalls = assistantMessage.parts().stream()
            .filter(p -> p instanceof MessagePart.ToolCallPart)
            .map(p -> (MessagePart.ToolCallPart) p)
            .toList();

        if (toolCalls.size() < DOOM_LOOP_THRESHOLD) return false;

        List<MessagePart.ToolCallPart> recent = toolCalls.subList(
            toolCalls.size() - DOOM_LOOP_THRESHOLD, toolCalls.size());

        String firstName = recent.get(0).name();
        String firstInput = recent.get(0).input();

        return recent.stream().allMatch(tc ->
            tc.name().equals(firstName) && Objects.equals(tc.input(), firstInput));
    }
}
