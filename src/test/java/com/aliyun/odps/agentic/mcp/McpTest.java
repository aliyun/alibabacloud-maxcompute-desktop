package com.aliyun.odps.agentic.mcp;

import com.aliyun.odps.agentic.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class McpTest {

    // ── McpServerConfig tests ──

    @Test
    void mcpServerConfig_basic() {
        McpServerConfig config = new McpServerConfig("test", "npx", List.of("arg1"));
        assertEquals("test", config.name());
        assertEquals("npx", config.command());
        assertEquals(List.of("arg1"), config.args());
        assertEquals(Map.of(), config.env());
    }

    @Test
    void mcpServerConfig_withEnv() {
        McpServerConfig config = new McpServerConfig("fs", "npx",
            List.of("@mcp/fs"), Map.of("KEY", "val"));
        assertEquals(Map.of("KEY", "val"), config.env());
    }

    // ── McpToolDef tests ──

    @Test
    void mcpToolDef_naming() {
        // Stub client that returns success
        McpClient stubClient = new StubMcpClient();
        McpToolDef tool = new McpToolDef("myserver", "read_file",
            "Read a file", null, stubClient);

        assertEquals("mcp__myserver__read_file", tool.getId());
        assertEquals("Read a file", tool.getDescription());
        assertEquals("myserver", tool.serverName());
        assertEquals("read_file", tool.toolName());
    }

    @Test
    void mcpToolDef_equality() {
        McpClient stub = new StubMcpClient();
        McpToolDef t1 = new McpToolDef("s", "tool", "", null, stub);
        McpToolDef t2 = new McpToolDef("s", "tool", "different desc", null, stub);
        McpToolDef t3 = new McpToolDef("other", "tool", "", null, stub);

        assertEquals(t1, t2); // same server+tool name = equal
        assertNotEquals(t1, t3); // different server
    }

    @Test
    void mcpToolDef_executeDelegates() {
        McpClient stub = new StubMcpClient();
        McpToolDef tool = new McpToolDef("test", "echo", "Echo", null, stub);

        ToolResult result = tool.execute(
            new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("text", "hello"),
            null // context
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("echo"));
    }

    // ── McpException tests ──

    @Test
    void mcpException_message() {
        McpException ex = new McpException("test error");
        assertEquals("test error", ex.getMessage());
    }

    @Test
    void mcpException_withCause() {
        RuntimeException cause = new RuntimeException("root cause");
        McpException ex = new McpException("wrapped", cause);
        assertEquals("wrapped", ex.getMessage());
        assertEquals(cause, ex.getCause());
    }

    // ── Stub MCP Client ──

    static class StubMcpClient implements McpClient {
        @Override
        public List<McpToolDef> connect(McpServerConfig config) {
            return List.of();
        }

        @Override
        public void disconnect(String serverName) {}

        @Override
        public List<String> listServers() { return List.of(); }

        @Override
        public boolean isConnected(String serverName) { return false; }

        @Override
        public ToolResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
            return ToolResult.success(serverName + ":" + toolName);
        }
    }
}
