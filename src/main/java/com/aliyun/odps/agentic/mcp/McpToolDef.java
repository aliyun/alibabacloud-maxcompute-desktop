package com.aliyun.odps.agentic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.Objects;

/**
 * MCP 工具定义 -- 表示从 MCP 服务器动态发现的工具。
 * 该类型将 MCP 工具包装为 {@link ToolDef}，以便像内置工具一样注册和执行。
 */
public class McpToolDef implements ToolDef {

    private final String serverName;
    private final String toolName;
    private final String description;
    private final JsonNode parametersSchema;
    private final McpClient mcpClient;

    public McpToolDef(String serverName, String toolName, String description,
                       JsonNode parametersSchema, McpClient mcpClient) {
        this.serverName = serverName;
        this.toolName = toolName;
        this.description = description;
        this.parametersSchema = parametersSchema;
        this.mcpClient = mcpClient;
    }

    @Override
    public String getId() {
        return "mcp__" + serverName + "__" + toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public ObjectNode getParametersSchema() {
        if (parametersSchema != null && parametersSchema.isObject()) {
            return (ObjectNode) parametersSchema;
        }
        return null;
    }

    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        if (args != null && args.isObject()) {
            args.fields().forEachRemaining(entry ->
                arguments.put(entry.getKey(), convertValue(entry.getValue())));
        }
        return mcpClient.callTool(serverName, toolName, arguments);
    }

    private static Object convertValue(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isDouble()) return node.asDouble();
        if (node.isBoolean()) return node.asBoolean();
        return node.asText();
    }

    /**
     * 获取 MCP 服务器名称。
     *
     * @return 服务器名称
     */
    public String serverName() {
        return serverName;
    }

    /**
     * 获取原始 MCP 工具名称，不包含前缀。
     *
     * @return 工具名称
     */
    public String toolName() {
        return toolName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof McpToolDef that)) return false;
        return serverName.equals(that.serverName) && toolName.equals(that.toolName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverName, toolName);
    }

    @Override
    public String toString() {
        return "McpToolDef{id=" + getId() + ", server=" + serverName + "}";
    }
}
