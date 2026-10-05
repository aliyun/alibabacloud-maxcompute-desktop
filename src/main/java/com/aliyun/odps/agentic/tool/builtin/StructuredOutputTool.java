package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

/**
 * 结构化输出工具，用于接收符合指定 JSON Schema 的输出。
 * 当用户要求以结构化格式返回结果时，可动态注入该工具并捕获 LLM 提交的数据。
 */
public class StructuredOutputTool implements ToolDef {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String id;
    private final ObjectNode parametersSchema;
    private final java.util.function.Consumer<JsonNode> onSuccess;

    /**
     * 创建结构化输出工具。
     *
     * @param schema    定义预期输出结构的 JSON Schema
     * @param onSuccess 工具被调用时触发的回调，参数为结构化输出
     */
    public StructuredOutputTool(ObjectNode schema, java.util.function.Consumer<JsonNode> onSuccess) {
        this.onSuccess = onSuccess;
        this.parametersSchema = MAPPER.createObjectNode();
        this.parametersSchema.put("type", "object");
        ObjectNode props = MAPPER.createObjectNode();
        props.set("output", schema);
        this.parametersSchema.set("properties", props);
        this.parametersSchema.put("required", MAPPER.createArrayNode().add("output"));
        this.id = "StructuredOutput";
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getDescription() {
        return "Output structured data conforming to the specified schema.";
    }

    /**
     * 返回结构化输出工具的参数 Schema。
     *
     * <p>该工具会把调用方传入的业务 Schema 包装到顶层 {@code output} 字段下。
     * 对应参数示例：
     * <pre>{@code
     * {
     *   "output": {
     *     "title": "HarnessEngine",
     *     "summary": "SDK entry point"
     *   }
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        return parametersSchema;
    }

    /**
     * 捕获 LLM 生成的结构化输出。
     *
     * <p>执行流程：
     * <ol>
     *   <li>从 {@code args.output} 中提取 LLM 按 Schema 生成的结构化数据</li>
     *   <li>若注册了 {@code onSuccess} 回调，将结构化数据传入回调（供调用方消费）</li>
     *   <li>返回序列化后的 JSON 文本作为工具结果</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        JsonNode output = args.path("output");
        if (onSuccess != null) {
            onSuccess.accept(output);
        }
        return ToolResult.of("Structured output captured", output.toString());
    }
}
