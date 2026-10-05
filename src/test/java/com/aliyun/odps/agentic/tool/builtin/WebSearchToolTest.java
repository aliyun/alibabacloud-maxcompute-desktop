package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSearchToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final WebSearchTool tool = new WebSearchTool();
    private static final ToolContext CTX = ToolContext.of("s1", "m1", "agent", "c1",
        java.util.List.of(), Map.of(), m -> {}, (perm, target, desc) -> true);

    @Test
    void searchWithQueryReturnsResult() throws Exception {
        var args = mapper.readTree("{\"query\":\"Java SDK\"}");
        ToolResult result = tool.execute(args, CTX);

        assertFalse(result.isError());
        assertTrue(result.output().contains("Java SDK"));
    }

    @Test
    void emptyQueryReturnsError() throws Exception {
        var args = mapper.readTree("{\"query\":\"\"}");
        ToolResult result = tool.execute(args, CTX);

        assertTrue(result.isError());
    }

    @Test
    void missingQueryReturnsError() throws Exception {
        var args = mapper.readTree("{}");
        ToolResult result = tool.execute(args, CTX);

        assertTrue(result.isError());
    }

    @Test
    void toolIdIsWebsearch() {
        assertEquals("websearch", tool.getId());
    }

    @Test
    void schemaHasQueryProperty() {
        var schema = tool.getParametersSchema();
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("query"));
    }
}
