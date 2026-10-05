package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the LLM layer — SseLlmClient, AnthropicTransform, MessageConverter.
 */
class LlmLayerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── AnthropicTransform tests ──

    @Test
    void anthropicTransform_textDelta() {
        var transform = new AnthropicTransform();
        String data = """
            {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}
            """;

        List<LLMEvent> events = assertDoesNotThrow(() ->
            transform.transformSseEvent("content_block_delta", MAPPER.readTree(data)));

        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.TextDelta.class, events.getFirst());
        assertEquals("Hello", ((LLMEvent.TextDelta) events.getFirst()).delta());
    }

    @Test
    void anthropicTransform_toolCall() {
        var transform = new AnthropicTransform();

        // content_block_start emits ToolInputStart (not ToolCall)
        String startData = """
            {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_123","name":"Read"}}
            """;
        List<LLMEvent> startEvents = assertDoesNotThrow(() ->
            transform.transformSseEvent("content_block_start", MAPPER.readTree(startData)));
        assertEquals(1, startEvents.size());
        assertInstanceOf(LLMEvent.ToolInputStart.class, startEvents.getFirst());
        assertEquals("toolu_123", ((LLMEvent.ToolInputStart) startEvents.getFirst()).callId());
        assertEquals("Read", ((LLMEvent.ToolInputStart) startEvents.getFirst()).tool());

        // content_block_stop emits ToolCall with accumulated input
        String stopData = """
            {"type":"content_block_stop","index":1}
            """;
        List<LLMEvent> stopEvents = assertDoesNotThrow(() ->
            transform.transformSseEvent("content_block_stop", MAPPER.readTree(stopData)));
        assertTrue(stopEvents.stream().anyMatch(e -> e instanceof LLMEvent.ToolCall));
        LLMEvent.ToolCall tc = stopEvents.stream()
            .filter(e -> e instanceof LLMEvent.ToolCall)
            .map(e -> (LLMEvent.ToolCall) e)
            .findFirst().orElse(null);
        assertNotNull(tc);
        assertEquals("toolu_123", tc.callId());
        assertEquals("Read", tc.tool());
    }

    @Test
    void anthropicTransform_inputJsonDelta() {
        var transform = new AnthropicTransform();
        String data = """
            {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"path\\""}}
            """;

        List<LLMEvent> events = assertDoesNotThrow(() ->
            transform.transformSseEvent("content_block_delta", MAPPER.readTree(data)));

        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.ToolInputDelta.class, events.getFirst());
        assertEquals("{\"path\"", ((LLMEvent.ToolInputDelta) events.getFirst()).delta());
    }

    @Test
    void anthropicTransform_messageFinish() {
        var transform = new AnthropicTransform();
        String data = """
            {"type":"message_delta","delta":{"stop_reason":"end-turn"},"usage":{"output_tokens":42}}
            """;

        List<LLMEvent> events = assertDoesNotThrow(() ->
            transform.transformSseEvent("message_delta", MAPPER.readTree(data)));

        assertEquals(2, events.size());
        assertInstanceOf(LLMEvent.Finish.class, events.get(0));
        assertEquals("end-turn", ((LLMEvent.Finish) events.get(0)).reason());
        assertInstanceOf(LLMEvent.Usage.class, events.get(1));
        assertEquals(42, ((LLMEvent.Usage) events.get(1)).usage().outputTokens());
    }

    @Test
    void anthropicTransform_thinkingDelta() {
        var transform = new AnthropicTransform();
        String data = """
            {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me think..."}}
            """;

        List<LLMEvent> events = assertDoesNotThrow(() ->
            transform.transformSseEvent("content_block_delta", MAPPER.readTree(data)));

        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.ReasoningDelta.class, events.getFirst());
        assertEquals("Let me think...", ((LLMEvent.ReasoningDelta) events.getFirst()).delta());
    }

    @Test
    void anthropicTransform_error() {
        var transform = new AnthropicTransform();
        String data = """
            {"type":"error","error":{"type":"overloaded_error","message":"API is overloaded"}}
            """;

        List<LLMEvent> events = assertDoesNotThrow(() ->
            transform.transformSseEvent("error", MAPPER.readTree(data)));

        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.ProviderError.class, events.getFirst());
        assertTrue(((LLMEvent.ProviderError) events.getFirst()).error().contains("overloaded"));
    }

    // ── MessageConverter tests ──

    @Test
    void messageConverter_simpleUserMessage() {
        Message msg = new Message("1", "s1", Role.USER,
            null, null, List.of(new MessagePart.TextPart("Hello")), null, null, null, null, null, null, null, null);

        List<Map<String, Object>> result = MessageConverter.toAnthropicMessages(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("user", result.getFirst().get("role"));
        assertEquals("Hello", result.getFirst().get("content"));
    }

    @Test
    void messageConverter_assistantWithToolCall() {
        Message msg = new Message("2", "s1", Role.ASSISTANT,
            null, null,
            List.of(
                new MessagePart.TextPart("Let me read that file."),
                new MessagePart.ToolCallPart("toolu_abc", "Read", "{\"path\":\"/tmp/test.txt\"}")
            ),
            "agent", null, "end-turn", null, null, null, null, null);

        List<Map<String, Object>> result = MessageConverter.toAnthropicMessages(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("assistant", result.getFirst().get("role"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.getFirst().get("content");
        assertEquals(2, content.size());

        // Text block
        assertEquals("text", content.get(0).get("type"));
        assertEquals("Let me read that file.", content.get(0).get("text"));

        // Tool use block
        assertEquals("tool_use", content.get(1).get("type"));
        assertEquals("toolu_abc", content.get(1).get("id"));
        assertEquals("Read", content.get(1).get("name"));
    }

    @Test
    void messageConverter_toolResult() {
        Message msg = new Message("3", "s1", Role.USER,
            null, null,
            List.of(new MessagePart.ToolResultPart("toolu_abc", "Read", "File contents here", false)),
            null, null, null, null, null, null, null, null);

        List<Map<String, Object>> result = MessageConverter.toAnthropicMessages(List.of(msg));

        assertEquals(1, result.size());
        assertEquals("user", result.getFirst().get("role"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.getFirst().get("content");
        assertEquals(1, content.size());
        assertEquals("tool_result", content.getFirst().get("type"));
        assertEquals("toolu_abc", content.getFirst().get("tool_use_id"));
        assertEquals("File contents here", content.getFirst().get("content"));
    }

    @Test
    void messageConverter_skipsSystemMessages() {
        Message sysMsg = new Message("0", "s1", Role.SYSTEM,
            null, null, List.of(new MessagePart.TextPart("System prompt")), null, null, null, null, null, null, null, null);
        Message userMsg = new Message("1", "s1", Role.USER,
            null, null, List.of(new MessagePart.TextPart("Hello")), null, null, null, null, null, null, null, null);

        List<Map<String, Object>> result = MessageConverter.toAnthropicMessages(List.of(sysMsg, userMsg));

        assertEquals(1, result.size()); // system message skipped
        assertEquals("user", result.getFirst().get("role"));
    }

    // ── SseLlmClient tests ──

    @Test
    void sseLlmClient_supportsAnthropic() {
        var client = new SseLlmClient();
        assertTrue(client.supports("anthropic"));
        assertFalse(client.supports("openai"));
    }

    @Test
    void sseLlmClient_supportsRegisteredProvider() {
        var client = new SseLlmClient();
        client.registerTransform("openai", new AnthropicTransform()); // reuse for test
        assertTrue(client.supports("openai"));
    }

    // ── LlmRequest test ──

    @Test
    void llmRequest_of() {
        var model = Model.of("anthropic", "claude-sonnet-4-20250514",
            new ModelLimit(200000, null, 16384));
        var request = LlmRequest.of(model, List.of("Be helpful"), List.of());

        assertEquals("anthropic", request.model().providerId());
        assertEquals(1, request.system().size());
        assertEquals("auto", request.toolChoice());
    }

    // ── OpenAITransform tests ──

    @Test
    void openAITransform_textDelta() {
        var transform = new OpenAITransform();
        String data = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hello"},"finish_reason":null}]}
            """;

        List<LLMEvent> events = assertDoesNotThrow(() ->
            transform.transformSseEvent("", MAPPER.readTree(data)));

        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.TextDelta.class, events.getFirst());
        assertEquals("Hello", ((LLMEvent.TextDelta) events.getFirst()).delta());
    }

    @Test
    void openAITransform_toolCall() {
        var transform = new OpenAITransform();

        // First chunk: tool_calls delta emits ToolInputStart
        String deltaData = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_abc","type":"function","function":{"name":"Read","arguments":"{}"}}]},"finish_reason":null}]}
            """;
        List<LLMEvent> deltaEvents = assertDoesNotThrow(() ->
            transform.transformSseEvent("", MAPPER.readTree(deltaData)));
        assertTrue(deltaEvents.stream().anyMatch(e -> e instanceof LLMEvent.ToolInputStart));

        // Second chunk: finish_reason "tool_calls" emits ToolCall
        String finishData = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
            """;
        List<LLMEvent> finishEvents = assertDoesNotThrow(() ->
            transform.transformSseEvent("", MAPPER.readTree(finishData)));
        assertTrue(finishEvents.stream().anyMatch(e -> e instanceof LLMEvent.ToolCall));
        LLMEvent.ToolCall tc = finishEvents.stream()
            .filter(e -> e instanceof LLMEvent.ToolCall)
            .map(e -> (LLMEvent.ToolCall) e)
            .findFirst().orElse(null);
        assertNotNull(tc);
        assertEquals("call_abc", tc.callId());
        assertEquals("Read", tc.tool());
    }

    @Test
    void openAITransform_finishStop() {
        var transform = new OpenAITransform();
        String data = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
            """;

        List<LLMEvent> events = assertDoesNotThrow(() ->
            transform.transformSseEvent("", MAPPER.readTree(data)));

        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.Finish.class, events.getFirst());
        assertEquals("end-turn", ((LLMEvent.Finish) events.getFirst()).reason());
    }

    @Test
    void openAITransform_finishToolCalls() {
        var transform = new OpenAITransform();
        String data = """
            {"id":"chatcmpl-1","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}
            """;

        List<LLMEvent> events = assertDoesNotThrow(() ->
            transform.transformSseEvent("", MAPPER.readTree(data)));

        assertEquals(1, events.size());
        assertEquals("tool-use", ((LLMEvent.Finish) events.getFirst()).reason());
    }

    // ── Usage test ──

    @Test
    void usage_totalTokens() {
        var usage = new Usage(100, 50, 20, 10);
        assertEquals(150, usage.totalTokens());
        assertEquals(100, usage.inputTokens());
        assertEquals(50, usage.outputTokens());
    }
}
