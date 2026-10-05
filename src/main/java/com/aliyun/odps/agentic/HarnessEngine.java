package com.aliyun.odps.agentic;

import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.AnthropicTransform;
import com.aliyun.odps.agentic.llm.Auth;
import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.LlmRequest;
import com.aliyun.odps.agentic.llm.MessageFormat;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.llm.Route;
import com.aliyun.odps.agentic.memory.MemoryConfig;
import com.aliyun.odps.agentic.model.Identifier;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.model.SessionStatus;
import com.aliyun.odps.agentic.permission.AsyncPermissionAsker;
import com.aliyun.odps.agentic.permission.DefaultPermissionAsker;
import com.aliyun.odps.agentic.permission.PermissionService;
import com.aliyun.odps.agentic.permission.Rule;
import com.aliyun.odps.agentic.mcp.McpClient;
import com.aliyun.odps.agentic.mcp.McpServerConfig;
import com.aliyun.odps.agentic.mcp.McpToolDef;
import com.aliyun.odps.agentic.mcp.StdioMcpClient;
import com.aliyun.odps.agentic.mcp.SseMcpClient;
import com.aliyun.odps.agentic.otel.OpenTelemetry;
import com.aliyun.odps.agentic.session.*;
import com.aliyun.odps.agentic.patch.PatchEngine;
import com.aliyun.odps.agentic.permission.PermissionEngine;
import com.aliyun.odps.agentic.skill.SkillLoader;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolRegistry;
import com.aliyun.odps.agentic.tool.builtin.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Harness 引擎，SDK 的主入口。
 *
 * <p>这是驱动所有通过 {@link AgentDef} 定义的代理的<b>固定运行时</b>。
 *
 * <h2>快速上手</h2>
 * <pre>{@code
 * // 1. 创建引擎
 * HarnessEngine engine = HarnessEngine.builder()
 *     .workDir(Path.of("/my/project"))
 *     .llmClient(myLlmClient)
 *     .model(Anthropic.configure("sk-ant-xxx")
 *         .model("claude-sonnet-4-20250514", new ModelLimit(200000, null, 16384)))
 *     .build();
 *
 * // 2. 定义代理
 * AgentDef myAgent = new MyAgentDef(); // 实现 AgentDef 接口
 *
 * // 3. 创建会话并运行
 * Session session = engine.createSession(myAgent);
 * Message result = engine.run(session, myAgent, "Hello!");
 * }</pre>
 *
 * <h2>架构</h2>
 * <pre>
 * ┌─────────────────────────────────────────────┐
 * │              HarnessEngine                   │
 * │  （固定运行时 — 对所有代理通用）              │
 * ├─────────────────────────────────────────────┤
 * │  运行循环 → 流式处理器 → LLMClient           │
 * │  上下文压缩引擎 → 权限引擎                   │
 * │  系统提示词构建器 → 技能加载器                │
 * │  工具注册表 → 补丁引擎                       │
 * └─────────────────────────────────────────────┘
 *          ▲ 实现
 * ┌─────────────────────────────────────────────┐
 * │              AgentDef（SPI）                  │
 * │  （可插拔 — 定义代理的行为和个性）            │
 * ├─────────────────────────────────────────────┤
 * │  getName() → 系统提示词 → 工具               │
 * │  MCP 服务器 → 技能 → 权限规则                │
 * │  最大步数 → 模型 → temperature                │
 * └─────────────────────────────────────────────┘
 * </pre>
 *
 * @see AgentDef
 * @see Model
 * @see Session
 */
public class HarnessEngine {

    private static final Logger log = LoggerFactory.getLogger(HarnessEngine.class);

    private final Path workDir;
    private final LLMClient llmClient;
    private final Model model;
    private final ToolRegistry toolRegistry;
    private final CompactionEngine compactionEngine;
    private final PermissionEngine permissionEngine;
    private final SystemPromptBuilder systemPromptBuilder;
    private final SkillLoader skillLoader;
    private final PatchEngine patchEngine;
    private final StreamProcessor streamProcessor;
    private final MessageStore messageStore;
    private final PermissionService permissionService;
    private final SubAgentSpawner subAgentSpawner;
    private final EventBus eventBus;
    private final SnapshotTracker snapshotTracker;
    private final OpenTelemetry.OtelTracer tracer;
    private final OpenTelemetry.OtelMetrics metrics;
    private final Map<String, McpClient> mcpClients = new java.util.concurrent.ConcurrentHashMap<>();
    /** Active top-level runs keyed by session id, used for cooperative cancellation. */
    private final Map<String, RunLoop> activeRuns = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService virtualThreadExecutor =
        java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    private HarnessEngine(Builder builder) {
        this.workDir = builder.workDir;
        this.llmClient = builder.llmClient;
        this.model = builder.model != null ? builder.model
            : new Route("default", new AnthropicTransform(), "https://api.anthropic.com/v1/messages",
                Auth.none, MessageFormat.ANTHROPIC).model("default", new ModelLimit(128000, null, 4096));
        this.patchEngine = new PatchEngine();
        this.permissionEngine = new PermissionEngine(builder.permissionRules);
        this.compactionEngine = new CompactionEngine();
        this.toolRegistry = new ToolRegistry();
        this.systemPromptBuilder = new SystemPromptBuilder();
        this.skillLoader = new SkillLoader(builder.skillPaths, builder.skillRepositories);
        this.streamProcessor = new StreamProcessor(toolRegistry);
        this.messageStore = builder.messageStore != null
            ? builder.messageStore
            : new InMemoryMessageStore();
        this.permissionService = new PermissionService(permissionEngine,
            builder.asyncPermissionAsker != null ? builder.asyncPermissionAsker : new DefaultPermissionAsker(),
            builder.permissionAskTimeout);
        this.subAgentSpawner = new SubAgentSpawner(this);
        this.eventBus = new EventBus();
        this.snapshotTracker = new SnapshotTracker(builder.workDir);
        this.tracer = new OpenTelemetry.OtelTracer(builder.otelConfig != null ? builder.otelConfig : OpenTelemetry.OtelConfig.disabled());
        this.metrics = new OpenTelemetry.OtelMetrics();

        // 注册内置工具
        registerBuiltinTools();
    }

    private void registerBuiltinTools() {
        toolRegistry.register(new ApplyPatchTool());
        toolRegistry.register(new EditTool());
        toolRegistry.register(new ReadTool());
        toolRegistry.register(new WriteTool());
        toolRegistry.register(new ShellTool());
        toolRegistry.register(new GlobTool());
        toolRegistry.register(new GrepTool());

        var taskTool = new TaskTool();
        taskTool.setSubAgentRunner(request -> {
            var subSession = createSession(
                com.aliyun.odps.agentic.agent.AgentDefBuilder.create(request.subagentType())
                    .systemPrompt("You are a helpful sub-agent. Complete the task and return results concisely.")
                    .build());
            var result = run(subSession,
                com.aliyun.odps.agentic.agent.AgentDefBuilder.create(request.subagentType())
                    .systemPrompt("You are a helpful sub-agent. Complete the task and return results concisely.")
                    .build(),
                request.prompt());
            return new TaskTool.SubAgentResult(result.getTextContent());
        });
        toolRegistry.register(taskTool);

        toolRegistry.register(new QuestionTool());
        toolRegistry.register(new TodoTool());
        toolRegistry.register(new WebFetchTool());
        toolRegistry.register(new SkillTool(skillLoader));
        toolRegistry.register(new PlanTool());
        toolRegistry.register(new WebSearchTool());
    }

    // ── 公共 API ────────────────────────────────

    /**
     * 为指定代理创建新会话。
     *
     * @param agent 代理定义
     * @return 新会话对象
     */
    public Session createSession(AgentDef agent) {
        return createSession(agent, UUID.randomUUID().toString(), List.of());
    }

    /**
     * 使用指定的会话 ID 创建新会话（无历史消息）。
     * 适用于需要稳定 ID 映射的服务端场景。
     *
     * @param agent     代理定义
     * @param sessionId 会话 ID
     * @return 新会话对象
     */
    public Session createSession(AgentDef agent, String sessionId) {
        return createSession(agent, sessionId, List.of());
    }

    /**
     * 使用指定的会话 ID 和初始消息历史创建新会话。
     *
     * @param agent     代理定义
     * @param sessionId 会话 ID
     * @param messages  初始消息历史
     * @return 新会话对象
     */
    public Session createSession(AgentDef agent, String sessionId, List<Message> messages) {
        return new Session(
            sessionId,
            null,
            SessionStatus.IDLE,
            messages == null ? List.of() : List.copyOf(messages),
            agent.getName(),
            model,
            0.0,
            null,
            Instant.now(),
            Instant.now(),
            agent.getPermissionRules()
        );
    }

    /**
     * 在指定会话上运行代理，返回最终助手消息。
     *
     * @param session     会话
     * @param agent       代理定义
     * @param userMessage 用户输入消息
     * @return 最终助手消息
     */
    public Message run(Session session, AgentDef agent, String userMessage) {
        List<AgentEvent> events = new ArrayList<>();
        RunLoop runLoop = createRunLoop(events::add, resolveModel(session));
        return withTrackedRun(session, runLoop,
            () -> withAgentContext(agent, () -> runLoop.run(session, agent, userMessage)));
    }

    /**
     * 运行代理并收集所有事件（适用于流式 UI）。
     *
     * @param session     会话
     * @param agent       代理定义
     * @param userMessage 用户输入消息
     * @return 包含最终消息和全部事件的运行结果
     */
    public RunResult runWithEvents(Session session, AgentDef agent, String userMessage) {
        List<AgentEvent> events = new ArrayList<>();
        RunLoop runLoop = createRunLoop(events::add, resolveModel(session));
        return withTrackedRun(session, runLoop, () -> withAgentContext(agent, () -> {
            Message finalMessage = runLoop.run(session, agent, userMessage);
            return new RunResult(finalMessage, List.copyOf(events));
        }));
    }

    /**
     * 运行代理并通过自定义事件消费者实时接收事件。
     *
     * @param session       会话
     * @param agent         代理定义
     * @param userMessage   用户输入消息
     * @param eventConsumer 实时事件消费者
     * @return 最终助手消息
     */
    public Message runStreaming(Session session, AgentDef agent, String userMessage,
                                AgentEventConsumer eventConsumer) {
        return runStreaming(session, agent,
            List.of(new MessagePart.TextPart(userMessage)), eventConsumer);
    }

    /**
     * Run a turn with structured content, including image and document parts.
     * The original text overload remains source and behavior compatible.
     */
    public Message runStreaming(Session session, AgentDef agent, List<MessagePart> userParts,
                                AgentEventConsumer eventConsumer) {
        RunLoop runLoop = createRunLoop(eventConsumer::onEvent, resolveModel(session));
        return withTrackedRun(session, runLoop,
            () -> withAgentContext(agent, () -> runLoop.run(session, agent, userParts)));
    }

    /** Run a new turn while allowing the host completion policy to pause it. */
    public RunOutcome runUntilPause(Session session, AgentDef agent, List<MessagePart> userParts,
                                    AgentEventConsumer eventConsumer) {
        RunLoop runLoop = createRunLoop(eventConsumer::onEvent, resolveModel(session));
        return withTrackedRun(session, runLoop,
            () -> withAgentContext(agent, () -> runLoop.runUntilPause(session, agent, userParts)));
    }

    /** Resume a paused run from persisted messages without appending another user turn. */
    public RunOutcome resumeUntilPause(Session session, AgentDef agent,
                                       AgentEventConsumer eventConsumer) {
        RunLoop runLoop = createRunLoop(eventConsumer::onEvent, resolveModel(session));
        return withTrackedRun(session, runLoop,
            () -> withAgentContext(agent, () -> runLoop.resumeUntilPause(session, agent)));
    }

    /** Resume with a correlated host result that must be shown to the model. */
    public RunOutcome resumeUntilPause(Session session, AgentDef agent,
                                       List<MessagePart> resumeParts,
                                       AgentEventConsumer eventConsumer) {
        RunLoop runLoop = createRunLoop(eventConsumer::onEvent, resolveModel(session));
        return withTrackedRun(session, runLoop,
            () -> withAgentContext(agent,
                () -> runLoop.resumeUntilPause(session, agent, resumeParts)));
    }

    /**
     * 异步运行代理。
     * 在虚拟线程上启动代理运行，事件通过 {@link EventBus} 发布。
     *
     * @param session     会话
     * @param agent       代理定义
     * @param userMessage 用户输入消息
     * @return 代理运行完成时解析的 {@code CompletableFuture}
     */
    public java.util.concurrent.CompletableFuture<Message> runAsync(Session session, AgentDef agent, String userMessage) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            RunLoop runLoop = createRunLoop(eventBus::emit, resolveModel(session));
            try {
                return withTrackedRun(session, runLoop,
                    () -> withAgentContext(agent, () -> runLoop.run(session, agent, userMessage)));
            } catch (Exception e) {
                eventBus.emit(new AgentEvent.Error(e.getMessage(), e));
                throw new RuntimeException(e);
            }
        }, virtualThreadExecutor);
    }

    /**
     * 异步运行代理并通过自定义事件消费者实时接收事件。
     * 事件同时发送给消费者和 {@link EventBus}。
     *
     * @param session       会话
     * @param agent         代理定义
     * @param userMessage   用户输入消息
     * @param eventConsumer 实时事件消费者
     * @return 代理运行完成时解析的 {@code CompletableFuture}
     */
    public java.util.concurrent.CompletableFuture<Message> runAsyncStreaming(
            Session session, AgentDef agent, String userMessage,
            AgentEventConsumer eventConsumer) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            RunLoop runLoop = createRunLoop(event -> {
                eventConsumer.onEvent(event);
                eventBus.emit(event);
            }, resolveModel(session));
            try {
                return withTrackedRun(session, runLoop,
                    () -> withAgentContext(agent, () -> runLoop.run(session, agent, userMessage)));
            } catch (Exception e) {
                eventBus.emit(new AgentEvent.Error(e.getMessage(), e));
                throw new RuntimeException(e);
            }
        }, virtualThreadExecutor);
    }

    /**
     * 手动触发会话上下文压缩。
     *
     * @param session 会话
     * @param agent   代理定义
     * @return 上下文压缩结果
     */
    public ManualCompactionResult compact(Session session, AgentDef agent) {
        List<AgentEvent> events = new ArrayList<>();
        return compact(session, agent, events::add);
    }

    /**
     * 手动触发会话上下文压缩，并通过事件消费者接收过程事件。
     *
     * @param session       会话
     * @param agent         代理定义
     * @param eventConsumer 事件消费者
     * @return 上下文压缩结果
     */
    public ManualCompactionResult compact(Session session, AgentDef agent, AgentEventConsumer eventConsumer) {
        SessionCompactor compactor = new SessionCompactor(llmClient, compactionEngine, messageStore, eventConsumer::onEvent);
        List<Message> messages = messageStore.getMessages(session.id());
        if (messages.isEmpty()) {
            messages = session.messages();
        }
        List<Message> capturedMessages = messages;
        Model effectiveModel = resolveModel(session);
        return withAgentContext(agent, () -> compactor.compact(
            session.id(),
            capturedMessages,
            effectiveModel,
            CompactionReason.MANUAL,
            false
        ));
    }

    // ── 结构化输出（0.4.0） ─────────────────────────────────────

    /**
     * 运行代理并以类型安全方式获取结构化输出。
     *
     * <p>从 {@code outputType} 生成 JSON Schema，注入一个绑定该 Schema 的结构化输出工具，
     * 捕获模型提交的数据并反序列化为 {@code T}。这取代了此前「在用户文本里嗅探
     * {@code "format":{"type":"json_schema"}} 子串」的脆弱做法——那种做法会在用户正常
     * 聊到该串时误开结构化输出。
     *
     * @param session    会话
     * @param agent      代理定义
     * @param userMessage 用户输入
     * @param outputType 期望输出的 Java 类型（优先 record / POJO）
     * @param <T>        输出类型
     * @return 结构化运行结果（最终消息 + 解析后的输出对象）
     */
    public <T> StructuredResult<T> runStructured(Session session, AgentDef agent, String userMessage,
                                                 Class<T> outputType) {
        com.fasterxml.jackson.databind.node.ObjectNode schema =
            com.aliyun.odps.agentic.llm.JsonSchemaGenerator.schemaFor(outputType);

        // 捕获模型通过结构化输出工具提交的数据
        java.util.concurrent.atomic.AtomicReference<com.fasterxml.jackson.databind.JsonNode> captured =
            new java.util.concurrent.atomic.AtomicReference<>();
        var structuredTool = new com.aliyun.odps.agentic.tool.builtin.StructuredOutputTool(schema, captured::set);

        // 以 run 级作用域注册该工具（仅本次运行可见，不污染引擎级注册表）
        List<AgentEvent> events = new ArrayList<>();
        RunLoop runLoop = createRunLoop(events::add, resolveModel(session));
        AgentDef withStructured = new AgentDefWithOutputTool(agent, structuredTool);
        Message finalMessage = withTrackedRun(session, runLoop,
            () -> withAgentContext(withStructured, () -> runLoop.run(session, withStructured, userMessage)));

        T output = null;
        com.fasterxml.jackson.databind.JsonNode node = captured.get();
        if (node != null) {
            try {
                output = new com.fasterxml.jackson.databind.ObjectMapper().treeToValue(node, outputType);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(
                    "Structured output could not be mapped to " + outputType.getName() + ": " + node, e);
            }
        }
        return new StructuredResult<>(finalMessage, output, node != null);
    }

    /**
     * 结构化运行结果。
     *
     * @param message  最终助手消息
     * @param output   解析后的输出对象；模型未提交结构化数据时为 {@code null}
     * @param hasOutput 模型是否提交了结构化数据
     * @param <T>      输出类型
     */
    public record StructuredResult<T>(Message message, T output, boolean hasOutput) {}

    /**
     * 在代理定义外叠加一个 run 级结构化输出工具的装饰器。
     *
     * <p>除 {@link #getTools()} 追加了结构化输出工具外，其余所有方法都原样委托给
     * 被装饰的代理——避免只委托部分方法而悄悄丢失 delegate 的自定义配置。
     */
    private static final class AgentDefWithOutputTool implements AgentDef {
        private final AgentDef delegate;
        private final com.aliyun.odps.agentic.tool.ToolDef extra;

        AgentDefWithOutputTool(AgentDef delegate, com.aliyun.odps.agentic.tool.ToolDef extra) {
            this.delegate = delegate;
            this.extra = extra;
        }

        @Override public String getName() { return delegate.getName(); }
        @Override public String getDescription() { return delegate.getDescription(); }
        @Override public String getSystemPrompt(java.util.function.Function<String, String> mp) {
            return delegate.getSystemPrompt(mp);
        }
        @Override public List<com.aliyun.odps.agentic.tool.ToolDef> getTools() {
            List<com.aliyun.odps.agentic.tool.ToolDef> tools = new ArrayList<>(delegate.getTools());
            tools.add(extra);
            return tools;
        }
        @Override public List<com.aliyun.odps.agentic.mcp.McpServerConfig> getMcpServers() { return delegate.getMcpServers(); }
        @Override public List<com.aliyun.odps.agentic.skill.SkillConfig> getSkills() { return delegate.getSkills(); }
        @Override public java.util.Set<String> getIncludedSkills() { return delegate.getIncludedSkills(); }
        @Override public List<String> getSkillRepositories() { return delegate.getSkillRepositories(); }
        @Override public String getMode() { return delegate.getMode(); }
        @Override public java.util.Optional<com.aliyun.odps.agentic.agent.ModelConfig> getAgentModel() { return delegate.getAgentModel(); }
        @Override public List<com.aliyun.odps.agentic.permission.Rule> getPermissionRules() { return delegate.getPermissionRules(); }
        @Override public int getMaxSteps() { return delegate.getMaxSteps(); }
        @Override public com.aliyun.odps.agentic.agent.ModelConfig getModel() { return delegate.getModel(); }
        @Override public java.util.Optional<Double> getTemperature() { return delegate.getTemperature(); }
        @Override public java.util.Optional<Double> getTopP() { return delegate.getTopP(); }
        @Override public java.util.Optional<Boolean> getEnableThinking() { return delegate.getEnableThinking(); }
        @Override public com.aliyun.odps.agentic.memory.MemoryConfig getMemoryConfig() { return delegate.getMemoryConfig(); }
        @Override public List<String> getInstructionFiles() { return delegate.getInstructionFiles(); }
        @Override public java.util.Set<String> getIncludedTools() { return delegate.getIncludedTools(); }
        @Override public Map<String, Object> getToolContextExtra() { return delegate.getToolContextExtra(); }
    }

    // ── 访问器 ──────────────────────────────────

    /**
     * 获取工具注册表，可用于注册自定义工具。
     *
     * @return 工具注册表
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * 获取技能加载器。
     *
     * @return 技能加载器
     */
    public SkillLoader getSkillLoader() {
        return skillLoader;
    }

    /**
     * 获取工作目录。
     *
     * @return 工作目录
     */
    public Path getWorkDir() {
        return workDir;
    }

    /**
     * 获取引擎默认模型。
     *
     * @return 模型
     */
    public Model getModel() {
        return model;
    }

    /**
     * 获取权限服务。
     *
     * @return 权限服务
     */
    public PermissionService getPermissionService() {
        return permissionService;
    }

    /**
     * 获取消息存储。
     *
     * @return 消息存储
     */
    public MessageStore getMessageStore() {
        return messageStore;
    }

    /**
     * 获取 OpenTelemetry 追踪器。
     *
     * @return 追踪器
     */
    public OpenTelemetry.OtelTracer getTracer() {
        return tracer;
    }

    /**
     * 获取 OpenTelemetry 指标收集器。
     *
     * @return 指标收集器
     */
    public OpenTelemetry.OtelMetrics getMetrics() {
        return metrics;
    }

    /**
     * 获取指定会话的消息列表。
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    public List<Message> getMessages(String sessionId) {
        return messageStore.getMessages(sessionId);
    }

    /**
     * Cooperatively cancel the active run for a session.
     *
     * <p>The run loop observes this flag at LLM/tool boundaries. Callers that own the
     * worker thread may additionally interrupt that thread to stop blocking I/O promptly.
     *
     * @param sessionId active session id
     * @return {@code true} when an active run was found and cancellation was requested
     */
    public boolean cancel(String sessionId) {
        if (sessionId == null) {
            log.warn("cancel called with null sessionId, ignored");
            return false;
        }
        if (sessionId.isBlank()) return false;
        RunLoop runLoop = activeRuns.get(sessionId);
        if (runLoop == null) {
            log.debug("cancel called for unknown session: {}, ignored", sessionId);
            return false;
        }
        runLoop.cancel();
        return true;
    }

    /** Returns whether the given session currently has an active top-level run. */
    public boolean isRunning(String sessionId) {
        return sessionId != null && activeRuns.containsKey(sessionId);
    }

    /**
     * 引导（steering，对齐 OpenCode）：向有活动运行的会话注入一条用户消息。
     *
     * <p>运行循环在每次迭代边界都会重读消息存储，注入的消息随下一次 LLM 调用进入
     * 上下文 —— 不打断当前步骤、不新建运行，当前流式事件通道会持续输出对它的响应。
     * 连续注入多条即形成有序队列效果，循环按序逐条响应。
     *
     * @param sessionId 会话 ID
     * @param agentName 代理名（记入消息元数据）
     * @param text      用户消息文本
     * @return 注入成功返回 {@code true}；会话无活动运行，或循环已收尾/取消返回
     *         {@code false} —— 此时调用方应走正常 {@link #run} 路径
     */
    public boolean steer(String sessionId, String agentName, String text) {
        if (sessionId == null || text == null || text.isBlank()) return false;
        RunLoop runLoop = activeRuns.get(sessionId);
        if (runLoop == null) return false;
        Message msg = new Message(
            Identifier.messageId(),
            sessionId,
            Role.USER,
            null, null,
            List.of(new MessagePart.TextPart(text)),
            agentName,
            null, null, null, null, null, null,
            Instant.now()
        );
        return runLoop.injectSteer(msg);
    }

    // ── 会话快照（外部持久化） ───────────────────

    /**
     * 导出会话快照，用于外部持久化或审计。
     *
     * @param session 会话
     * @return 会话快照
     */
    public SessionSnapshot exportSession(Session session) {
        List<Message> messages = messageStore.getMessages(session.id());
        if (messages == null || messages.isEmpty()) {
            messages = session.messages();
        }
        return SessionSnapshot.from(session.withMessages(
            messages == null ? List.of() : messages));
    }

    /**
     * 从快照重建可运行的会话。
     *
     * @param snapshot 会话快照
     * @param agent    代理定义
     * @return 重建后的会话
     */
    public Session importSession(SessionSnapshot snapshot, AgentDef agent) {
        return createSession(agent, snapshot.sessionId(), snapshot.messages());
    }

    // ── MCP 与代理工具 ──────────────────────────

    /**
     * 连接代理定义中的 MCP 服务器并注册发现的工具。
     */
    private void connectMcpServers(AgentDef agent) {
        List<McpServerConfig> servers = agent.getMcpServers();
        if (servers == null || servers.isEmpty()) return;

        for (McpServerConfig server : servers) {
            McpClient existing = mcpClients.get(server.name());
            if (existing != null && existing.isConnected(server.name())) continue;

            McpClient client = server.isRemote()
                ? new SseMcpClient()
                : new StdioMcpClient();

            try {
                List<McpToolDef> tools = client.connect(server);
                mcpClients.put(server.name(), client);
                for (McpToolDef tool : tools) {
                    toolRegistry.register(tool);
                }
            } catch (Exception e) {
                log.warn(
                    "Failed to connect MCP server: " + server.name(), e);
            }
        }
    }

    /**
     * 断开运行结束后的 MCP 服务器连接。
     */
    private void cleanupMcpServers(AgentDef agent) {
        for (McpClient client : mcpClients.values()) {
            try {
                for (String server : client.listServers()) {
                    client.disconnect(server);
                }
            } catch (Exception e) {
                log.warn(
                    "Error disconnecting MCP servers", e);
            }
        }
        mcpClients.clear();
    }

    // ── 内部实现 ────────────────────────────────

    private <T> T withAgentContext(AgentDef agent, java.util.function.Supplier<T> action) {
        connectMcpServers(agent);
        try {
            return action.get();
        } finally {
            cleanupMcpServers(agent);
        }
    }

    private <T> T withTrackedRun(Session session, RunLoop runLoop,
                                 java.util.function.Supplier<T> action) {
        Objects.requireNonNull(session, "session");
        RunLoop existing = activeRuns.putIfAbsent(session.id(), runLoop);
        if (existing != null) {
            throw new IllegalStateException("Session already has an active run: " + session.id());
        }
        try {
            return action.get();
        } finally {
            activeRuns.remove(session.id(), runLoop);
        }
    }

    private Model resolveModel(Session session) {
        return session != null && session.model() != null ? session.model() : model;
    }

    private RunLoop createRunLoop(java.util.function.Consumer<AgentEvent> eventConsumer, Model effectiveModel) {
        permissionService.setEventListener(permEvent -> {
            switch (permEvent) {
                case com.aliyun.odps.agentic.permission.PermissionService.PermissionEvent.Asked asked ->
                    eventConsumer.accept(new AgentEvent.PermissionAsked(asked.request()));
                case com.aliyun.odps.agentic.permission.PermissionService.PermissionEvent.Replied replied ->
                    eventConsumer.accept(new AgentEvent.PermissionReplied(replied.requestId(), replied.reply()));
            }
        });
        return new RunLoop(
            llmClient, toolRegistry, compactionEngine, permissionEngine,
            systemPromptBuilder, patchEngine, eventConsumer, effectiveModel,
            workDir.toAbsolutePath().toString(), skillLoader, messageStore, permissionService
        );
    }

    // ── 构建器 ──────────────────────────────────

    /**
     * 创建新的引擎构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 运行结果，包含最终消息和全部事件。
     *
     * @param finalMessage 最终助手消息
     * @param events       运行过程中产生的全部事件
     */
    public record RunResult(Message finalMessage, List<AgentEvent> events) {}

    /**
     * 用于实时消费代理事件的函数式接口。
     */
    @FunctionalInterface
    public interface AgentEventConsumer {
        /**
         * 处理一个代理事件。
         *
         * @param event 代理事件
         */
        void onEvent(AgentEvent event);
    }

    /**
     * 关闭引擎，清理 MCP 连接、子代理和事件总线等资源。
     */
    public void shutdown() {
        activeRuns.values().forEach(RunLoop::cancel);
        activeRuns.clear();
        for (String server : mcpClients.keySet().stream().toList()) {
            try {
                mcpClients.get(server).disconnect(server);
            } catch (Exception e) {
                log.warn("Failed to disconnect MCP server {}", server, e);
            }
        }
        mcpClients.clear();
        subAgentSpawner.shutdown();
        eventBus.shutdown();
        virtualThreadExecutor.shutdown();
    }

    /**
     * 获取事件总线，用于订阅代理事件。
     *
     * @return 事件总线
     */
    public EventBus getEventBus() {
        return eventBus;
    }

    /**
     * 获取子代理派生器。
     *
     * @return 子代理派生器
     */
    public SubAgentSpawner getSubAgentSpawner() {
        return subAgentSpawner;
    }

    /**
     * 获取快照跟踪器。
     *
     * @return 快照跟踪器
     */
    public SnapshotTracker getSnapshotTracker() {
        return snapshotTracker;
    }

    /**
     * {@link HarnessEngine} 的构建器。
     *
     * <p>必须：{@code llmClient}
     * <p>可选：{@code model}、{@code workDir}、{@code permissionRules}、
     * {@code memoryConfig}、{@code skillPaths}、{@code skillRepositories}
     */
    public static class Builder {
        private Path workDir = Path.of(".");
        private LLMClient llmClient;
        private Model model;
        private List<Rule> permissionRules = List.of();
        private MemoryConfig memoryConfig = MemoryConfig.defaultConfig();
        private List<Path> skillPaths = List.of();
        private List<String> skillRepositories = List.of();
        private AsyncPermissionAsker asyncPermissionAsker;
        private OpenTelemetry.OtelConfig otelConfig;
        private MessageStore messageStore;
        private Duration permissionAskTimeout = Duration.ofMinutes(5);

        /** 设置工作目录。 */
        public Builder workDir(Path workDir) { this.workDir = workDir; return this; }
        /** 设置 LLM 客户端（必须）。 */
        public Builder llmClient(LLMClient llmClient) { this.llmClient = llmClient; return this; }
        /** 设置默认模型。 */
        public Builder model(Model model) { this.model = model; return this; }
        /** 设置权限规则列表。 */
        public Builder permissionRules(List<Rule> rules) { this.permissionRules = rules; return this; }
        /**
         * 设置记忆配置。
         *
         * @deprecated 0.4.0 起如实标注：该配置当前<b>未被引擎消费</b>——长期记忆子系统
         * （{@code MemoryStore} 接入运行循环）在后续版本（P2）提供。设置它不会产生任何效果。
         */
        @Deprecated
        public Builder memoryConfig(MemoryConfig config) { this.memoryConfig = config; return this; }
        /** 设置技能搜索路径。 */
        public Builder skillPaths(List<Path> paths) { this.skillPaths = paths; return this; }
        /** 设置技能仓库列表。 */
        public Builder skillRepositories(List<String> repos) { this.skillRepositories = repos; return this; }
        /** 设置异步权限询问器。 */
        public Builder asyncPermissionAsker(AsyncPermissionAsker asker) { this.asyncPermissionAsker = asker; return this; }
        /** 启用 OpenTelemetry。 */
        public Builder otelConfig(OpenTelemetry.OtelConfig config) { this.otelConfig = config; return this; }
        /** 设置外部消息存储；默认使用 {@link InMemoryMessageStore}。 */
        public Builder messageStore(MessageStore messageStore) { this.messageStore = messageStore; return this; }

        /**
         * 设置权限询问的 SDK 侧超时。
         *
         * <p>{@code Duration.ZERO} 或 {@code null} 表示不设 SDK 侧超时（由调用方管理生命周期）。
         * 默认 5 分钟（向后兼容）。
         *
         * @param timeout 超时时长；{@code null} 或零/负值表示不超时
         * @return this
         */
        public Builder permissionAskTimeout(Duration timeout) { this.permissionAskTimeout = timeout; return this; }

        /**
         * 构建引擎实例。
         *
         * @return 引擎实例
         * @throws IllegalStateException 当 {@code llmClient} 未设置时
         */
        public HarnessEngine build() {
            if (llmClient == null) {
                throw new IllegalStateException("LLMClient is required. Use builder().llmClient(...).build()");
            }
            return new HarnessEngine(this);
        }
    }
}
