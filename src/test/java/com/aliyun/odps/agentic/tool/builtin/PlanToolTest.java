package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlanToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final PlanTool tool = new PlanTool();
    private static final ToolContext CTX = ToolContext.of("s1", "m1", "agent", "c1",
        java.util.List.of(), Map.of(), m -> {}, (perm, target, desc) -> true);

    @Test
    void planEnterReturnsReadOnlyMessage() throws Exception {
        var args = mapper.readTree("{\"action\":\"plan_enter\"}");
        ToolResult result = tool.execute(args, CTX);

        assertFalse(result.isError());
        assertTrue(result.output().contains("READ-ONLY"));
        assertTrue(result.output().contains("FORBIDDEN"));
    }

    @Test
    void planExitReturnsBuildMessage() throws Exception {
        var args = mapper.readTree("{\"action\":\"plan_exit\"}");
        ToolResult result = tool.execute(args, CTX);

        assertFalse(result.isError());
        assertTrue(result.output().contains("BUILD mode"));
    }

    @Test
    void invalidActionReturnsError() throws Exception {
        var args = mapper.readTree("{\"action\":\"invalid\"}");
        ToolResult result = tool.execute(args, CTX);

        assertTrue(result.isError());
    }

    @Test
    void emptyActionReturnsError() throws Exception {
        var args = mapper.readTree("{\"action\":\"\"}");
        ToolResult result = tool.execute(args, CTX);

        assertTrue(result.isError());
    }

    @Test
    void toolIdIsPlan() {
        assertEquals("plan", tool.getId());
    }

    @Test
    void schemaHasActionProperty() {
        var schema = tool.getParametersSchema();
        assertTrue(schema.has("properties"));
        assertTrue(schema.get("properties").has("action"));
    }
}
