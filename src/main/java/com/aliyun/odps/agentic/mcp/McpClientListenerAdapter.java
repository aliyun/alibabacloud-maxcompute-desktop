package com.aliyun.odps.agentic.mcp;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.McpClientListener;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Adapts langchain4j McpClientListener events to MaxQuery's MCP management layer.
 * Logs lifecycle events and triggers tool-list refresh when the server notifies a change.
 */
public class McpClientListenerAdapter implements McpClientListener {

    private static final Logger log = LoggerFactory.getLogger(McpClientListenerAdapter.class);

    private final String serverName;
    private final Consumer<String> onToolsChanged;

    public McpClientListenerAdapter(String serverName, Consumer<String> onToolsChanged) {
        this.serverName = serverName;
        this.onToolsChanged = onToolsChanged;
    }

    @Override
    public void afterInitialize(McpCallContext context) {
        log.info("[MCP:{}] Connection initialized", serverName);
    }

    @Override
    public void onInitializeError(McpCallContext context, Throwable error) {
        log.warn("[MCP:{}] Initialization failed: {}", serverName, error.getMessage());
    }

    @Override
    public void afterToolsList(McpCallContext context, List<ToolSpecification> tools) {
        log.debug("[MCP:{}] Listed {} tools", serverName, tools.size());
    }

    @Override
    public void onToolsListError(McpCallContext context, Throwable error) {
        log.warn("[MCP:{}] Failed to list tools: {}", serverName, error.getMessage());
    }

    @Override
    public void afterExecuteTool(McpCallContext context, ToolExecutionResult result, Map<String, Object> rawResult) {
        log.debug("[MCP:{}] Tool executed, isError={}", serverName, result.isError());
    }

    @Override
    public void onExecuteToolError(McpCallContext context, Throwable error) {
        log.warn("[MCP:{}] Tool execution failed: {}", serverName, error.getMessage());
    }

    @Override
    public void onNotificationToolsListChanged() {
        log.info("[MCP:{}] Tool list changed notification", serverName);
        if (onToolsChanged != null) {
            onToolsChanged.accept(serverName);
        }
    }

    @Override
    public void onNotificationResourcesListChanged() {
        log.debug("[MCP:{}] Resource list changed", serverName);
    }

    @Override
    public void onPingError(McpCallContext context, Throwable error) {
        log.warn("[MCP:{}] Health check failed: {}", serverName, error.getMessage());
    }
}
