# Agentic SDK 内核与宿主边界

## 运行入口

`HarnessEngine` 接收 `AgentDef`、`LLMClient`、模型及消息存储，创建并运行会话。
`RunLoop` 推进模型调用与工具批次，`StreamProcessor` 将流式输出转换为消息和事件。
`AgentDef` 可以自定义工具目录、精确提示词、完成策略和宿主执行器。

## 扩展接口

| 接口 | 内核职责 | 宿主职责 |
| --- | --- | --- |
| `AgentDef` | 读取代理声明，建立一次运行的上下文 | 定义任务角色、提示词、工具、技能与策略 |
| `LLMClient` / `ProviderTransform` | 调用模型、解释流式事件和规范化协议数据 | 配置服务地址、鉴权、模型及供应商特性 |
| `ToolDef` | 广告工具 Schema、执行与保存结果 | 实现领域动作、参数校验和权限检查 |
| `ToolBatchExecutor` | 校验调用与结果配对，保持声明次序，消费早发完成结果 | 对接已有执行流水线，安排业务依赖与并行调度 |
| `RunPolicy` | 在步骤、模型调用、工具批次与结束边界调用策略 | 接入计划、异步观察、结果验收、终止或挂起决定 |
| `MessageStore` | 保存运行使用的消息和工具结果 | 提供存储实现及产品历史投影 |
| `AsyncPermissionAsker` | 管理异步回复与超时 | 展示授权 UI、持久化业务确认状态并传回决议 |
| `AgentEvent` | 发布文本、工具、权限、压缩及终态事件 | 生成 SSE、UI 轨迹、日志与外部遥测 |

## 历史与恢复

内核的消息历史用于后续模型调用，业务 UI 可以按自己的协议投影这些消息。
SQLite 实现支持消息批次事务和稳定排序，消息中的扩展元数据可供宿主重建业务投影。

`SessionSnapshot` 包含会话身份、消息与相关会话信息；业务审批、远程作业、画布回执
和产品 Checkpoint 由宿主持久化。宿主在这些状态恢复之后，可使用 `resumeUntilPause`
继续同一目标，并将新的观察结果写入相应消息历史。

## 上下文管理

默认压缩通过 SDK 的摘要和裁剪路径缩小并替换历史。需要保留完整审计历史的应用
可以声明 `hostManagesContextProjection()`，由宿主构建有预算的模型输入投影。
`TextConversationProjection` 和 `ChunkedTextSummary` 可用于这一流程。
协议适配器负责保留提供者要求的结构化内容与签名；纯文本摘要不是原始多模态内容的替代品。

## 非对话模型操作

`ModelOperationRunner` 接受宿主的类型化操作，发布开始、进度、完成或失败事件。
`SQLiteModelOperationStore` 可以保存这些事件。操作的响应值继续由宿主持有，
记录操作事件并不表示媒体产物已经成功生成或持久化。

实时 WebSocket 传输属于 Java SDK；Citywalk 的浏览器适配保存在 `web/`，
使用供应商 JS SDK 完成视频会话，需由宿主 TypeScript 工程编译。

## 模块导航

Java 源码根目录是 `src/main/java/com/aliyun/odps/agentic/`。

| 模块 | 主要入口 |
| --- | --- |
| Agent 定义 | `agent/AgentDef.java`、`agent/AgentDefBuilder.java` |
| 会话运行 | `session/RunLoop.java`、`session/RunPolicy.java`、`session/ToolBatchExecutor.java` |
| 消息与存储 | `model/Message.java`、`model/MessagePart.java`、`session/MessageStore.java` |
| 模型协议 | `llm/LLMClient.java`、`llm/ProviderTransform.java`、`llm/provider/ProviderManager.java` |
| 工具与补丁 | `tool/ToolDef.java`、`tool/ToolRegistry.java`、`patch/PatchEngine.java` |
| 权限与技能 | `permission/PermissionService.java`、`skill/SkillLoader.java` |
| MCP | `mcp/StdioMcpClient.java`、`mcp/SseMcpClient.java` |
| 模型操作 | `operation/ModelOperationRunner.java`、`operation/RealtimeWebSocketTransport.java` |

设计与实现借鉴了 OpenCode 的架构和代码。版权和许可证说明见 [NOTICE](../NOTICE)。
