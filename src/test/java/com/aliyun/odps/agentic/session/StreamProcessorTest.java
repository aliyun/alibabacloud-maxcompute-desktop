package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.llm.Usage;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.tool.ToolRegistry;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StreamProcessor — event processing and message building.
 */
class StreamProcessorTest {

    private StreamProcessor processor;
    private List<AgentEvent> capturedEvents;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry();
        processor = new StreamProcessor(registry);
        capturedEvents = new ArrayList<>();
    }

    @Test
    void processEvents_textOnly() {
        List<LLMEvent> events = List.of(
            new LLMEvent.TextStart("claude-sonnet-4-20250514"),
            new LLMEvent.TextDelta("Hello, "),
            new LLMEvent.TextDelta("world!"),
            new LLMEvent.TextEnd(),
            new LLMEvent.Finish("end-turn")
        );

        Message msg = processor.processEvents(events, "session-1", "test-agent", capturedEvents::add);

        assertEquals(Role.ASSISTANT, msg.role());
        assertEquals("Hello, world!", msg.getTextContent());
        assertEquals("end-turn", msg.finish());
        assertEquals("session-1", msg.sessionId());
    }

    @Test
    void processEvents_withReasoning() {
        List<LLMEvent> events = List.of(
            new LLMEvent.ReasoningStart("thinking-1"),
            new LLMEvent.ReasoningDelta("Let me analyze..."),
            new LLMEvent.ReasoningEnd("thinking-1", "sig123"),
            new LLMEvent.TextDelta("Here is my answer."),
            new LLMEvent.Finish("end-turn")
        );

        Message msg = processor.processEvents(events, "session-1", "test-agent", capturedEvents::add);

        assertEquals(Role.ASSISTANT, msg.role());
        assertTrue(msg.parts().stream().anyMatch(p -> p instanceof MessagePart.ReasoningPart rp
            && "sig123".equals(rp.signature())));
        assertTrue(msg.parts().stream().anyMatch(p -> p instanceof MessagePart.TextPart));
        assertEquals("Here is my answer.", msg.getTextContent());
    }

    @Test
    void processEvents_toolCall() {
        List<LLMEvent> events = List.of(
            new LLMEvent.TextDelta("Let me read that file."),
            new LLMEvent.ToolCall("toolu_abc", "Read"),
            new LLMEvent.ToolInputDelta("{\"path\":\"/tmp/test.txt\"}"),
            new LLMEvent.Finish("tool-use")
        );

        Message msg = processor.processEvents(events, "session-1", "test-agent", capturedEvents::add);

        assertEquals(Role.ASSISTANT, msg.role());
        assertEquals("tool-use", msg.finish());

        List<MessagePart.ToolCallPart> toolCalls = msg.parts().stream()
            .filter(p -> p instanceof MessagePart.ToolCallPart)
            .map(p -> (MessagePart.ToolCallPart) p)
            .toList();

        assertEquals(1, toolCalls.size());
        assertEquals("toolu_abc", toolCalls.getFirst().callID());
        assertEquals("Read", toolCalls.getFirst().name());
    }

    @Test
    void processEvents_parallelToolCallsKeepTheirOwnIdentityAndInput() {
        List<LLMEvent> events = List.of(
            new LLMEvent.ToolInputStart("call-skill", "skill"),
            new LLMEvent.ToolInputDelta("{\"name\":\"maxframe\"}"),
            new LLMEvent.ToolInputStart("call-read", "read"),
            new LLMEvent.ToolInputDelta("{\"filePath\":\"/tmp/main.py\"}"),
            new LLMEvent.ToolCall("call-skill", "skill", "{\"name\":\"maxframe\"}"),
            new LLMEvent.ToolCall("call-read", "read", "{\"filePath\":\"/tmp/main.py\"}"),
            new LLMEvent.Finish("tool-use")
        );

        Message msg = processor.processEvents(events, "session-1", "test-agent", capturedEvents::add);

        List<MessagePart.ToolCallPart> toolCalls = msg.parts().stream()
            .filter(p -> p instanceof MessagePart.ToolCallPart)
            .map(p -> (MessagePart.ToolCallPart) p)
            .toList();

        assertEquals(2, toolCalls.size());
        assertEquals(new MessagePart.ToolCallPart(
            "call-skill", "skill", "{\"name\":\"maxframe\"}"), toolCalls.get(0));
        assertEquals(new MessagePart.ToolCallPart(
            "call-read", "read", "{\"filePath\":\"/tmp/main.py\"}"), toolCalls.get(1));

        List<AgentEvent.ToolCallStarted> started = capturedEvents.stream()
            .filter(e -> e instanceof AgentEvent.ToolCallStarted)
            .map(e -> (AgentEvent.ToolCallStarted) e)
            .toList();
        assertEquals(List.of("call-skill", "call-read"),
            started.stream().map(AgentEvent.ToolCallStarted::callId).toList());
        assertEquals(List.of("skill", "read"),
            started.stream().map(AgentEvent.ToolCallStarted::tool).toList());
    }

    @Test
    void processEvents_withUsage() {
        List<LLMEvent> events = List.of(
            new LLMEvent.TextDelta("Hi"),
            new LLMEvent.Finish("end-turn"),
            new LLMEvent.Usage(new Usage(100, 50, 10, 5))
        );

        Message msg = processor.processEvents(events, "session-1", "test-agent", capturedEvents::add);

        assertNotNull(msg.tokens());
        assertEquals(100, msg.tokens().input());
        assertEquals(50, msg.tokens().output());
    }

    @Test
    void processEvents_emptyStream() {
        List<LLMEvent> events = List.of(
            new LLMEvent.Finish("end-turn")
        );

        Message msg = processor.processEvents(events, "session-1", "test-agent", capturedEvents::add);

        assertEquals(Role.ASSISTANT, msg.role());
        assertEquals("", msg.getTextContent());
    }

    @Test
    void processEvents_providerError() {
        List<LLMEvent> events = List.of(
            new LLMEvent.ProviderError("API overloaded")
        );

        Message msg = processor.processEvents(events, "session-1", "test-agent", capturedEvents::add);

        // Error should still produce a message
        assertEquals(Role.ASSISTANT, msg.role());

        // Should have emitted an error agent event
        assertTrue(capturedEvents.stream().anyMatch(e -> e instanceof AgentEvent.Error));
    }

    @Test
    void processEvents_agentEventsEmitted() {
        List<LLMEvent> events = List.of(
            new LLMEvent.TextDelta("Hello"),
            new LLMEvent.ToolCall("toolu_1", "Read"),
            new LLMEvent.Finish("tool-use")
        );

        processor.processEvents(events, "session-1", "test-agent", capturedEvents::add);

        // Should have text delta and tool call started events
        assertTrue(capturedEvents.stream().anyMatch(e -> e instanceof AgentEvent.TextDelta));
        assertTrue(capturedEvents.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallStarted));
    }

    @Test
    void executeToolCalls_prefersRunScopedToolWithSameId() {
        Message assistant = new Message("m1", "s1", Role.ASSISTANT, null, null,
            List.of(new MessagePart.ToolCallPart("c1", "same", "{}")),
            "agent", null, "tool-use", null, null, null, null, null);

        var first = processor.executeToolCalls(assistant, (tool, callId) -> null,
            null, null, Map.of("same", constantTool("same", "first-context")));
        var second = processor.executeToolCalls(assistant, (tool, callId) -> null,
            null, null, Map.of("same", constantTool("same", "second-context")));

        assertEquals("first-context", first.getFirst().output());
        assertEquals("second-context", second.getFirst().output());
    }

    @Test
    void executeToolCalls_keepsStructuredResultMetadata() {
        Message assistant = new Message("m1", "s1", Role.ASSISTANT, null, null,
            List.of(new MessagePart.ToolCallPart("c1", "query", "{}")),
            "agent", null, "tool-use", null, null, null, null, null);
        ToolDef tool = new ToolDef() {
            @Override public String getId() { return "query"; }
            @Override public String getDescription() { return "test"; }
            @Override public ObjectNode getParametersSchema() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }
            @Override public ToolResult execute(JsonNode args, ToolContext context) {
                return new ToolResult("rows", Map.of("rowCount", 2, "artifactId", "a1"),
                    "2 rows", List.of());
            }
        };

        var results = processor.executeToolCalls(assistant, (name, callId) -> null,
            null, null, Map.of("query", tool));

        assertEquals(2, results.getFirst().metadata().get("rowCount"));
        assertEquals("a1", results.getFirst().metadata().get("artifactId"));
    }

    private static ToolDef constantTool(String id, String output) {
        return new ToolDef() {
            @Override public String getId() { return id; }
            @Override public String getDescription() { return "test"; }
            @Override public ObjectNode getParametersSchema() {
                return JsonNodeFactory.instance.objectNode().put("type", "object");
            }
            @Override public ToolResult execute(JsonNode args, ToolContext context) {
                return ToolResult.success(output);
            }
        };
    }

    @Test
    void doomLoop_detection() {
        // Create messages with repeated tool calls
        Message msg1 = new Message("1", "s1", Role.ASSISTANT, null, null,
            List.of(new MessagePart.ToolCallPart("tc1", "Read", "{\"path\":\"/a\"}")),
            "agent", null, "tool-use", null, null, null, null, null);
        Message msg2 = new Message("2", "s1", Role.USER, null, null,
            List.of(new MessagePart.ToolResultPart("tc1", "Read", "content", false)),
            null, null, null, null, null, null, null, null);
        Message msg3 = new Message("3", "s1", Role.ASSISTANT, null, null,
            List.of(new MessagePart.ToolCallPart("tc2", "Read", "{\"path\":\"/a\"}")),
            "agent", null, "tool-use", null, null, null, null, null);
        Message msg4 = new Message("4", "s1", Role.USER, null, null,
            List.of(new MessagePart.ToolResultPart("tc2", "Read", "content", false)),
            null, null, null, null, null, null, null, null);

        List<Message> messages = List.of(msg1, msg2, msg3, msg4);

        assertTrue(processor.isDoomLoop(messages, 2));
    }

    @Test
    void doomLoop_noLoop() {
        // Different tool calls — not a loop
        Message msg1 = new Message("1", "s1", Role.ASSISTANT, null, null,
            List.of(new MessagePart.ToolCallPart("tc1", "Read", "{\"path\":\"/a\"}")),
            "agent", null, "tool-use", null, null, null, null, null);
        Message msg2 = new Message("2", "s1", Role.USER, null, null,
            List.of(new MessagePart.ToolResultPart("tc1", "Read", "content", false)),
            null, null, null, null, null, null, null, null);
        Message msg3 = new Message("3", "s1", Role.ASSISTANT, null, null,
            List.of(new MessagePart.ToolCallPart("tc2", "Write", "{\"path\":\"/b\"}")),
            "agent", null, "tool-use", null, null, null, null, null);
        Message msg4 = new Message("4", "s1", Role.USER, null, null,
            List.of(new MessagePart.ToolResultPart("tc2", "Write", "done", false)),
            null, null, null, null, null, null, null, null);

        List<Message> messages = List.of(msg1, msg2, msg3, msg4);

        assertFalse(processor.isDoomLoop(messages, 2));
    }
}
