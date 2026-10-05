package com.aliyun.odps.agentic.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for McpServerConfig and McpToolDef records.
 */
class McpRecordTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // -- McpServerConfig: stdio constructors --

    @Test
    void stdioConstructorThreeArgs() {
        McpServerConfig config = new McpServerConfig("myserver", "node", List.of("server.js"));
        assertEquals("myserver", config.name());
        assertEquals("node", config.command());
        assertEquals(List.of("server.js"), config.args());
        assertEquals(Map.of(), config.env());
        assertNull(config.url());
        assertNull(config.headers());
        assertNull(config.timeout());
        assertNull(config.oauth());
    }

    @Test
    void stdioConstructorWithEnv() {
        Map<String, String> env = Map.of("DEBUG", "true");
        McpServerConfig config = new McpServerConfig("myserver", "python", List.of("-m", "mcp"), env);
        assertEquals(env, config.env());
        assertNull(config.url());
    }

    // -- McpServerConfig: remote factories --

    @Test
    void remoteFactory() {
        McpServerConfig config = McpServerConfig.remote("remote-srv", "https://mcp.example.com/sse");
        assertEquals("remote-srv", config.name());
        assertNull(config.command());
        assertNull(config.args());
        assertEquals("https://mcp.example.com/sse", config.url());
        assertNull(config.headers());
    }

    @Test
    void remoteFactoryWithHeaders() {
        Map<String, String> headers = Map.of("Authorization", "Bearer tok123");
        McpServerConfig config = McpServerConfig.remote("remote-srv", "https://mcp.example.com", headers);
        assertEquals(headers, config.headers());
    }

    // -- McpServerConfig: isRemote --

    @Test
    void isRemoteTrueForUrl() {
        McpServerConfig config = McpServerConfig.remote("srv", "https://example.com");
        assertTrue(config.isRemote());
    }

    @Test
    void isRemoteFalseForStdio() {
        McpServerConfig config = new McpServerConfig("srv", "node", List.of("s.js"));
        assertFalse(config.isRemote());
    }

    @Test
    void isRemoteFalseForBlankUrl() {
        McpServerConfig config = new McpServerConfig("srv", null, null, Map.of(), "  ", null, null, null);
        assertFalse(config.isRemote());
    }

    // -- McpServerConfig: getTimeoutMs --

    @Test
    void getTimeoutMsDefault() {
        McpServerConfig config = new McpServerConfig("srv", "node", List.of());
        assertEquals(30_000, config.getTimeoutMs());
    }

    @Test
    void getTimeoutMsCustom() {
        McpServerConfig config = new McpServerConfig("srv", "node", null, Map.of(), null, null, 5000, null);
        assertEquals(5000, config.getTimeoutMs());
    }

    // -- McpToolDef: getId --

    @Test
    void mcpToolDefGetId() {
        McpToolDef tool = new McpToolDef("myserver", "list_files", "Lists files", null, null);
        assertEquals("mcp__myserver__list_files", tool.getId());
    }

    // -- McpToolDef: getDescription --

    @Test
    void mcpToolDefGetDescription() {
        McpToolDef tool = new McpToolDef("srv", "tool1", "Does something useful", null, null);
        assertEquals("Does something useful", tool.getDescription());
    }

    // -- McpToolDef: getParametersSchema --

    @Test
    void mcpToolDefParametersSchemaNull() {
        McpToolDef tool = new McpToolDef("srv", "tool1", "desc", null, null);
        assertNull(tool.getParametersSchema());
    }

    @Test
    void mcpToolDefParametersSchemaValidObject() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");
        McpToolDef tool = new McpToolDef("srv", "tool1", "desc", schema, null);
        assertNotNull(tool.getParametersSchema());
        assertEquals("object", tool.getParametersSchema().get("type").asText());
    }

    @Test
    void mcpToolDefParametersSchemaNotObject() {
        McpToolDef tool = new McpToolDef("srv", "tool1", "desc", MAPPER.createArrayNode(), null);
        assertNull(tool.getParametersSchema());
    }

    // -- McpToolDef: accessors --

    @Test
    void mcpToolDefServerName() {
        McpToolDef tool = new McpToolDef("my-server", "my-tool", "desc", null, null);
        assertEquals("my-server", tool.serverName());
    }

    @Test
    void mcpToolDefToolName() {
        McpToolDef tool = new McpToolDef("my-server", "my-tool", "desc", null, null);
        assertEquals("my-tool", tool.toolName());
    }

    // -- McpToolDef: equals / hashCode --

    @Test
    void mcpToolDefEquality() {
        McpToolDef t1 = new McpToolDef("srv", "tool1", "desc1", null, null);
        McpToolDef t2 = new McpToolDef("srv", "tool1", "desc2", null, null);
        assertEquals(t1, t2, "Equality is based on serverName + toolName only");
        assertEquals(t1.hashCode(), t2.hashCode());
    }

    @Test
    void mcpToolDefInequality() {
        McpToolDef t1 = new McpToolDef("srv", "tool1", "desc", null, null);
        McpToolDef t2 = new McpToolDef("srv", "tool2", "desc", null, null);
        assertNotEquals(t1, t2);
    }

    // -- McpToolDef: toString --

    @Test
    void mcpToolDefToString() {
        McpToolDef tool = new McpToolDef("srv", "tool1", "desc", null, null);
        String str = tool.toString();
        assertTrue(str.contains("mcp__srv__tool1"));
        assertTrue(str.contains("srv"));
    }
}
