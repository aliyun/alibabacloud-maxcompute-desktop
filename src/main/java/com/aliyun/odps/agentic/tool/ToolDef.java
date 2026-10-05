package com.aliyun.odps.agentic.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 工具定义接口，定义 LLM 可调用的工具。
 * 每个工具需提供唯一标识、描述、参数 JSON Schema 以及执行逻辑。
 *
 * <p>实现示例：
 * <pre>{@code
 * public class MyTool implements ToolDef {
 *     public String getId() { return "my_tool"; }
 *     public String getDescription() { return "Execute a SQL query"; }
 *     public ObjectNode getParametersSchema() {
 *         ObjectNode schema = MAPPER.createObjectNode();
 *         schema.put("type", "object");
 *         ObjectNode props = MAPPER.createObjectNode();
 *         ObjectNode sql = MAPPER.createObjectNode();
 *         sql.put("type", "string");
 *         sql.put("description", "The SQL statement to execute");
 *         props.set("sql", sql);
 *         schema.set("properties", props);
 *         schema.set("required", MAPPER.createArrayNode().add("sql"));
 *         return schema;
 *     }
 *     public ToolResult execute(JsonNode args, ToolContext context) {
 *         String sql = args.get("sql").asText();
 *         // ... 执行查询 ...
 *         return ToolResult.of("Query Result", resultText);
 *     }
 * }
 * }</pre>
 */
public interface ToolDef {

    /**
     * 工具标识符，LLM 通过该 ID 引用此工具。
     * 在同一个 {@link com.aliyun.odps.agentic.tool.ToolRegistry} 中必须唯一。
     */
    String getId();

    /**
     * 工具的可读描述，告知 LLM 该工具的功能和使用场景。
     * 该文本会直接嵌入 LLM 请求的 {@code tools} 数组中，
     * LLM 根据此描述决定何时调用该工具。
     */
    String getDescription();

    /**
     * 工具输入参数的 JSON Schema（符合 JSON Schema Draft 规范）。
     * 返回的 Schema 会发送给 LLM，LLM 据此生成符合约束的参数 JSON。
     *
     * <p>典型返回值结构：
     * <pre>{@code
     * {
     *   "type": "object",
     *   "properties": {
     *     "filePath": { "type": "string", "description": "文件绝对路径" },
     *     "content":  { "type": "string", "description": "文件内容" }
     *   },
     *   "required": ["filePath", "content"]
     * }
     * }</pre>
     *
     * @return 参数 JSON Schema，顶层 {@code type} 应为 {@code "object"}
     */
    ObjectNode getParametersSchema();

    /**
     * 使用给定参数执行工具。
     *
     * <p>执行流程约定：
     * <ol>
     *   <li>从 {@code args} 中提取参数（可用 {@link #extractString} 兼容 camelCase/snake_case）</li>
     *   <li>校验必填参数，缺失时返回 {@link ToolResult#error(String)}</li>
     *   <li>如需用户授权，通过 {@link ToolContext#permissionAsker()} 请求权限</li>
     *   <li>执行核心逻辑（文件操作、网络请求、子进程调用等）</li>
     *   <li>通过 {@link ToolContext#progressReporter()} 上报中间进度（可选）</li>
     *   <li>返回 {@link ToolResult}：成功时用 {@code ToolResult.of(...)}，失败时用 {@code ToolResult.error(...)}</li>
     * </ol>
     *
     * <p>此方法不应抛出异常；所有错误应封装为 {@link ToolResult#error(String)} 返回。
     *
     * @param args    LLM 生成的参数 JSON，结构符合 {@link #getParametersSchema()} 定义
     * @param context 执行上下文，提供会话信息、权限询问和进度上报等能力
     * @return 工具执行结果，包含标题、输出文本和可选元数据
     */
    ToolResult execute(JsonNode args, ToolContext context);

    /**
     * 标记本工具是否为只读（不修改任何外部状态：文件、网络、进程等）。
     *
     * <p>运行循环据此并行化一批连续的只读调用（如多个 Read/Glob/Grep），
     * 缩短墙钟时间；写工具仍保持串行以保证顺序与一致性。
     *
     * <p><b>默认为 {@code false}（保守视为有副作用）</b>。只有当工具确实
     * 线程安全、且多次并发执行互不干扰时，才应返回 {@code true}。
     * 注意：并行执行期间 {@link ToolContext#progressReporter()} 与生命周期钩子
     * 可能从多个线程并发回调，其实现需自行保证线程安全。
     *
     * @return 只读且可安全并行时返回 {@code true}
     */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * 从参数中提取字符串值，优先使用 camelCase 键，回退到 snake_case。
     * LLM 有时会在 schema 定义 camelCase 时发送 snake_case 键。
     */
    static String extractString(JsonNode args, String camelCase, String snakeCase) {
        if (args.has(camelCase) && !args.get(camelCase).isNull()) {
            return args.get(camelCase).asText();
        }
        if (args.has(snakeCase) && !args.get(snakeCase).isNull()) {
            return args.get(snakeCase).asText();
        }
        return null;
    }
}
