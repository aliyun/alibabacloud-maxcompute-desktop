package com.aliyun.odps.agentic.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SseMcpClientTest {

    private final SseMcpClient client = new SseMcpClient();

    // ── McpServerConfig.remote() ──

    @Test
    void remoteConfig_isRemote() {
        McpServerConfig config = McpServerConfig.remote("test-server", "http://localhost:8080");
        assertTrue(config.isRemote());
        assertEquals("http://localhost:8080", config.url());
        assertNull(config.command());
    }

    @Test
    void remoteConfig_withHeaders() {
        McpServerConfig config = McpServerConfig.remote("test-server", "http://localhost:8080",
            Map.of("Authorization", "Bearer token123"));
        assertTrue(config.isRemote());
        assertEquals("Bearer token123", config.headers().get("Authorization"));
    }

    @Test
    void stdioConfig_isNotRemote() {
        McpServerConfig config = new McpServerConfig("local", "npx", List.of("-y", "mcp-server"));
        assertFalse(config.isRemote());
        assertNull(config.url());
        assertEquals("npx", config.command());
    }

    @Test
    void config_timeoutDefault() {
        McpServerConfig config = McpServerConfig.remote("test", "http://localhost:8080");
        assertEquals(30_000, config.getTimeoutMs());
    }

    @Test
    void config_timeoutCustom() {
        McpServerConfig config = new McpServerConfig("test", null, null, Map.of(),
            "http://localhost:8080", null, 10_000, null);
        assertEquals(10_000, config.getTimeoutMs());
    }

    // ── SseMcpClient not connected ──

    @Test
    void listServers_emptyWhenNotConnected() {
        assertTrue(client.listServers().isEmpty());
    }

    @Test
    void isConnected_falseWhenNotConnected() {
        assertFalse(client.isConnected("any-server"));
    }

    @Test
    void callTool_returnsErrorWhenNotConnected() {
        var result = client.callTool("nonexistent", "someTool", Map.of());
        assertTrue(result.isError());
        assertTrue(result.output().contains("not connected"));
    }

    @Test
    void disconnect_noErrorWhenNotConnected() {
        assertDoesNotThrow(() -> client.disconnect("nonexistent"));
    }

    // ── SseMcpClient connect with invalid URL ──

    @Test
    void connect_invalidUrl_throwsMcpException() {
        McpServerConfig config = McpServerConfig.remote("bad", "http://localhost:1/nonexistent");
        assertThrows(McpException.class, () -> client.connect(config));
    }

    @Test
    void connect_blankUrl_throwsMcpException() {
        McpServerConfig config = McpServerConfig.remote("bad", "");
        assertThrows(McpException.class, () -> client.connect(config));
    }
}
