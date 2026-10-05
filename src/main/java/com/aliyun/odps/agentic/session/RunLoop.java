package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.*;
import com.aliyun.odps.agentic.llm.MessageConverter;
import com.aliyun.odps.agentic.patch.PatchEngine;
import com.aliyun.odps.agentic.permission.*;
import com.aliyun.odps.agentic.skill.SkillInfo;
import com.aliyun.odps.agentic.skill.SkillLoader;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolRegistry;
import com.aliyun.odps.agentic.tool.ToolResult;
import com.aliyun.odps.agentic.tool.builtin.SkillTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.odps.agentic.tool.FileReadTracker;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/**
 * 代理运行循环。
 *
 * <p>每轮迭代依次执行以下阶段：
 * <ol>
 *   <li>设置状态为忙碌</li>
 *   <li>过滤已被压缩取代的消息</li>
 *   <li>查找最新用户/助手消息状态并判断退出条件</li>
 *   <li>首步触发标题生成</li>
 *   <li>处理待办任务（子任务/上下文压缩）</li>
 *   <li>预检上下文溢出</li>
 *   <li>构建系统提示词和工具列表</li>
 *   <li>判断是否达到最大步数并注入 MAX_STEPS 提示</li>
 *   <li>调用 LLM 并构建助手消息</li>
 *   <li>执行工具调用</li>
 *   <li>检测重复循环和终止条件</li>
 * </ol>
 */
public class RunLoop {

    private static final Logger log = LoggerFactory.getLogger(RunLoop.class);
    private static final int DEFAULT_MAX_STEPS = 100;
    private static final int DOOM_LOOP_THRESHOLD = 3;

    private final LLMClient llmClient;
    private final ToolRegistry toolRegistry;
    private final CompactionEngine compactionEngine;
    private final PermissionEngine permissionEngine;
    private final SystemPromptBuilder systemPromptBuilder;
    private final PatchEngine patchEngine;
    private final Consumer<AgentEvent> eventConsumer;
    private final Model model;
    private final StreamProcessor streamProcessor;
    private final String workDir;
    private final SkillLoader skillLoader;
    private final MessageStore messageStore;
    private final PermissionService permissionService;
    private final SessionCompactor sessionCompactor;
    // OverflowDetector 无状态，复用同一实例，避免每步重建。
    private final OverflowDetector overflowDetector = new OverflowDetector();
    private volatile boolean cancelled = false;

    /**
     * 正在执行本次运行的线程。{@link #cancel()} 会中断它，
     * 使阻塞中的 HTTP 发送/流式读取（{@code HttpClient.send} / body 流）立即抛出
     * {@link InterruptedException}，从而真正掐断在途请求而不是只置协作标志。
     */
    private volatile Thread workerThread;

    /** 会话生命周期钩子。 */
    private volatile SessionHooks hooks;

    /** 当前运行的累计成本。 */
    private volatile double cumulativeCost = 0.0;

    /** 当前运行的累计 Token 用量。 */
    private volatile Tokens cumulativeTokens = Tokens.empty();

    /** 待处理任务队列（子任务和上下文压缩）。 */
    private final LinkedList<PendingTask> taskQueue = new LinkedList<>();

    /**
     * 测试用的重试退避覆盖（包私有，仅供测试）。非 {@code null} 时对
     * {@link RetryLogic#delay} 算出的毫秒数再做一次映射（例如压成 1ms），
     * 让故障注入测试免于真实多秒退避。生产路径保持 {@code null}。
     */
    static volatile java.util.function.LongUnaryOperator retryDelayOverrideForTest = null;

    /**
     * 引导注入（steering）与退出确认的互斥锁。运行中注入用户消息与收尾退出判断必须原子：
     * 若注入发生在退出判断之后、运行注销之前，消息会落库却无人响应（孤儿消息）。
     */
    private final Object steerLock = new Object();

    /** Steering accepted since the last model request that actually included it. */
    private final Set<String> pendingSteeringIds = new HashSet<>();

    /** 运行循环已确认退出（正常结束/取消/异常任一出口）。置位后 {@link #injectSteer} 一律拒绝。 */
    private volatile boolean exitConfirmed = false;

    public RunLoop(
        LLMClient llmClient,
        ToolRegistry toolRegistry,
        CompactionEngine compactionEngine,
        PermissionEngine permissionEngine,
        SystemPromptBuilder systemPromptBuilder,
        PatchEngine patchEngine,
        Consumer<AgentEvent> eventConsumer,
        Model model,
        String workDir,
        SkillLoader skillLoader,
        MessageStore messageStore,
        PermissionService permissionService
    ) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.compactionEngine = compactionEngine;
        this.permissionEngine = permissionEngine;
        this.systemPromptBuilder = systemPromptBuilder;
        this.patchEngine = patchEngine;
        this.eventConsumer = eventConsumer;
        this.model = model;
        this.streamProcessor = new StreamProcessor(toolRegistry, messageStore);
        this.workDir = workDir;
        this.skillLoader = skillLoader;
        this.messageStore = messageStore;
        this.permissionService = permissionService;
        this.sessionCompactor = new SessionCompactor(llmClient, compactionEngine, messageStore, eventConsumer);
    }

    // ── 中止/取消 ──

    /**
     * 取消正在执行的会话。
     *
     * <p>既设置协作式取消标志（运行循环在 LLM/工具边界观测），也中断运行线程——
     * 使阻塞中的在途 LLM 流式调用立即抛出 {@link InterruptedException} 而中止，
     * 不再继续消耗 token 直到提供者自然结束。
     */
    public void cancel() {
        this.cancelled = true;
        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
        }
        if (hooks != null) {
            hooks.onAbort("session");
        }
    }

    /**
     * 查询运行循环是否已被取消。
     *
     * @return 已取消返回 {@code true}
     */
    public boolean isCancelled() {
        return cancelled;
    }

    // ── 引导注入（steering，对齐 OpenCode） ──

    /**
     * 向正在运行的循环注入一条用户引导消息。
     *
     * <p>循环每次迭代边界都会从 {@link MessageStore} 重读消息，注入的用户消息随下一次
     * LLM 调用进入上下文 —— 不打断当前步骤、不新建运行。与退出判断互斥：返回
     * {@code true} 保证这条消息一定会被循环看到；循环已退出/已取消时返回 {@code false}，
     * 调用方应改为发起新一轮运行。
     *
     * @param msg 用户消息（{@code role == Role.USER}）
     * @return 注入成功返回 {@code true}
     */
    public boolean injectSteer(Message msg) {
        if (messageStore == null || msg == null || msg.role() != Role.USER) return false;
        synchronized (steerLock) {
            if (exitConfirmed || cancelled) return false;
            messageStore.updateMessage(msg.sessionId(), msg);
            pendingSteeringIds.add(msg.id());
        }
        emit(new AgentEvent.MessagePersisted(msg.sessionId(), msg));
        return true;
    }

    /**
     * 退出前最后一次确认有无引导注入（与 {@link #injectSteer} 互斥）。
     *
     * <p>存储中最后的用户或系统指令比最后的助手消息新 → 有未处理输入，返回 {@code false}，
     * 调用处应继续主循环响应它；否则确认退出并置位 {@link #exitConfirmed}，
     * 把「确认退出 → 运行注销」之间的注入窗口一并关闭。
     */
    private boolean confirmExit(String sessionId) {
        synchronized (steerLock) {
            if (!pendingSteeringIds.isEmpty()) return false;
            if (messageStore != null) {
                List<Message> freshest = new ArrayList<>(messageStore.getMessages(sessionId));
                if (findLastInputIndex(freshest) > findLastRoleIndex(freshest, Role.ASSISTANT)) {
                    return false;
                }
            }
            exitConfirmed = true;
            return true;
        }
    }

    private boolean hasPendingSteering() {
        synchronized (steerLock) {
            return !pendingSteeringIds.isEmpty();
        }
    }

    private void acknowledgeSteeringInModelRequest(List<Message> modelMessages) {
        synchronized (steerLock) {
            for (Message message : modelMessages) {
                pendingSteeringIds.remove(message.id());
            }
        }
    }

    // ── 钩子管理 ──

    /**
     * 安装或清除生命周期钩子。
     *
     * @param hooks 钩子实例；传 {@code null} 表示清除
     */
    public void setHooks(SessionHooks hooks) {
        this.hooks = hooks;
    }

    // ── 成本查询 ──

    /**
     * 获取当前运行的累计成本。
     *
     * @return 累计成本
     */
    public double getCumulativeCost() {
        return cumulativeCost;
    }

    /**
     * 获取当前运行的累计 Token 用量。
     *
     * @return 累计 Token
     */
    public Tokens getCumulativeTokens() {
        return cumulativeTokens;
    }

    // ── 撤销操作 ──

    /**
     * 撤销最后一轮助手消息及其工具结果。
     *
     * @param sessionId 会话 ID
     * @return 移除的消息数量
     */
    public int undo(String sessionId) {
        if (messageStore == null) return 0;

        List<Message> messages = new ArrayList<>(messageStore.getMessages(sessionId));
        int originalSize = messages.size();

        // 从尾部向前移除工具结果消息和助手消息
        while (!messages.isEmpty()) {
            Message last = messages.getLast();
            if (last.role() == Role.USER && last.parentMessageId() != null) {
                // 工具结果用户消息——移除
                messageStore.removeMessage(sessionId, last.id());
                messages.removeLast();
            } else if (last.role() == Role.ASSISTANT) {
                // 助手消息——移除后停止
                messageStore.removeMessage(sessionId, last.id());
                messages.removeLast();
                break;
            } else {
                // 普通用户消息——不移除
                break;
            }
        }

        int removed = originalSize - messages.size();
        if (removed > 0) {
            emit(new AgentEvent.UndoCompleted(sessionId, removed));
        }
        return removed;
    }

    /**
     * 执行代理运行循环直到完成或达到最大步数。
     *
     * @param session 会话
     * @param agentDef 代理定义
     * @param userMessage 用户输入文本
     * @return 最后一条助手消息
     */
    public Message run(Session session, AgentDef agentDef, String userMessage) {
        return run(session, agentDef, List.of(new MessagePart.TextPart(userMessage)));
    }

    /**
     * Run with structured user content. This preserves images and documents in
     * the SDK message store and provider projection.
     */
    public Message run(Session session, AgentDef agentDef, List<MessagePart> userParts) {
        RunOutcome outcome = runUntilPause(session, agentDef, userParts);
        if (outcome.suspended()) throw new RunSuspendedException(outcome.suspendReason());
        return outcome.message();
    }

    /** Run a user turn and return an explicit outcome when the host pauses it. */
    public RunOutcome runUntilPause(Session session, AgentDef agentDef, List<MessagePart> userParts) {
        if (userParts == null || userParts.isEmpty()) {
            throw new IllegalArgumentException("User turn requires at least one message part");
        }
        return runInternal(session, agentDef, userParts);
    }

    /** Resume a suspended run from the existing message store without duplicating a user turn. */
    public RunOutcome resumeUntilPause(Session session, AgentDef agentDef) {
        return runInternal(session, agentDef, null);
    }

    /** Resume with a host-supplied result, such as completed SQL rows or a confirmation reply. */
    public RunOutcome resumeUntilPause(Session session, AgentDef agentDef,
                                        List<MessagePart> resumeParts) {
        if (resumeParts == null || resumeParts.isEmpty()) {
            throw new IllegalArgumentException("Resume input requires at least one message part");
        }
        return runInternal(session, agentDef, resumeParts);
    }

    private RunOutcome runInternal(Session session, AgentDef agentDef, List<MessagePart> userParts) {
        // 记录工作线程，供 cancel() 中断在途的阻塞式 LLM 调用/工具执行。
        workerThread = Thread.currentThread();
        try {
            return doRun(session, agentDef, userParts);
        } finally {
            workerThread = null;
            // 若本次运行是被 cancel() 中止的，中断标志是我们自己置上的——在把线程还给
            // 调用方（可能是池化线程，如 Tomcat 请求线程）之前清掉它，避免残留中断位
            // 误伤该线程上的下一个无关任务。非取消路径不动它（不吞掉外部来源的中断）。
            if (cancelled) {
                Thread.interrupted();
            }
            // 任何退出路径都确认退出兜底：此后的引导注入一律拒绝，调用方改为发起新一轮运行。
            synchronized (steerLock) {
                exitConfirmed = true;
            }
        }
    }

    private RunOutcome doRun(Session session, AgentDef agentDef, List<MessagePart> userParts) {
        List<Message> messages = new ArrayList<>(session.messages() != null ? session.messages() : List.of());

        // 将初始会话历史写入存储，避免后续 reload 丢失数据
        if (messageStore != null && !messages.isEmpty()) {
            List<Message> known = messageStore.getMessages(session.id());
            java.util.Set<String> knownIds = new java.util.HashSet<>();
            for (Message m : known) knownIds.add(m.id());
            for (Message m : messages) {
                if (!knownIds.contains(m.id())) {
                    messageStore.updateMessage(session.id(), m);
                }
            }
        }

        // 重置累计成本和 Token
        cumulativeCost = 0.0;
        cumulativeTokens = Tokens.empty();

        // 初始化文件读取追踪（用于写前读校验）
        FileReadTracker fileReadTracker = new FileReadTracker();

        // A resume reuses the persisted history; only a new user turn appends input.
        if (userParts != null) {
            Message userMsg = createUserMessage(session, agentDef, userParts);
            messages.add(userMsg);
            if (messageStore != null) messageStore.updateMessage(session.id(), userMsg);
            emit(new AgentEvent.MessagePersisted(session.id(), userMsg));
            if (hooks != null) hooks.onMessagePersisted(session.id(), userMsg);
        }

        int step = 0;
        int maxSteps = agentDef.getMaxSteps() > 0 ? agentDef.getMaxSteps() : DEFAULT_MAX_STEPS;

        emit(AgentEvent.stepStart(session.id(), step));

        boolean hitMaxSteps = false;
        boolean finishRejectedAtLimit = false;
        String suspendReason = null;

        // doom-loop 纠偏只进行一次：注入提示后若仍重复则 break，避免无限纠偏循环。
        boolean doomLoopNudged = false;

        while (true) {
            // ── 检查中止 ──
            if (cancelled) {
                log.info("Session cancelled");
                emit(new AgentEvent.SessionAborted(session.id()));
                if (hooks != null) {
                    hooks.onAbort(session.id());
                }
                break;
            }

            // 从存储重新加载消息（如可用）
            if (messageStore != null) {
                messages = new ArrayList<>(messageStore.getMessages(session.id()));
            }

            // ── 1. 设置忙碌状态 ──
            emit(AgentEvent.statusUpdate(session.id(), SessionStatus.BUSY));
            log.info("Loop step {} for session {}", step + 1, session.id());

            List<List<MessagePart>> observations = Objects.requireNonNull(
                agentDef.getRunPolicy().beforeStep(session, agentDef, List.copyOf(messages)),
                "RunPolicy.beforeStep returned null");
            for (List<MessagePart> observation : observations) {
                if (observation == null || observation.isEmpty()) {
                    throw new IllegalStateException("RunPolicy.beforeStep returned an empty observation");
                }
                Message injected = createUserMessage(session, agentDef, observation);
                messages.add(injected);
                if (messageStore != null) messageStore.updateMessage(session.id(), injected);
                emit(new AgentEvent.MessagePersisted(session.id(), injected));
                if (hooks != null) hooks.onMessagePersisted(session.id(), injected);
            }

            // ── 2. 过滤已被压缩取代的消息 ──
            List<Message> msgs = filterCompacted(messages);

            // ── 3. 查找最新用户/助手消息 ──
            LatestResult latest = findLatest(msgs);
            Message lastUser = latest.lastUser;
            Message lastAssistant = latest.lastAssistant;
            Message lastFinished = latest.lastFinished;

            if (lastUser == null) {
                throw new IllegalStateException("No user message found in stream. This should never happen.");
            }

            // ── 4. 判断退出条件 ──
            // 某些提供者即使消息包含工具调用也返回 stop，此时需继续以发送工具结果。
            boolean hasToolCalls = lastAssistant != null && lastAssistant.parts().stream()
                .anyMatch(p -> p instanceof MessagePart.ToolCallPart);

            // 检测被中断的孤立工具调用
            boolean hasOrphanedInterruptedTool = lastAssistant != null && lastAssistant.parts().stream()
                .anyMatch(p -> p instanceof MessagePart.ToolPart tp
                    && tp.state() instanceof ToolCallState.Error err
                    && err.isInterrupted());

            // 宿主可将验收反馈等控制指令作为 SYSTEM 消息持久化。已完成答复之后的
            // 新指令也需要模型处理，冷恢复时不能直接重用上一条答复。
            int lastInputIdx = findLastInputIndex(messages);
            int lastAssistantIdx = findLastRoleIndex(messages, Role.ASSISTANT);
            // 溢出错误消息虽 finish="error"，但它是待恢复的（队里已有压缩任务），
            // 不应被当作"已完成"退出——否则顶部出口会在压缩任务执行前拦截，恢复永远打不着。
            boolean pendingCompaction = !agentDef.hostManagesContextProjection() && taskQueue.stream()
                .anyMatch(t -> t.type == PendingTask.Type.COMPACTION);
            boolean lastAssistantIsOverflowError = !agentDef.hostManagesContextProjection()
                && lastAssistant != null
                && lastAssistant.error() != null
                && AgentError.ContextOverflowError.isInstance(
                    new AgentErrorException(new AgentError.ContextOverflowError(lastAssistant.error())));
            if (lastAssistant != null && lastAssistant.finish() != null
                && !"tool-calls".equals(lastAssistant.finish())
                && !hasToolCalls
                && lastInputIdx < lastAssistantIdx
                && !hasPendingSteering()
                && !pendingCompaction
                && !lastAssistantIsOverflowError) {
                RunPolicy.FinishDecision finishDecision = lastAssistant.error() == null
                    ? evaluateFinish(session, agentDef, lastAssistant, messages)
                    : RunPolicy.FinishDecision.accept();
                if (finishDecision.suspended()) {
                    suspendReason = finishDecision.suspendReason();
                    break;
                }
                if (!finishDecision.accepted()) {
                    if (step >= maxSteps) {
                        finishRejectedAtLimit = true;
                        markFinishRejectedAtLimit(session, lastAssistant, messages);
                        break;
                    }
                    appendFinishFeedback(session, agentDef, finishDecision.feedback(), messages);
                    continue;
                }
                applyFallbackFinalText(session, lastAssistant, finishDecision, messages);
                if (confirmExit(session.id())) {
                    if (hasOrphanedInterruptedTool) {
                        log.warn("Loop exit with orphaned interrupted tool in message {}", lastAssistant.id());
                    }
                    log.info("Exiting loop: assistant finished with reason={}", lastAssistant.finish());
                    break;
                }
                // 收尾窗口内到达的引导消息 —— 回循环顶部重读快照，让它进入下一轮 LLM 上下文
                log.info("Steered user message detected at wind-down; continuing loop");
                continue;
            }

            // ── 5. 步数递增 ──
            step++;

            // ── 6. 首步触发标题生成 ──
            if (step == 1 && agentDef.generateTitle()
                    && (session.title() == null || session.title().isBlank())) {
                String firstUserText = extractFirstUserText(messages);
                if (firstUserText != null) {
                    final String text = firstUserText;
                    // 0.4.0：标题生成是阻塞式 LLM 调用，改用独立虚拟线程，
                    // 不再占用公共 ForkJoinPool（阻塞任务会拖垮共享池）。
                    Thread.startVirtualThread(() -> {
                        try {
                            String title = TitleGenerator.generateTitle(text, llmClient, model);
                            emit(new AgentEvent.TitleGenerated(session.id(), title));
                        } catch (Exception e) {
                            log.warn("Title generation failed: {}", e.getMessage());
                        }
                    });
                }
            }

            // ── 7. 处理待办任务 ──
            if (agentDef.hostManagesContextProjection()) {
                taskQueue.removeIf(pending -> pending.type == PendingTask.Type.COMPACTION);
            }
            PendingTask task = taskQueue.poll();
            if (task != null) {
                if (task.type == PendingTask.Type.SUBTASK) {
                    // 0.4.0 如实说明：SUBTASK 任务在当前代码里没有生产者（无任何入队点）——
                    // 子代理由 TaskTool/SubAgentSpawner 在工具层完成，不走这条 taskQueue 分支。
                    // 此分支因此不可达；保留它只为不破坏 PendingTask 的公共枚举契约。
                    log.debug("SUBTASK pending task is unreachable (no producer); sub-agents run via TaskTool: {}",
                        task.description);
                    continue;
                } else if (task.type == PendingTask.Type.COMPACTION) {
                    log.info("Running compaction (auto={}, overflow={})", task.auto, task.overflow);
                    ManualCompactionResult compacted = sessionCompactor.compact(
                        session.id(), messages, model, CompactionReason.AUTO, task.overflow);
                    if (!compacted.changed()) {
                        log.warn("Compaction produced no reduction, breaking");
                        break;
                    }
                    messages.clear();
                    messages.addAll(compacted.messages());
                    continue;
                }
            }

            // ── 8. 预检上下文溢出 ──
            if (!agentDef.hostManagesContextProjection()
                    && lastAssistant != null && lastAssistant.tokens() != null) {
                if (overflowDetector.isOverflow(messages, model)) {
                    taskQueue.addFirst(new PendingTask(PendingTask.Type.COMPACTION, "auto-compaction", true, true));
                    continue;
                }
            }

            // 压缩引擎预检
            CompactionEngine.CompactionRoute route = agentDef.hostManagesContextProjection()
                ? CompactionEngine.CompactionRoute.NONE
                : compactionEngine.checkNeeded(msgs, model);
            if (route == CompactionEngine.CompactionRoute.COMPACT) {
                taskQueue.addFirst(new PendingTask(PendingTask.Type.COMPACTION, "preemptive-compaction", true, false));
                continue;
            } else if (route == CompactionEngine.CompactionRoute.TRUNCATE_TOOL_RESULTS_ONLY) {
                messages = new ArrayList<>(compactionEngine.pruneToolResults(messages));
            }

            // ── 9. 构建系统提示词 ──
            // Skills are progressive-disclosure tools. Do not advertise a catalog to an agent
            // whose tool allowlist excludes `skill`, otherwise the prompt asks the model to call
            // a tool that cannot be resolved for that agent.
            Map<String, Boolean> enabledTools = resolveTools(agentDef);
            List<SkillInfo> discoveredSkills = skillLoader != null
                && Boolean.TRUE.equals(enabledTools.get("skill"))
                ? skillLoader.discover()
                : List.of();
            Set<String> includedSkills = agentDef.getIncludedSkills();
            if (includedSkills != null && !includedSkills.isEmpty()) {
                discoveredSkills = discoveredSkills.stream()
                    .filter(skill -> includedSkills.contains(skill.name()))
                    .toList();
            }
            String systemPrompt = systemPromptBuilder.build(model, agentDef, discoveredSkills, workDir);

            // ── 10. 最大步数检查 ──
            RunPolicy.Finalization policyFinalization = agentDef.getRunPolicy()
                .beforeModelCallFinalization(session, agentDef, List.copyOf(messages));
            boolean atStepLimit = step >= maxSteps;
            boolean isLastStep = atStepLimit || policyFinalization != null;
            if (atStepLimit) {
                log.warn("Max steps reached: {} (step={})", maxSteps, step);
                hitMaxSteps = true;
            }

            // ── 11. 解析工具列表和消息 ──
            // 0.4.0：移除了「在用户文本里嗅探 "format":{"type":"json_schema"} 子串来开启
            // 结构化输出」的脆弱做法。结构化输出改为显式 API：HarnessEngine.runStructured(...)
            // 会把一个绑定目标 Schema 的 StructuredOutput 工具通过 agentDef.getTools() 注入，
            // 随 buildToolSchemas 正常进入工具面，无需在消息文本里嗅探。

            // ── 12. 调用 LLM ──

            List<MessagePart.ToolCallPart> plannedCalls = isLastStep ? List.of()
                : Objects.requireNonNull(agentDef.getRunPolicy().beforeModelCall(
                    session, agentDef, List.copyOf(messages)),
                    "RunPolicy.beforeModelCall returned null");
            boolean syntheticBatch = !plannedCalls.isEmpty();
            LlmCallResult llmResult;
            if (syntheticBatch) {
                Message plannedAssistant = new Message(Identifier.messageId(), session.id(),
                    Role.ASSISTANT, null, null, new ArrayList<>(plannedCalls),
                    agentDef.getName(), null, "tool-calls", null, null,
                    enabledTools, null, Instant.now());
                llmResult = new LlmCallResult(plannedAssistant, null);
            } else {
                // 发出提示词审计快照
                emit(new AgentEvent.PromptPrepared(new PromptSnapshot(
                    session.id(), agentDef.getName(), model.apiId(), systemPrompt,
                    projectForAudit(msgs), new LinkedHashMap<>(enabledTools), Instant.now()
                )));
                emit(new AgentEvent.BeforeLLMCall(session.id(), step));
                if (hooks != null) hooks.beforeLLMCall(session.id(), step);
                emit(AgentEvent.llmStart(session.id(), step));
                acknowledgeSteeringInModelRequest(msgs);
                llmResult = callLLM(session, agentDef, systemPrompt, msgs,
                    enabledTools, isLastStep,
                    policyFinalization != null ? policyFinalization.hint() : null);
            }
            Message assistantMsg = llmResult.message;
            if (!syntheticBatch && !isLastStep && assistantMsg.parts().stream()
                    .anyMatch(part -> part instanceof MessagePart.ToolCallPart)) {
                RunPolicy.Finalization gate = agentDef.getRunPolicy().beforeToolBatch(
                    session, agentDef, assistantMsg, List.copyOf(messages));
                if (gate != null) {
                    // The proposed tool calls have not entered the message store. Replace
                    // them with a tool-free answer, leaving no orphaned call IDs.
                    llmResult = callLLM(session, agentDef, systemPrompt, msgs,
                        enabledTools, true, gate.hint());
                    assistantMsg = llmResult.message;
                    isLastStep = true;
                }
            }
            if (isLastStep) {
                assistantMsg = withoutToolCalls(assistantMsg);
            }
            messages.add(assistantMsg);
            if (messageStore != null) {
                messageStore.updateMessage(session.id(), assistantMsg);
                emit(new AgentEvent.MessagePersisted(session.id(), assistantMsg));
                if (hooks != null) {
                    hooks.onMessagePersisted(session.id(), assistantMsg);
                }
            }

            // LLM 调用后钩子
            if (!syntheticBatch) {
                emit(new AgentEvent.AfterLLMCall(session.id(), step, assistantMsg));
                if (hooks != null) {
                    hooks.afterLLMCall(session.id(), step, assistantMsg);
                }
            }

            // ── 成本追踪 ──
            if (llmResult.usage != null) {
                Tokens stepTokens = Tokens.fromUsage(llmResult.usage);
                double stepCost = assistantMsg.cost() != null ? assistantMsg.cost() : 0.0;
                cumulativeCost += stepCost;
                cumulativeTokens = cumulativeTokens.add(stepTokens);

                emit(new AgentEvent.CostUpdate(session.id(), stepCost, cumulativeCost, stepTokens));
                if (hooks != null) {
                    hooks.onCostUpdate(session.id(), stepCost, cumulativeCost, stepTokens);
                }
            }

            // ── 步后溢出检测 ──
            if (llmResult.usage != null && !agentDef.hostManagesContextProjection()) {
                if (overflowDetector.isOverflow(messages, model)) {
                    log.info("Overflow detected after step {} -- queueing compaction", step);
                    taskQueue.addFirst(new PendingTask(
                        PendingTask.Type.COMPACTION, "post-step-overflow", true, true));
                }
            }

            // ── 13. 执行工具调用 ──
            List<MessagePart.ToolCallPart> toolCalls = assistantMsg.parts().stream()
                .filter(p -> p instanceof MessagePart.ToolCallPart)
                .map(p -> (MessagePart.ToolCallPart) p)
                .toList();

            // 错误消息中的工具调用可能不完整，跳过执行
            if ("error".equals(assistantMsg.finish())) {
                toolCalls = List.of();
            }

            if (!toolCalls.isEmpty()) {
                // 工具执行前检查中止状态
                if (cancelled) {
                    log.info("Session cancelled before tool execution");
                    emit(new AgentEvent.SessionAborted(session.id()));
                    break;
                }

                // 合并代理额外上下文与 SDK 内部上下文
                Map<String, Object> toolExtra = new HashMap<>();
                toolExtra.put(FileReadTracker.CONTEXT_KEY, fileReadTracker);
                toolExtra.putAll(agentDef.getToolContextExtra());
                Set<String> toolIncludedSkills = agentDef.getIncludedSkills();
                if (toolIncludedSkills != null && !toolIncludedSkills.isEmpty()) {
                    toolExtra.put(SkillTool.INCLUDED_SKILLS_CONTEXT_KEY, Set.copyOf(toolIncludedSkills));
                }
                Map<String, Object> immutableExtra = Collections.unmodifiableMap(toolExtra);

                // 为每个工具调用创建独立上下文
                final List<Message> currentMessages = messages;
                final String assistantMessageId = assistantMsg.id();
                // P1 回归修复(复核 2026-09-28 第二轮):scopedTools 按【白名单
                // 过滤后】装入——此前全部 agentDef.getTools() 未过滤就传入,
                // 同轮含允许 A + 禁止 B 时 B 仍会执行(执行器在 scoped 中找得到)
                Map<String, ToolDef> scopedTools = new LinkedHashMap<>();
                Set<String> wl = agentDef.getIncludedTools();
                // 空集=「未配置,全放行」(AgentDef 文档契约)在此消化:装满 agent
                // 全部工具;装配后仍为空 = 配置过但零命中(拼写错)——S3 收口后
                // StreamProcessor 不再借 registry 回退兜底,这里给出可定位的告警
                boolean whitelistConfigured = wl != null && !wl.isEmpty();
                for (ToolDef tool : agentDef.getTools()) {
                    if (!whitelistConfigured || wl.contains(tool.getId())) {
                        scopedTools.put(tool.getId(), tool);
                    }
                }
                if (whitelistConfigured && scopedTools.isEmpty()) {
                    log.warn("[{}] includedTools whitelist {} matched zero tools — all tool calls "
                        + "will be denied; check tool id spelling", session.id(), wl);
                }
                ToolBatchExecutor hostExecutor = agentDef.getToolBatchExecutor().orElse(null);
                List<MessagePart.ToolResultPart> results;
                try {
                    if (hostExecutor != null) {
                        // Host policy may schedule internal plan tools that were not
                        // advertised to the model. Provider-produced calls must match
                        // the tools offered on this exact turn.
                        Set<String> permittedTools = syntheticBatch
                            ? toolCalls.stream().map(MessagePart.ToolCallPart::name)
                                .collect(java.util.stream.Collectors.toSet())
                            : enabledTools.keySet();
                        results = executeHostToolBatch(hostExecutor, toolCalls, permittedTools);
                    } else {
                        results = streamProcessor.executeToolCalls(assistantMsg,
                            (toolName, callId) -> new ToolContext(
                                session.id(), assistantMessageId, agentDef.getName(), callId,
                                currentMessages, immutableExtra, meta -> {},
                                (perm, target, detail) -> {
                                    if (permissionService != null) {
                                        PermissionRequest req = PermissionRequest.create(
                                            session.id(), toolName, callId, perm, target, detail);
                                        return permissionService.ask(req);
                                    }
                                    return permissionEngine.evaluate(perm, target) == Action.ALLOW;
                                },
                                (title, metadata) -> emit(new AgentEvent.ToolCallProgress(toolName, callId, title, metadata))
                            ),
                            () -> cancelled,
                            hooks,
                            scopedTools
                        );
                    }
                } catch (PermissionTimeoutException pte) {
                    // 权限超时 → 终止当前 run（等同于 Aborted），不重试
                    log.warn("Permission timeout during tool execution: {}", pte.getMessage());
                    emit(new AgentEvent.Error("Permission timeout: " + pte.getMessage(), pte));
                    emit(new AgentEvent.SessionAborted(session.id()));
                    break;
                }

                // 将工具结果构造为用户消息
                Message toolResultMsg = new Message(
                    Identifier.messageId(),
                    session.id(),
                    Role.USER,
                    assistantMsg.id(),
                    null,
                    new ArrayList<>(results),
                    agentDef.getName(),
                    null, null, null, null, null, null,
                    Instant.now()
                );
                messages.add(toolResultMsg);
                if (messageStore != null) {
                    messageStore.updateMessage(session.id(), toolResultMsg);
                    emit(new AgentEvent.MessagePersisted(session.id(), toolResultMsg));
                    if (hooks != null) {
                        hooks.onMessagePersisted(session.id(), toolResultMsg);
                    }
                }

                if (hostExecutor == null) {
                    for (MessagePart.ToolResultPart trp : results) {
                        emitToolCompleted(trp);
                    }
                }

                RunPolicy.ToolBatchDecision batchDecision = agentDef.getRunPolicy()
                    .afterToolBatch(session, agentDef, assistantMsg, List.copyOf(results),
                        List.copyOf(messages));
                if (!batchDecision.followupParts().isEmpty()) {
                    Message followup = createUserMessage(session, agentDef,
                        batchDecision.followupParts());
                    messages.add(followup);
                    if (messageStore != null) messageStore.updateMessage(session.id(), followup);
                    emit(new AgentEvent.MessagePersisted(session.id(), followup));
                    if (hooks != null) hooks.onMessagePersisted(session.id(), followup);
                }
                if (batchDecision.suspendReason() != null) {
                    suspendReason = batchDecision.suspendReason();
                    break;
                }
                if (batchDecision.feedback() != null) {
                    appendFinishFeedback(session, agentDef, batchDecision.feedback(), messages);
                    continue;
                }
                if (batchDecision.finalText() != null) {
                    // Preserve a provider-visible final assistant message so history,
                    // cold resume and Studio's rendered trace agree on the answer.
                    Message finalMessage = new Message(Identifier.messageId(), session.id(),
                        Role.ASSISTANT, toolResultMsg.id(), null,
                        List.of(new MessagePart.TextPart(batchDecision.finalText())),
                        agentDef.getName(), null, "end-turn", null, null, null,
                        null, Instant.now());
                    messages.add(finalMessage);
                    if (messageStore != null) {
                        messageStore.updateMessage(session.id(), finalMessage);
                        emit(new AgentEvent.MessagePersisted(session.id(), finalMessage));
                        if (hooks != null) hooks.onMessagePersisted(session.id(), finalMessage);
                    }
                    if (confirmExit(session.id())) break;
                    continue;
                }
            }

            // ── 重复循环检测 ──
            if (agentDef.getRunPolicy().useBuiltinDoomLoopDetection()
                    && streamProcessor.isDoomLoop(messages, DOOM_LOOP_THRESHOLD)) {
                // 0.4.0：不再静默 break。先发事件（前端不再「无理由停住」），
                // 再给模型一次纠偏机会：注入提示让它换思路，下一轮若仍重复才真正 break。
                emit(new AgentEvent.DoomLoopDetected(session.id(),
                    lastToolNameOf(assistantMsg)));
                if (!doomLoopNudged) {
                    doomLoopNudged = true;
                    log.warn("Doom loop detected -- nudging model to change approach");
                    Message nudge = createUserMessage(session, agentDef, DoomLoopNudge.PROMPT);
                    messages.add(nudge);
                    if (messageStore != null) {
                        messageStore.updateMessage(session.id(), nudge);
                        emit(new AgentEvent.MessagePersisted(session.id(), nudge));
                    }
                    continue;
                }
                log.warn("Doom loop persists after nudge -- breaking");
                break;
            }

            // ── 14. 终止条件判断 ──
            String finish = assistantMsg.finish();
            boolean finished = finish != null && !"tool-calls".equals(finish) && !"unknown".equals(finish);

            // 错误消息中的上下文溢出需优先触发压缩恢复——必须先于下面的 finished 退出判断，
            // 否则 finish="error" 的消息会被当作"已完成"直接退出，溢出恢复永远打不着。
            if (assistantMsg.error() != null) {
                if (AgentError.ContextOverflowError.isInstance(
                        new AgentErrorException(new AgentError.ContextOverflowError(assistantMsg.error())))) {
                    taskQueue.addFirst(new PendingTask(PendingTask.Type.COMPACTION, "overflow-compaction", true, true));
                    continue;
                }
            }

            if (finished && toolCalls.isEmpty()) {
                if (assistantMsg.error() == null) {
                    RunPolicy.FinishDecision decision =
                        evaluateFinish(session, agentDef, assistantMsg, messages);
                    if (decision.suspended()) {
                        suspendReason = decision.suspendReason();
                        break;
                    }
                    if (!decision.accepted()) {
                        if (hitMaxSteps) {
                            finishRejectedAtLimit = true;
                            markFinishRejectedAtLimit(session, assistantMsg, messages);
                            break;
                        }
                        appendFinishFeedback(session, agentDef, decision.feedback(), messages);
                        continue;
                    }
                    applyFallbackFinalText(session, assistantMsg, decision, messages);
                }
                // steering 对齐：LLM 调用期间落库的引导消息不应被本次退出甩下
                if (confirmExit(session.id())) {
                    break;
                }
                log.info("Steered user message detected at run end; continuing loop");
                continue;
            }

            if (hitMaxSteps) {
                log.warn("Forcing exit after max steps despite pending tool calls");
                break;
            }

            // 错误消息伴有未完成工具调用时标记为被中断
            if (assistantMsg.error() != null && !toolCalls.isEmpty()) {
                log.warn("Assistant error with pending tool calls, marking tools as interrupted");
                markPendingToolsInterrupted(assistantMsg);
            }
        }

        // ── 结束 ──
        emit(AgentEvent.stepFinish(session.id(), step));
        if (suspendReason != null) {
            emit(AgentEvent.statusUpdate(session.id(), SessionStatus.SUSPENDED));
            emit(new AgentEvent.Suspended(session.id(), suspendReason, step));
            return RunOutcome.suspended(findLastRole(messages, Role.ASSISTANT), suspendReason);
        }
        emit(AgentEvent.statusUpdate(session.id(), SessionStatus.IDLE));
        emit(new AgentEvent.Finished(
            finishRejectedAtLimit ? "completion-policy-rejected-max-steps"
                : hitMaxSteps ? "max-steps"
                : agentDef.getRunPolicy().terminationReason() != null
                    ? agentDef.getRunPolicy().terminationReason() : lastFinishReason(messages), step
        ));

        return RunOutcome.completed(findLastRole(messages, Role.ASSISTANT));
    }

    // ── 消息过滤与查找 ──

    /**
     * 过滤已被上下文压缩取代的消息。
     *
     * <p>两遍扫描：前向遍历标记已完成压缩的父消息 ID，反向遍历定位最后一个压缩节点，
     * 最终保留摘要消息和尾部消息。
     */
    private List<Message> filterCompacted(List<Message> messages) {
        if (messages.isEmpty()) return messages;

        // ── 前向遍历 ──
        Set<String> completed = new HashSet<>();
        String retainAfterId = null;

        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);

            // 助手摘要消息标记其父消息 ID 为"已完成"
            if (msg.role() == Role.ASSISTANT && msg.isCompacted() && msg.finish() != null && msg.error() == null) {
                completed.add(msg.parentMessageId());
            }

            // 检查已完成的用户消息是否含有压缩片段
            if (msg.role() == Role.USER && completed.contains(msg.id())) {
                for (MessagePart part : msg.parts()) {
                    if (part instanceof MessagePart.CompactionPart cp && cp.tailStartId() != null) {
                        retainAfterId = cp.tailStartId();
                        break;
                    }
                }
                if (retainAfterId == null) {
                    return new ArrayList<>(messages.subList(i, messages.size()));
                }
            }
        }

        // ── 反向遍历 ──
        int compactionIndex = -1;
        String tailStartId = null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.role() == Role.USER) {
                for (MessagePart part : msg.parts()) {
                    if (part instanceof MessagePart.CompactionPart cp && cp.tailStartId() != null) {
                        compactionIndex = i;
                        tailStartId = cp.tailStartId();
                        break;
                    }
                }
                if (compactionIndex >= 0) break;
            }
        }

        if (compactionIndex < 0) {
            if (retainAfterId != null) {
                return filterAfterId(messages, retainAfterId);
            }
            return messages;
        }

        // 定位摘要消息
        int summaryIndex = -1;
        if (compactionIndex > 0) {
            for (int i = compactionIndex - 1; i >= 0; i--) {
                Message msg = messages.get(i);
                if (msg.role() == Role.ASSISTANT && msg.isCompacted()) {
                    summaryIndex = i;
                    break;
                }
            }
        }

        // 定位尾部起点
        int tailIndex = -1;
        if (tailStartId != null) {
            for (int i = 0; i < messages.size(); i++) {
                if (messages.get(i).id().equals(tailStartId)) {
                    tailIndex = i;
                    break;
                }
            }
        }

        // 构建结果：摘要 + 尾部
        List<Message> result = new ArrayList<>();
        if (summaryIndex >= 0) {
            result.add(messages.get(summaryIndex));
        }
        if (tailIndex >= 0) {
            result.addAll(messages.subList(tailIndex, messages.size()));
        } else {
            result.addAll(messages.subList(compactionIndex, messages.size()));
        }

        return result;
    }

    /**
     * 返回指定 ID 之后的消息子列表。
     */
    private List<Message> filterAfterId(List<Message> messages, String afterId) {
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).id().equals(afterId)) {
                return new ArrayList<>(messages.subList(i, messages.size()));
            }
        }
        return messages;
    }

    /**
     * 查找最新的用户消息、助手消息和已完成消息，并提取待办任务。
     */
    private LatestResult findLatest(List<Message> messages) {
        Message lastUser = null;
        Message lastAssistant = null;
        Message lastFinished = null;
        List<PendingTask> tasks = new ArrayList<>();

        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg.role() == Role.USER && lastUser == null) {
                lastUser = msg;
                for (MessagePart part : msg.parts()) {
                    if (part instanceof MessagePart.CompactionPart cp) {
                        tasks.add(new PendingTask(PendingTask.Type.COMPACTION, "compaction", false, false));
                    }
                }
            }
            if (msg.role() == Role.ASSISTANT) {
                if (lastAssistant == null) lastAssistant = msg;
                if (lastFinished == null && msg.finish() != null) lastFinished = msg;
            }
            if (lastUser != null && lastAssistant != null && lastFinished != null) break;
        }

        for (PendingTask task : tasks) {
            if (!taskQueue.contains(task)) {
                taskQueue.add(task);
            }
        }

        return new LatestResult(lastUser, lastAssistant, lastFinished);
    }

    /**
     * 提取首条用户消息文本，用于标题生成。
     */
    private String extractFirstUserText(List<Message> messages) {
        for (Message msg : messages) {
            if (msg.role() == Role.USER) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    return text;
                }
            }
        }
        return null;
    }

    // ── LLM 调用 ──

    /**
     * 将消息投影为提供者格式以用于审计快照。
     */
    private List<Map<String, Object>> projectForAudit(List<Message> messages) {
        try {
            return model.route() != null
                ? model.route().convertMessages(messages, model)
                : MessageConverter.toAnthropicMessages(messages, model);
        } catch (Exception e) {
            log.debug("Prompt audit projection failed; emitting empty modelMessages", e);
            return List.of();
        }
    }

    /**
     * 调用 LLM 并返回助手消息和使用量。
     * 在最后一步会注入 MAX_STEPS 提示以强制模型返回纯文本。
     */
    private LlmCallResult callLLM(Session session, AgentDef agentDef, String systemPrompt,
                            List<Message> messages, Map<String, Boolean> enabledTools,
                            boolean isLastStep, String finalStepHint) {
        // 构建提供者格式消息
        List<Map<String, Object>> modelMessages;
        if (llmClient.requiresProviderProjection()) {
            boolean hasHostImageReference = messages.stream().flatMap(m -> m.parts().stream())
                .anyMatch(part -> part instanceof MessagePart.ImageReferencePart);
            if (hasHostImageReference) {
                throw new IllegalStateException("Image reference requires a host LLM protocol adapter");
            }
            modelMessages = model.route() != null
                ? model.route().convertMessages(messages, model)
                : MessageConverter.toAnthropicMessages(messages, model);
        } else {
            modelMessages = new ArrayList<>();
        }

        // 最后一步注入 MAX_STEPS 提示
        if (isLastStep) {
            modelMessages.add(Map.of("role", "assistant", "content",
                finalStepHint != null ? finalStepHint : MaxStepsPrompt.MAX_STEPS));
        }

        Map<String, Object> toolSchemas = isLastStep ? Map.of() : buildToolSchemas(agentDef);

        ProviderOptions.ModelConfig optionConfig = new ProviderOptions.ModelConfig(
            model.providerId(),
            model.apiId(),
            0,
            null,
            agentDef.getTemperature().orElse(null),
            agentDef.getTopP().orElse(null),
            model.limit().output(),
            agentDef.getEnableThinking().orElse(null)
        );
        Map<String, Object> providerOptions = ProviderOptions.compute(
            model.providerId(),
            model.apiId(),
            optionConfig
        );
        Double temperature = ProviderOptions.shouldSetTemperature(
            model.providerId(),
            model.apiId(),
            agentDef.getTemperature().orElse(null)
        ) ? ProviderOptions.temperature(model.providerId(), model.apiId(), agentDef.getTemperature().orElse(null)) : null;
        Integer maxTokens = ProviderOptions.maxOutputTokens(
            model.providerId(),
            model.apiId(),
            model.limit().output()
        );

        LlmRequest request = new LlmRequest(
            model,
            List.of(systemPrompt),
            modelMessages,
            toolSchemas,
            isLastStep ? "none" : "auto",
            temperature,
            maxTokens,
            providerOptions.isEmpty() ? null : providerOptions,
            session.id(),
            List.copyOf(messages),
            isLastStep,
            finalStepHint
        );

        // 与 OpenCode 对齐：助手消息的 id/createdAt 在 LLM 调用开始前确定。
        // 调用期间 steer 注入的用户消息创建时间更晚，在按时间排序的存储中位于
        // 助手消息之后，confirmExit 因此能感知到它并继续循环响应。
        String assistantId = Identifier.messageId();
        Instant assistantCreatedAt = Instant.now();

        // 带重试的流式请求
        List<LLMEvent> events = new ArrayList<>();
        try {
            int maxRetries = RetryLogic.DEFAULT_MAX_RETRIES;
            Exception lastError = null;
            boolean[] outputPublished = {false};
            for (int attempt = 0; attempt <= maxRetries; attempt++) {
                if (cancelled) {
                    log.info("LLM call aborted");
                    return new LlmCallResult(
                        createErrorMessage(session, agentDef, "Aborted", assistantId, assistantCreatedAt), null);
                }

                try {
                    events.clear();
                    llmClient.stream(request, event -> {
                        events.add(event);
                        // A failed request without output can retry and still stream normally.
                        // Once output is published, retrying would splice a different answer
                        // or tool call into the same live trace, which has no rollback event.
                        if (event instanceof LLMEvent.TextDelta td) {
                            outputPublished[0] |= td.delta() != null && !td.delta().isEmpty();
                            emit(new AgentEvent.TextDelta(td.delta()));
                        } else if (event instanceof LLMEvent.ReasoningStart rs) {
                            outputPublished[0] = true;
                            emit(new AgentEvent.ReasoningStart(rs.id()));
                        } else if (event instanceof LLMEvent.ReasoningDelta rd) {
                            outputPublished[0] = true;
                            emit(new AgentEvent.ReasoningDelta("", rd.delta()));
                        } else if (event instanceof LLMEvent.ReasoningEnd re) {
                            outputPublished[0] = true;
                            emit(new AgentEvent.ReasoningEnd(re.id(), null));
                        } else if (event instanceof LLMEvent.ToolCall tc) {
                            outputPublished[0] = true;
                            emit(new AgentEvent.ToolCallStarted(tc.tool(), tc.callId(), tc.input()));
                        } else if (event instanceof LLMEvent.ProviderError pe) {
                            emit(new AgentEvent.Error(pe.error(), null));
                        }
                    });
                    lastError = null;
                    break;
                } catch (Exception e) {
                    lastError = e;
                    // cancel() 中断工作线程会让在途 HTTP 调用抛出异常——这是取消而非可重试故障，
                    // 必须优先识别并立即以中止收尾，既不重试也不退避。
                    if (cancelled) {
                        log.info("LLM call interrupted by cancellation");
                        return new LlmCallResult(
                            createErrorMessage(session, agentDef, "Aborted", assistantId, assistantCreatedAt), null);
                    }
                    if (outputPublished[0]) throw e;
                    if (attempt >= maxRetries) break;

                    RetryLogic.ApiError apiError = extractApiError(e);
                    RetryLogic.Retryable retryable = RetryLogic.retryable(apiError, model.providerId());
                    if (retryable == null) throw e;

                    long delayMs = RetryLogic.delay(attempt + 1, apiError);
                    if (retryDelayOverrideForTest != null) {
                        delayMs = retryDelayOverrideForTest.applyAsLong(delayMs);
                    }
                    log.warn("LLM attempt {} failed: {}. Retrying in {}ms", attempt + 1, e.getMessage(), delayMs);
                    // 0.4.0：发出重试事件，让前端能显示「重试中」而非黑屏卡住。
                    emit(new AgentEvent.Retrying(session.id(), attempt + 1, delayMs, e.getMessage()));
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        // 退避期间被取消
                        Thread.currentThread().interrupt();
                        if (cancelled) {
                            log.info("LLM retry backoff interrupted by cancellation");
                            return new LlmCallResult(
                                createErrorMessage(session, agentDef, "Aborted", assistantId, assistantCreatedAt), null);
                        }
                        throw ie;
                    }
                }
            }
            if (lastError != null) {
                throw lastError;
            }
        } catch (Exception e) {
            log.error("LLM call failed after retries", e);
            if (agentDef.propagateLlmFailures()) {
                if (e instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("Host LLM call failed", e);
            }
            return new LlmCallResult(
                createErrorMessage(session, agentDef, e.getMessage(), assistantId, assistantCreatedAt), null);
        }

        // 提取使用量
        com.aliyun.odps.agentic.llm.Usage rawUsage = streamProcessor.extractUsage(events);

        // 从事件流构建助手消息（携带 model 以按真实单价计费）
        Message assistantMsg = streamProcessor.processEvents(
            events, session.id(), agentDef.getName(), null, assistantId, assistantCreatedAt,
            model, false
        );

        // 设置模型引用
        Message withModel = new Message(
            assistantMsg.id(),
            assistantMsg.sessionId(),
            assistantMsg.role(),
            assistantMsg.parentMessageId(),
            assistantMsg.tokens(),
            assistantMsg.parts(),
            assistantMsg.agent(),
            new Message.ModelRef(model.providerId(), model.apiId(), null),
            assistantMsg.finish(),
            assistantMsg.error(),
            assistantMsg.summary(),
            enabledTools,
            assistantMsg.cost(),
            assistantMsg.createdAt(),
            assistantMsg.hostMetadata()
        );

        return new LlmCallResult(withModel, rawUsage);
    }

    /**
     * LLM 调用内部结果，同时携带消息和原始使用量。
     */
    private record LlmCallResult(Message message, com.aliyun.odps.agentic.llm.Usage usage) {}

    /** A provider may ignore toolChoice=none; never execute or persist those calls. */
    private static Message withoutToolCalls(Message candidate) {
        if (candidate.parts().stream().noneMatch(MessagePart.ToolCallPart.class::isInstance)) {
            return candidate;
        }
        List<MessagePart> visible = candidate.parts().stream()
            .filter(part -> !(part instanceof MessagePart.ToolCallPart)).toList();
        if (visible.isEmpty()) visible = List.of(new MessagePart.TextPart(""));
        return new Message(candidate.id(), candidate.sessionId(), candidate.role(),
            candidate.parentMessageId(), candidate.tokens(), visible, candidate.agent(),
            candidate.model(), "end-turn", candidate.error(), candidate.summary(),
            candidate.tools(), candidate.cost(), candidate.createdAt(),
            candidate.hostMetadata());
    }

    private Message createErrorMessage(Session session, AgentDef agentDef, String error,
                                       String messageId, Instant createdAt) {
        return new Message(
            messageId,
            session.id(),
            Role.ASSISTANT,
            null, null,
            List.of(new MessagePart.TextPart("Error: " + error)),
            agentDef.getName(),
            new Message.ModelRef(model.providerId(), model.apiId(), null),
            "error",
            error,
            null, null, null,
            createdAt
        );
    }

    private RetryLogic.ApiError extractApiError(Exception e) {
        if (e instanceof RetryLogic.ApiErrorException aee) {
            return aee.apiError();
        }
        // 0.4.0：SseLlmClient 以非受检的 LlmApiException 抛出 HTTP/网络故障，
        // 这里把状态码/可重试性/响应头透传给重试判定，让重试机制真正可达。
        if (e instanceof com.aliyun.odps.agentic.llm.LlmApiException lae) {
            return new RetryLogic.ApiError(
                lae.getMessage(),
                lae.statusCode(),
                lae.retryable(),
                lae.responseHeaders(),
                lae.responseBody()
            );
        }
        return new RetryLogic.ApiError(e.getMessage(), null, false, null, null);
    }

    private Map<String, Object> buildToolSchemas(AgentDef agentDef) {
        Set<String> whitelist = agentDef.getIncludedTools();
        boolean hasWhitelist = whitelist != null && !whitelist.isEmpty();

        Map<String, Object> schemas = new LinkedHashMap<>();
        for (ToolDef tool : agentDef.getTools()) {
            if (hasWhitelist && !whitelist.contains(tool.getId())) continue;
            schemas.put(tool.getId(), Map.of(
                "description", tool.getDescription() != null ? tool.getDescription() : "",
                "inputSchema", tool.getParametersSchema() != null ? (Object) tool.getParametersSchema() : Map.of()
            ));
        }
        if (agentDef.includeBuiltinTools()) {
            for (ToolDef tool : toolRegistry.all()) {
                if (hasWhitelist && !whitelist.contains(tool.getId())) continue;
                schemas.putIfAbsent(tool.getId(), Map.of(
                    "description", tool.getDescription() != null ? tool.getDescription() : "",
                    "inputSchema", tool.getParametersSchema() != null ? (Object) tool.getParametersSchema() : Map.of()
                ));
            }
        }
        return schemas;
    }

    private Map<String, Boolean> resolveTools(AgentDef agentDef) {
        Set<String> whitelist = agentDef.getIncludedTools();
        boolean hasWhitelist = whitelist != null && !whitelist.isEmpty();

        Map<String, Boolean> tools = new LinkedHashMap<>();
        for (ToolDef toolDef : agentDef.getTools()) {
            if (hasWhitelist && !whitelist.contains(toolDef.getId())) continue;
            tools.put(toolDef.getId(), true);
        }
        if (agentDef.includeBuiltinTools()) {
            for (ToolDef toolDef : toolRegistry.all()) {
                if (hasWhitelist && !whitelist.contains(toolDef.getId())) continue;
                tools.putIfAbsent(toolDef.getId(), true);
            }
        }
        return tools;
    }

    private Message findLastRole(List<Message> messages, Role role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == role) return messages.get(i);
        }
        return null;
    }

    /**
     * 将未完成的工具调用标记为被中断。
     *
     * <p>0.4.0 如实说明：运行循环里助手消息携带的是 {@link MessagePart.ToolCallPart}
     * （无执行状态），而非带 {@link ToolCallState} 的 {@link MessagePart.ToolPart}
     * （后者仅 CompactionEngine 渲染用）。因此本方法在当前消息模型下不会命中任何 part。
     * 工具中断状态实际由 {@code executeToolCalls} 的中止检查在 {@code ToolResultPart}
     * 层面以 "Tool execution aborted" 记录。保留本方法作为防御性占位。
     */
    private void markPendingToolsInterrupted(Message assistantMsg) {
        for (MessagePart part : assistantMsg.parts()) {
            if (part instanceof MessagePart.ToolPart tp) {
                if (tp.state() instanceof ToolCallState.Running || tp.state() instanceof ToolCallState.Pending) {
                    log.warn("Tool {} (callID={}) left in {} state, marking interrupted",
                        tp.tool(), tp.callID(), tp.state().getClass().getSimpleName());
                }
            }
        }
    }

    private int findLastInputIndex(List<Message> messages) {
        return Math.max(findLastRoleIndex(messages, Role.USER), findLastRoleIndex(messages, Role.SYSTEM));
    }

    private int findLastRoleIndex(List<Message> messages, Role role) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).role() == role) return i;
        }
        return -1;
    }

    private Message createUserMessage(Session session, AgentDef agentDef, String text) {
        return createUserMessage(session, agentDef, List.of(new MessagePart.TextPart(text)));
    }

    private List<MessagePart.ToolResultPart> executeHostToolBatch(
            ToolBatchExecutor executor, List<MessagePart.ToolCallPart> calls,
            Set<String> offeredTools) {
        Map<String, String> expectedNames = new LinkedHashMap<>();
        Map<String, Integer> batchIndices = new HashMap<>();
        List<MessagePart.ToolCallPart> permitted = new ArrayList<>();
        Map<String, MessagePart.ToolResultPart> denied = new HashMap<>();
        int batchIndex = 0;
        for (MessagePart.ToolCallPart call : calls) {
            if (expectedNames.putIfAbsent(call.callID(), call.name()) != null) {
                throw new IllegalStateException("Duplicate tool call id: " + call.callID());
            }
            batchIndices.put(call.callID(), batchIndex++);
            if (offeredTools.contains(call.name())) {
                permitted.add(call);
            } else {
                denied.put(call.callID(), new MessagePart.ToolResultPart(call.callID(),
                    call.name(), "Tool is not available in this turn: " + call.name(), true));
            }
        }
        Set<String> earlySettled = Collections.synchronizedSet(new HashSet<>());
        for (MessagePart.ToolCallPart call : calls) {
            MessagePart.ToolResultPart result = denied.get(call.callID());
            if (result == null) continue;
            emit(new AgentEvent.HostToolResultSettled(result, batchIndices.get(result.callID())));
            emitToolCompleted(result);
            earlySettled.add(result.callID());
        }
        List<MessagePart.ToolResultPart> executed = permitted.isEmpty() ? List.of()
            : executor.execute(List.copyOf(permitted), result -> {
                validateHostResult(result, expectedNames);
                if (earlySettled.add(result.callID())) {
                    emit(new AgentEvent.HostToolResultSettled(result,
                        batchIndices.get(result.callID())));
                    emitToolCompleted(result);
                }
            });
        if (executed == null || executed.size() != permitted.size()) {
            throw new IllegalStateException("Host tool batch returned an incomplete result list");
        }
        Map<String, MessagePart.ToolResultPart> byId = new HashMap<>(denied);
        for (int i = 0; i < permitted.size(); i++) {
            MessagePart.ToolResultPart result = executed.get(i);
            validateHostResult(result, expectedNames);
            if (!permitted.get(i).callID().equals(result.callID())) {
                throw new IllegalStateException("Host tool batch changed result order at index " + i);
            }
            byId.put(result.callID(), result);
            if (earlySettled.add(result.callID())) {
                emit(new AgentEvent.HostToolResultSettled(result, batchIndices.get(result.callID())));
                emitToolCompleted(result);
            }
        }
        List<MessagePart.ToolResultPart> results = calls.stream()
            .map(call -> byId.get(call.callID())).toList();
        return results;
    }

    private static void validateHostResult(MessagePart.ToolResultPart result,
                                           Map<String, String> expectedNames) {
        if (result == null || !Objects.equals(expectedNames.get(result.callID()), result.name())) {
            throw new IllegalStateException("Host tool batch returned an unknown tool result");
        }
    }

    private void emitToolCompleted(MessagePart.ToolResultPart result) {
        emit(new AgentEvent.ToolCallCompleted(result.name(), result.callID(),
            result.output(), result.output()));
    }

    private RunPolicy.FinishDecision evaluateFinish(Session session, AgentDef agentDef,
                                                    Message candidate, List<Message> messages) {
        return Objects.requireNonNull(agentDef.getRunPolicy().beforeFinish(
            session, agentDef, candidate, List.copyOf(messages)),
            "RunPolicy.beforeFinish returned null");
    }

    private void appendFinishFeedback(Session session, AgentDef agentDef,
                                      String feedback, List<Message> messages) {
        Message userFeedback = createUserMessage(session, agentDef, feedback);
        messages.add(userFeedback);
        if (messageStore != null) {
            messageStore.updateMessage(session.id(), userFeedback);
            emit(new AgentEvent.MessagePersisted(session.id(), userFeedback));
        }
    }

    private void applyFallbackFinalText(Session session, Message candidate,
                                        RunPolicy.FinishDecision decision,
                                        List<Message> messages) {
        if (decision.replacementParts() == null
                && (decision.fallbackText() == null || !candidate.getTextContent().isBlank())) {
            return;
        }
        List<MessagePart> parts;
        if (decision.replacementParts() != null) {
            parts = decision.replacementParts();
        } else {
            parts = new ArrayList<>(candidate.parts());
            parts.add(new MessagePart.TextPart(decision.fallbackText()));
        }
        Message filled = new Message(candidate.id(), candidate.sessionId(), candidate.role(),
            candidate.parentMessageId(), candidate.tokens(), List.copyOf(parts),
            candidate.agent(), candidate.model(), candidate.finish(), candidate.error(),
            candidate.summary(), candidate.tools(), candidate.cost(), candidate.createdAt(),
            candidate.hostMetadata());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (candidate.id().equals(messages.get(i).id())) {
                messages.set(i, filled);
                break;
            }
        }
        if (messageStore != null) {
            messageStore.updateMessage(session.id(), filled);
            emit(new AgentEvent.MessagePersisted(session.id(), filled));
        }
    }

    private void markFinishRejectedAtLimit(Session session, Message candidate,
                                           List<Message> messages) {
        Message failed = new Message(candidate.id(), candidate.sessionId(), candidate.role(),
            candidate.parentMessageId(), candidate.tokens(), candidate.parts(),
            candidate.agent(), candidate.model(), "completion-policy-rejected",
            "Completion policy rejected the final answer at the maximum step limit",
            candidate.summary(), candidate.tools(), candidate.cost(), candidate.createdAt(),
            candidate.hostMetadata());
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (candidate.id().equals(messages.get(i).id())) {
                messages.set(i, failed);
                break;
            }
        }
        if (messageStore != null) {
            messageStore.updateMessage(session.id(), failed);
            emit(new AgentEvent.MessagePersisted(session.id(), failed));
        }
    }

    private Message createUserMessage(Session session, AgentDef agentDef, List<MessagePart> parts) {
        return new Message(
            Identifier.messageId(),
            session.id(),
            Role.USER,
            null, null,
            List.copyOf(parts),
            agentDef.getName(),
            null, null, null, null, null, null,
            Instant.now()
        );
    }

    private String lastFinishReason(List<Message> messages) {
        Message last = findLastRole(messages, Role.ASSISTANT);
        return last != null && last.finish() != null ? last.finish() : "max-steps";
    }

    /** 提取助手消息中最后一个工具调用的名字（用于 doom-loop 事件的可观测性）。 */
    private String lastToolNameOf(Message assistantMsg) {
        if (assistantMsg == null) return null;
        List<MessagePart.ToolCallPart> tcs = assistantMsg.parts().stream()
            .filter(p -> p instanceof MessagePart.ToolCallPart)
            .map(p -> (MessagePart.ToolCallPart) p)
            .toList();
        return tcs.isEmpty() ? null : tcs.getLast().name();
    }

    /** doom-loop 纠偏提示（0.4.0）。 */
    private static final class DoomLoopNudge {
        static final String PROMPT =
            "[System] You appear to be repeating the same tool call with identical arguments and making no progress. "
            + "Stop and reconsider: either try a materially different approach, or explain to the user what is blocking you. "
            + "Do not repeat the previous tool call.";
    }

    private void emit(AgentEvent event) {
        if (eventConsumer != null) {
            eventConsumer.accept(event);
        }
    }

    // ── 内部类型 ──

    /**
     * 待处理任务（子任务或上下文压缩）。
     *
     * @param type 任务类型
     * @param description 描述
     * @param auto 是否自动触发
     * @param overflow 是否由溢出触发
     */
    public record PendingTask(Type type, String description, boolean auto, boolean overflow) {
        /** 任务类型。 */
        public enum Type { SUBTASK, COMPACTION }
    }

    /**
     * {@link #findLatest} 的返回结果。
     */
    private record LatestResult(Message lastUser, Message lastAssistant, Message lastFinished) {}
}
