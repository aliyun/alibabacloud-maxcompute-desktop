# Changelog

Agentic SDK 的源码开发记录。以下版本标题不代表 Maven Central 已发布状态，实际发布以仓库版本标签和 Central 为准。

## [Unreleased]

### 独立 Agentic 内核

- 统一会话运行、SDK 权威消息历史、宿主完成策略与工具批次扩展。
- 支持持久化挂起恢复、运行中插话、结构化多模态消息和非对话模型操作生命周期。
- 移除模型名称驱动的旧提示词模板，使用自主编写的通用及辅助提示词。
- POM 独立于 Studio 父项目，保留现有 Maven 坐标并提供 sources、Javadoc 和签名发布配置。
- 增加 Java 21 / 25 CI、消费者产物校验、可编译 QuickStart 和 Maven Central 标签发布流程。

## [0.4.0] - 2026-08-23

### 行为变更（含破坏性）

这是一次**生产韧性**版本：把三套「机制存在但在出货路径上一次都打不着」的韧性机制
（重试、溢出压缩恢复、思考链 token 记账）从死代码变为真实可达，并补齐主路的生产化加固。
伴随若干行为变化，宿主升级前请阅读 `MIGRATION.md`。

### Fixed（韧性机制救活）

- **重试机制可达**：`SseLlmClient` 此前全文零 `throw`，HTTP≥400 与网络/IO 故障被静默吞成
  `ProviderError` 事件，导致 `RunLoop` 的 3 次退避重试永不触发。现以非受检
  `LlmApiException`（携带 status/retryable/headers/body）抛出，`RunLoop` 据此进入重试判定。
- **请求超时接通**：`SseLlmClient.REQUEST_TIMEOUT`（5 分钟）此前声明后零引用；现已接到
  `.timeout(...)`，且修复了 `X-Session-Id` 重建请求时丢失超时设置的问题——半开连接不再无限挂住。
- **溢出压缩恢复可达**：`StreamProcessor` 构造助手消息时 `error` 字段此前恒为 `null`（错误只进正文
  `[Error: ...]`），`RunLoop` 的溢出后置恢复分支因此永不触发。现填充 `Message.error`，
  并修复 `RunLoop` 顶部出口在压缩任务执行前误拦截溢出错误消息的顺序问题。
- **reasoning token 记账救活**：`StreamProcessor.extractUsage` 此前用四参兼容构造合并用量，
  `reasoningTokens` 在 `fromUsage` 之前就被归零。现改用五参构造保留真值。

### Fixed（主路加固）

- **成本按真实单价计费**：`calculateCost` 此前硬编码 Sonnet 费率（3.0/15.0），跑 qwen/DashScope
  时账面数字错误。现按 `ModelCatalog` 中模型实际单价计费，查不到时回退默认费率。
- **在途流可取消**：`cancel()` 此前只置协作标志，阻塞中的 HTTP 调用无法中断。现会中断运行线程，
  在途 LLM 流式调用立即中止，不再空烧 token。
- **权限 fail-closed**：`PermissionService` 的 `askResult` 回调从 `thenAccept` 改为 `whenComplete`，
  宿主 asker 异常完成时 fail-closed 为 REJECT；`future.get()` 增加可配置超时（默认 5 分钟），
  超时同样 fail-closed——整个 run 不再因审批悬挂而死锁。
- **Permission timeout collision**: SDK-side and host-side timeouts no longer race; added `permissionAskTimeout(Duration.ZERO)` builder option to let host manage timeout exclusively, and `PermissionTimeoutException` for clean termination when SDK timeout fires.
- **doom-loop 检测修复并可观测**：修复了窗口错位导致 doom-loop 在主循环几乎永不触发的缺陷；
  命中后发出 `DoomLoopDetected` 事件并给模型一次纠偏提示，不再静默 break。
- **重试可观测**：重试时发出 `AgentEvent.Retrying` 事件，前端不再只看到黑屏卡住。
- **结构化输出类型安全**：新增 `HarnessEngine.runStructured(session, agent, msg, Class<T>)`，
  从 `Class<T>` 生成 JSON Schema、注入绑定该 Schema 的工具并反序列化为 POJO。
  移除了「在用户文本里嗅探 `json_schema` 子串」的脆弱做法。
- **只读工具并行**：`ToolDef.isReadOnly()`（默认 `false`）标记只读工具（Read/Glob/Grep/WebFetch/
  WebSearch 已标记）；一批连续的只读调用并行执行，写工具仍串行，结果顺序与声明一致。
- **SSE 解析健壮**：容忍 `data:` 后无空格、多行 `data` 拼接、`event:`/`id:`/`retry:` 字段、
  `:` 注释行；响应流显式关闭。
- **MCP 协议版本**：握手版本从 `2024-11-05` 升到 `2025-06-18`，并协商/回读服务端实际版本。
- **Anthropic 缓存窗口**：稳定系统提示词前缀的 `cache_control` 增加 `ttl: "1h"`。

### Added（测试安全网）

- `FaultInjectionResilienceTest`：端到端把「注入故障 → 观测到重试/压缩/计数」串起来断言。
- `InFlightCancellationTest`、`ParallelToolExecutionTest`、`StructuredOutputTest`、
  `CostCalculationTest`、`ProviderMessageTransformTest`、`ShellToolTest`。

### Changed（诚信修复）

- `otel/OpenTelemetry`：如实标注为**进程内**遥测总线（不导出 OTLP 后端）；
  `OtelConfig.exporterEndpoint` 标注为预留字段（当前未被消费）。
- `HarnessEngine.Builder.memoryConfig`：标注 `@Deprecated`——记忆子系统尚未接入运行循环（P2）。
- README 示例包名从 `com.opencode.harness.*` 修正为实际的 `com.aliyun.odps.agentic.*`；
  `CodingAssistant` 示例移到正确的 `example` 包目录；CHANGELOG 移除不实的 `module-info.java` 声明。

## [0.3.0-SNAPSHOT] - 2026-06-30

### Changed
- **Profile-scoped skills**: `AgentDef.getIncludedSkills()` filters both the advertised skill
  catalog and runtime `skill` loading, allowing one engine to host multiple domain profiles
  without cross-loading unrelated skills.
- **Run-scoped Agent tools**: `AgentDef.getTools()` are resolved per run instead of being
  registered into the engine-global registry, so concurrent sessions can safely use the same
  tool id with different request context.
- **RunLoop request construction**: Main route now computes and passes `providerOptions`, `temperature`, and `maxTokens` through `LlmRequest`, aligning the production path with the unified provider semantic layer.
- **ProviderOptions**: Switched to provider-namespaced output consumed directly by transforms (for example `anthropic`, `openai`, and `openaiCompatible`) instead of flat helper-only values.
- **Temperature semantics**: Aligned with upstream OpenCode so explicit agent temperature wins, while provider/model defaults are only applied when the agent does not set one.
- **Reasoning replay routing**: Broadened `reasoning_content` replay beyond DeepSeek-only handling so compatible Alibaba/Qwen-style models can reuse the shared path.

### Added
- **Session cancellation**: `HarnessEngine.cancel(sessionId)` and `isRunning(sessionId)` expose
  cooperative cancellation and active-run state for embedding applications.
- **Alibaba/Qwen reasoning support**: Added `enable_thinking` emission for reasoning-capable `alibaba-cn` OpenAI-compatible models so `reasoning_content` can be returned.
- **OpenAI-compatible streaming fidelity**: Added support for `stream_options.include_usage`, `reasoning_content` deltas, usage-only SSE chunks, provider error chunks, and `reasoning_tokens` mapping in the generic OpenAI-compatible transform.
- **Regression coverage**: Added and updated tests covering RunLoop request wiring, namespaced provider options, Alibaba/Qwen `enable_thinking`, reasoning deltas, usage-only chunks, provider errors, and reasoning token accounting.

## [0.1.0] - 2026-06-04

### Added
- **HarnessEngine**: Main SDK entry point with builder pattern
- **AgentDef SPI**: Pluggable agent definition interface
- **AgentDefBuilder**: Fluent builder for agent definitions
- **12 Builtin Tools**: apply_patch, edit, read, write, shell, glob, grep, task, question, todo, webfetch, skill
- **PatchEngine**: 4-level fuzzy matching (exact→trim→normalized→Levenshtein)
- **LLM Integration**: SSE streaming with Anthropic and OpenAI support
- **ProviderTransform SPI**: Extensible LLM provider interface
- **MCP Integration**: Model Context Protocol client with stdio transport
- **CompactionEngine**: 3-route context management (COMPACT/TRUNCATE/NONE) + LLM summary
- **PermissionEngine**: Glob-based permission rules with ALLOW/DENY/ASK actions
- **SkillLoader**: SKILL.md frontmatter discovery from workspace directories
- **SessionStore**: JSON file persistence with memory cache
- **Message Model**: 15 MessagePart types, ToolCallState sealed interface
- **SystemPromptBuilder**: 5-layer system prompt construction
- **StreamProcessor**: LLM event → Message processing with doom loop detection
- **RunLoop**: 9-step while-true agent execution loop
- **Examples**: CodingAssistant + CodeReviewAgent
- **119 unit tests**: All passing ✅

### Distilled From
- opencode v1.15.13 (TypeScript/Effect → Java 21)
- Source: https://github.com/anomalyco/opencode
