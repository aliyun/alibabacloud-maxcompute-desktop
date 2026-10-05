# 接入与迁移说明

## 独立内核的接入变化

- SDK 可以使用自己的 POM 构建，不再依赖 Studio 父项目；Maven 坐标保持不变。
- 默认提示词统一为通用模板。应用需要角色、工具行为或供应商特定指令时，通过
  `getSystemPrompt` / `getExactSystemPrompt` 自行定义。精确提示词仍原样传入运行循环。
- 示例优先使用 `AGENT_*` 配置，旧 `OPENCODE_*` 环境变量仍保留兼容读取。
- 权限询问、领域工具执行、业务确认和冷恢复状态继续由宿主通过扩展接口接入。
- 长期记忆与 OTLP 导出的当前边界见 [README](README.md)，发布方式见 [发布指南](docs/PUBLISHING.md)。

下文保留早期源码版本的迁移记录；其版本号和目录范围描述不代表当前发布状态。

---

## 历史记录：0.3.x → 0.4.0

0.4.0 是一次**生产韧性**版本。它把三套「机制存在但在出货路径上打不着」的韧性机制变成真实可达，
并补齐主路的生产化加固。多数改动是 bug 修复语义，但有几条**会改变你可观测到的行为**，
升级前请通读本指南。

## 升级坐标

```xml
<dependency>
    <groupId>com.aliyun.odps</groupId>
    <artifactId>agentic-sdk</artifactId>
    <version>0.4.0</version>
</dependency>
```

---

## 会改变行为的点（需要宿主关注）

### 1. LLM 错误现在会真的重试
`SseLlmClient` 此前把 HTTP≥400 / 网络故障静默吞成 `ProviderError` 事件，重试机制是死代码。
现在这些故障以 `LlmApiException` 抛出，`RunLoop` 会对 **429 / 5xx / 网络错误**做指数退避重试
（默认最多 3 次，`retry-after` 头优先）。

- **你会看到**：一次真实运行里 LLM 调用可能比之前多几次尝试；每次重试前发出
  `AgentEvent.Retrying(sessionId, attempt, delayMs, reason)`。
- **需要你做的**：若有自定义 `LLMClient` 实现，确认它把可重试故障以异常抛出（或发
  `ProviderError` 事件——那仍走「不重试、直接落错误消息」的旧路径）。
  若你的 UI 此前自己实现了重试，现在可以交给 SDK。

### 2. 请求有超时了（默认 5 分钟）
此前 `REQUEST_TIMEOUT` 声明后从未接线，半开连接会无限挂住。现在每个 LLM 请求都有超时。
- **你会看到**：挂死的请求会在 5 分钟后抛超时错误（并进入重试/中止），不再永久卡住。
- **需要你做的**：若有合法的「超长生成」场景，目前超时是固定值；如需可调请在 issue 中提出。

### 3. cancel() 现在真的会掐断在途请求
此前 `cancel(sessionId)` 只置协作标志，阻塞中的流式调用继续烧 token 直到提供者自然结束。
现在会中断运行线程，在途调用立即中止。
- **你会看到**：取消后运行几乎立刻结束（发出 `SessionAborted`），最终消息 `finish="error"`、
  `error="Aborted"`。
- **需要你做的**：无。若你依赖「取消后仍收到完整响应」的旧行为（不太可能），请重新评估。

### 4. 成本数字会变（变得正确了）
`calculateCost` 此前对所有模型都按 Sonnet 费率硬编码。现在按 `ModelCatalog` 里的真实单价。
- **你会看到**：跑非 Sonnet 模型时，`Message.cost` 和 `CostUpdate` 事件的金额会变化
  （例如 Haiku 更便宜）。目录查不到的模型回退默认费率并打 debug 日志。
- **需要你做的**：若你把成本数字接到计费/对账，请确认新的按模型计费口径符合预期。
  注意：`ModelCatalog` 当前只收录 Anthropic + OpenAI；跑 DashScope/qwen 会落默认费率
  （仍优于「恒按 Sonnet」，但如需精确单价请扩展目录）。

### 5. 权限询问 fail-closed
宿主 asker 的 future 异常完成、或 5 分钟内不回复，都会 fail-closed 为 **REJECT**，不再死锁整个 run。
- **你会看到**：此前会永久悬挂的场景现在返回「权限拒绝」。
- **需要你做的**：若你的 asker 依赖无限等待（如 `CopilotPermissionAsker` 自己实现了长超时策略），
  现在可以移除那层 workaround，或用它来设定 SDK 的 `askTimeout`。

### 6. doom-loop 检测更准 + 会纠偏
修复了窗口错位导致的「doom-loop 几乎永不触发」。现在连续 3 次相同工具调用会：
发出 `DoomLoopDetected` 事件 → 注入一次纠偏提示给模型 → 仍重复才退出。
- **你会看到**：此前「无理由停住」现在有事件；且模型有一次自我纠偏的机会。

### 7. 结构化输出改为显式 API
移除了「在用户文本里嗅探 `json_schema` 子串」的脆弱做法。
- **需要你做的**：改用 `HarnessEngine.runStructured(session, agent, msg, MyType.class)`，
  它返回 `StructuredResult<T>`（最终消息 + 解析后的 `T`）。

---

## 不破坏源码兼容的点

- `SseLlmClient`、`RunLoop`、`PermissionService` 的公共签名基本不变（`PermissionService`
  新增了一个带超时的构造器，旧构造器仍可用）。
- `ToolDef.isReadOnly()` 是带默认值的 `default` 方法，旧实现无需改动。
- README 示例的包名已修正为 `com.aliyun.odps.agentic.*`（此前文档写的是 `com.opencode.harness.*`，
  代码无法编译——若你照着 README 写过，请更新 import）。

## 已知仍未做（明确出 P0+P1 范围，留待后续）

- 真正的 OpenTelemetry OTLP 导出（`otel` 包目前是进程内遥测总线）。
- 长期记忆子系统接入运行循环（`memoryConfig` 已标注 `@Deprecated`，当前不被消费）。
- handoff / 多代理编排、guardrails、RAG、evals。
- `ModelCatalog` 收录 DashScope/qwen 等更多提供者的精确单价。
