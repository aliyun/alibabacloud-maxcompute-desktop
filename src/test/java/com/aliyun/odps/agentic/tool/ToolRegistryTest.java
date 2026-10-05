package com.aliyun.odps.agentic.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for ToolRegistry — tool registration, lookup, conflict, listing.
 */
class ToolRegistryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private ToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
    }

    @Test
    void registerAndLookup() {
        ToolDef tool = new StubTool("shell", "Execute shell commands");
        registry.register(tool);
        assertTrue(registry.get("shell").isPresent());
        assertEquals("shell", registry.get("shell").get().getId());
    }

    @Test
    void resolveReturnsTool() {
        ToolDef tool = new StubTool("shell", "Execute shell commands");
        registry.register(tool);
        assertNotNull(registry.resolve("shell"));
        assertEquals("shell", registry.resolve("shell").getId());
    }

    @Test
    void resolveNonexistentReturnsNull() {
        assertNull(registry.resolve("nonexistent"));
    }

    @Test
    void lookupNonexistent() {
        assertFalse(registry.get("nonexistent").isPresent());
    }

    @Test
    void registerCustom() {
        ToolDef tool = new StubTool("my-tool", "Custom tool");
        registry.registerCustom(tool);
        assertTrue(registry.get("my-tool").isPresent());
    }

    @Test
    void unregisterCustom() {
        ToolDef tool = new StubTool("temp", "Temporary");
        registry.registerCustom(tool);
        assertTrue(registry.get("temp").isPresent());
        assertTrue(registry.unregister("temp"));
        assertFalse(registry.get("temp").isPresent());
    }

    @Test
    void unregisterBuiltinNotAllowed() {
        ToolDef tool = new StubTool("builtin", "Built-in");
        registry.register(tool);
        assertFalse(registry.unregister("builtin"));
        assertTrue(registry.get("builtin").isPresent());
    }

    @Test
    void getAllTools() {
        registry.register(new StubTool("a", "Tool A"));
        registry.register(new StubTool("b", "Tool B"));
        registry.registerCustom(new StubTool("c", "Tool C"));
        Collection<ToolDef> all = registry.getAll();
        assertTrue(all.size() >= 3);
    }

    @Test
    void idsReturnsAllIds() {
        registry.register(new StubTool("a", "Tool A"));
        registry.registerCustom(new StubTool("b", "Tool B"));
        List<String> ids = registry.ids();
        assertTrue(ids.contains("a"));
        assertTrue(ids.contains("b"));
    }

    @Test
    void overrideBuiltinWithCustom() {
        ToolDef builtin = new StubTool("shell", "Built-in shell");
        ToolDef custom = new StubTool("shell", "Custom shell");
        registry.register(builtin);
        registry.registerCustom(custom);
        // Custom tools take priority via get() checking builtin first then custom
        // but they both exist; resolve checks builtin first
        ToolDef resolved = registry.resolve("shell");
        assertNotNull(resolved);
        assertEquals("Built-in shell", resolved.getDescription());
    }

    @Test
    void emptyRegistryReturnsEmpty() {
        assertTrue(registry.getAll().isEmpty());
        assertTrue(registry.ids().isEmpty());
        assertTrue(registry.all().isEmpty());
    }

    @Test
    void validateArgsValidInput() {
        ToolDef tool = new StubTool("test", "Test");
        JsonNode args = MAPPER.createObjectNode().put("name", "hello");
        List<String> errors = registry.validateArgs(tool, args);
        // StubTool has minimal schema, no required fields
        assertTrue(errors.isEmpty());
    }

    @Test
    void toToolsSchemaIncludesRegisteredTools() {
        registry.register(new StubTool("shell", "Shell"));
        Map<String, Object> schema = registry.toToolsSchema();
        assertTrue(schema.containsKey("shell"));
    }

    // ── Stub ToolDef ──

    private static class StubTool implements ToolDef {
        private final String id;
        private final String description;

        StubTool(String id, String description) {
            this.id = id;
            this.description = description;
        }

        @Override public String getId() { return id; }
        @Override public String getDescription() { return description; }
        @Override public ObjectNode getParametersSchema() {
            return MAPPER.createObjectNode().put("type", "object");
        }
        @Override public ToolResult execute(JsonNode args, ToolContext context) {
            return ToolResult.of("stub result");
        }
    }
}
