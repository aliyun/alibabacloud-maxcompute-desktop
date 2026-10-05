package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

/**
 * 网页搜索工具，用于在互联网上检索信息。
 * 当前为占位实现，返回提示信息而不真正调用搜索提供者。
 */
public class WebSearchTool implements ToolDef {

    @Override
    public String getId() { return "websearch"; }

    // 只读工具：可与其他只读工具并行执行（0.4.0）。
    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String getDescription() {
        return "Search the web for information. Returns search results with titles, URLs, and snippets.";
    }

    /**
     * 返回 websearch 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "query": "Claude Code documentation"
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode queryProp = props.putObject("query");
        queryProp.put("type", "string");
        queryProp.put("description", "The search query");

        schema.putArray("required").add("query");
        return schema;
    }

    /**
     * 执行网页搜索（当前为占位实现）。
     *
     * <p>当前行为：返回提示信息，告知用户需要配置 MCP 搜索服务器或搜索 API 密钥。
     * 生产环境可对接 Google/Bing/SerpAPI 等搜索提供者实现真正的检索能力。
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String query = args.path("query").asText("");
        if (query.isBlank()) {
            return ToolResult.error("Search query cannot be empty");
        }
        // Stub: in production, integrate with search API
        return ToolResult.of(
            "Web search for: \"" + query + "\"\n\n" +
            "Note: Web search requires integration with a search provider.\n" +
            "Configure an MCP websearch server or provide a search API key.",
            query
        );
    }
}
