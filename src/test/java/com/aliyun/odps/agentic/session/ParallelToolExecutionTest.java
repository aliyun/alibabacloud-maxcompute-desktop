package com.aliyun.odps.agentic.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolRegistry;
import com.aliyun.odps.agentic.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 只读工具并行执行（0.4.0 / P1-F）的回归测试。
 */
@Timeout(20)
class ParallelToolExecutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一个耗时 300ms 的只读工具——用于观测并行带来的墙钟缩短。 */
    private static ToolDef slowReadOnlyTool(String id) {
        return new ToolDef() {
            @Override public String getId() { return id; }
            @Override public String getDescription() { return "slow read"; }
            @Override public boolean isReadOnly() { return true; }
            @Override public ObjectNode getParametersSchema() { return MAPPER.createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext context) {
                try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolResult.of("out", "result-" + id);
            }
        };
    }

    private Message assistantWithCalls(String... callIdsAndNames) {
        // callIdsAndNames 形如 [callId, toolName] 交替
        List<MessagePart> parts = new ArrayList<>();
        for (int i = 0; i < callIdsAndNames.length; i += 2) {
            parts.add(new MessagePart.ToolCallPart(callIdsAndNames[i], callIdsAndNames[i + 1], "{}"));
        }
        return new Message("m1", "s1", Role.ASSISTANT, parts);
    }

    @Test
    void readOnlyBatchRunsInParallelAndPreservesOrder() {
        ToolRegistry registry = new ToolRegistry();
        for (String id : List.of("r1", "r2", "r3", "r4")) {
            registry.register(slowReadOnlyTool(id));
        }
        StreamProcessor sp = new StreamProcessor(registry);

        Message msg = assistantWithCalls("c1", "r1", "c2", "r2", "c3", "r3", "c4", "r4");

        long start = System.nanoTime();
        List<MessagePart.ToolResultPart> results = sp.executeToolCalls(msg, (name, callId) -> null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 4 个 300ms 只读工具：串行约 1200ms，并行应远小于此（<900ms 留足调度余量）。
        assertTrue(elapsedMs < 900, "read-only batch should run in parallel, took " + elapsedMs + "ms");

        // 结果顺序与声明顺序一致（executeOne 会把 title 前置为 "title\noutput"）
        assertEquals(4, results.size());
        assertEquals(List.of("c1", "c2", "c3", "c4"),
            results.stream().map(MessagePart.ToolResultPart::callID).toList());
        assertTrue(results.get(0).output().endsWith("result-r1"));
        assertTrue(results.get(3).output().endsWith("result-r4"));
    }

    @Test
    void mutatingToolsStaySerial() {
        // 写工具（isReadOnly=false，默认）即便多个相邻也保持串行。
        ToolRegistry registry = new ToolRegistry();
        List<String> executionOrder = java.util.Collections.synchronizedList(new ArrayList<>());
        ToolDef writer = new ToolDef() {
            @Override public String getId() { return "w"; }
            @Override public String getDescription() { return "write"; }
            // isReadOnly 默认 false
            @Override public ObjectNode getParametersSchema() { return MAPPER.createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext context) {
                executionOrder.add(Thread.currentThread().getName());
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolResult.of("out", "wrote");
            }
        };
        registry.register(writer);
        StreamProcessor sp = new StreamProcessor(registry);

        Message msg = assistantWithCalls("c1", "w", "c2", "w");
        long start = System.nanoTime();
        List<MessagePart.ToolResultPart> results = sp.executeToolCalls(msg, (name, callId) -> null);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        // 串行：两个 200ms 写工具 ≥ 400ms
        assertTrue(elapsedMs >= 380, "mutating tools must run serially, took " + elapsedMs + "ms");
        assertEquals(2, results.size());
        // 串行 → 同一线程执行
        assertEquals(1, new java.util.HashSet<>(executionOrder).size(), "serial execution on one thread");
    }

    @Test
    void unknownToolTreatedAsMutatingAndSerial() {
        // 未注册的工具保守视为有副作用，串行返回 "Unknown tool" 错误而不抛异常。
        ToolRegistry registry = new ToolRegistry();
        StreamProcessor sp = new StreamProcessor(registry);
        Message msg = assistantWithCalls("c1", "nonexistent");
        List<MessagePart.ToolResultPart> results = sp.executeToolCalls(msg, (name, callId) -> null);
        assertEquals(1, results.size());
        assertTrue(results.get(0).isError());
        assertTrue(results.get(0).output().contains("Unknown tool"));
    }
}
