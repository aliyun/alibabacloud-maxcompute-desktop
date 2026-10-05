package com.aliyun.odps.agentic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aliyun.odps.agentic.session.AbortSignal;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 stdio 传输的默认 MCP 客户端实现。
 * 负责启动服务器进程、初始化协议、发现工具并执行工具调用。
 */
public class StdioMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(StdioMcpClient.class);
    private static final int MAX_LIST_PAGES = 1000;

    private final Map<String, McpConnection> connections = new ConcurrentHashMap<>();

    @Override
    public List<McpToolDef> connect(McpServerConfig config) {
        McpConnection conn = null;
        try {
            conn = McpConnection.spawn(config);
            connections.put(config.name(), conn);

            JsonNode initResult = conn.sendRequest("initialize", Map.of(
                "protocolVersion", McpClient.PREFERRED_PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agentic-sdk", "version", "1.0.0")
            ));

            // 协议版本协商：服务端回它实际使用的版本（可能更低）。
            if (initResult != null && initResult.has("protocolVersion")) {
                String negotiated = initResult.get("protocolVersion").asText();
                if (!McpClient.PREFERRED_PROTOCOL_VERSION.equals(negotiated)) {
                    log.info("MCP server '{}' negotiated protocol version {} (client preferred {})",
                        config.name(), negotiated, McpClient.PREFERRED_PROTOCOL_VERSION);
                }
            }

            conn.sendNotification("notifications/initialized", Map.of());

            JsonNode capabilities = initResult != null ? initResult.path("capabilities") : null;
            boolean hasToolCapability = capabilities != null
                && !capabilities.isMissingNode()
                && capabilities.has("tools");

            if (!hasToolCapability) {
                log.warn("MCP server '{}' does not advertise tool capability, skipping tool discovery", config.name());
                return List.of();
            }

            List<McpToolDef> tools = new ArrayList<>();
            String cursor = null;
            Set<String> seenCursors = new HashSet<>();

            for (int page = 0; page < MAX_LIST_PAGES; page++) {
                Map<String, Object> params = cursor != null ? Map.of("cursor", cursor) : Map.of();
                JsonNode toolsResult = conn.sendRequest("tools/list", params);

                if (toolsResult.has("tools")) {
                    for (JsonNode tool : toolsResult.get("tools")) {
                        String toolName = tool.get("name").asText();
                        String description = tool.has("description") ? tool.get("description").asText() : "";
                        JsonNode schema = tool.has("inputSchema") ? tool.get("inputSchema") : null;
                        tools.add(new McpToolDef(config.name(), toolName, description, schema, this));
                    }
                }

                if (toolsResult.has("nextCursor") && !toolsResult.get("nextCursor").isNull()) {
                    String nextCursor = toolsResult.get("nextCursor").asText();
                    if (seenCursors.contains(nextCursor)) {
                        log.warn("MCP server '{}' returned duplicate cursor, stopping pagination", config.name());
                        break;
                    }
                    seenCursors.add(nextCursor);
                    cursor = nextCursor;
                } else {
                    break;
                }
            }

            return tools;

        } catch (Exception e) {
            log.warn("Failed to connect to MCP server '{}': {}", config.name(), e.getMessage());
            if (conn != null) {
                conn.close();
                connections.remove(config.name());
            }
            return List.of();
        }
    }

    @Override
    public void disconnect(String serverName) {
        McpConnection conn = connections.remove(serverName);
        if (conn != null) {
            conn.close();
        }
    }

    @Override
    public List<String> listServers() {
        return List.copyOf(connections.keySet());
    }

    @Override
    public boolean isConnected(String serverName) {
        return connections.containsKey(serverName);
    }

    @Override
    public ToolResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        return callTool(serverName, toolName, arguments, null);
    }

    @Override
    public ToolResult callTool(String serverName, String toolName, Map<String, Object> arguments,
                               AbortSignal abortSignal) {
        McpConnection conn = connections.get(serverName);
        if (conn == null) {
            return ToolResult.error("MCP server not connected: " + serverName);
        }

        if (abortSignal != null && abortSignal.isAborted()) {
            return ToolResult.error("MCP tool call aborted before execution");
        }

        try {
            JsonNode result = conn.sendRequest("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
            ));

            if (abortSignal != null && abortSignal.isAborted()) {
                return ToolResult.error("MCP tool call aborted");
            }

            StringBuilder text = new StringBuilder();
            boolean isError = false;

            if (result.has("content")) {
                for (JsonNode content : result.get("content")) {
                    if (content.has("text")) {
                        text.append(content.get("text").asText());
                    }
                    if (content.has("isError") && content.get("isError").asBoolean()) {
                        isError = true;
                    }
                }
            }

            if (result.has("isError") && result.get("isError").asBoolean()) {
                isError = true;
            }

            if (isError) {
                return ToolResult.error(text.toString());
            }
            return ToolResult.success(text.toString());

        } catch (McpException e) {
            return ToolResult.error("MCP tool call failed: " + e.getMessage());
        }
    }
}
