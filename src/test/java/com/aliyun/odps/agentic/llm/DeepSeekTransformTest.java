package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DeepSeekTransformTest {

    private DeepSeekTransform transform;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setup() {
        transform = new DeepSeekTransform();
    }

    // ── buildHttpRequest ──

    @Test
    void buildHttpRequest_usesDeepSeekBaseUrl() throws Exception {
        LlmRequest request = createTestRequest();
        var httpRequest = transform.buildHttpRequest(request, Map.of("deepseek", "sk-test"));
        String uri = httpRequest.uri().toString();
        assertEquals("https://api.deepseek.com/v1/chat/completions", uri);
    }

    @Test
    void buildHttpRequest_usesCustomBaseUrl() throws Exception {
        LlmRequest request = createTestRequest();
        var httpRequest = transform.buildHttpRequest(request,
            Map.of("deepseek", "sk-test", "deepseek_base_url", "https://custom.api.com/v1/chat/completions"));
        assertEquals("https://custom.api.com/v1/chat/completions", httpRequest.uri().toString());
    }

    @Test
    void buildHttpRequest_includesAuthHeader() throws Exception {
        LlmRequest request = createTestRequest();
        var httpRequest = transform.buildHttpRequest(request, Map.of("deepseek", "sk-test123"));
        String auth = httpRequest.headers().firstValue("Authorization").orElse("");
        assertEquals("Bearer sk-test123", auth);
    }

    @Test
    void buildHttpRequest_fallsBackToDefaultKey() throws Exception {
        LlmRequest request = createTestRequest();
        var httpRequest = transform.buildHttpRequest(request, Map.of("default", "sk-fallback"));
        String auth = httpRequest.headers().firstValue("Authorization").orElse("");
        assertEquals("Bearer sk-fallback", auth);
    }

    // ── transformSseEvent ──

    @Test
    void transformSseEvent_textDelta() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}");
        List<LLMEvent> events = transform.transformSseEvent("", data);
        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.TextDelta.class, events.get(0));
        assertEquals("Hello", ((LLMEvent.TextDelta) events.get(0)).delta());
    }

    @Test
    void transformSseEvent_reasoningDelta() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me think...\"},\"finish_reason\":null}]}");
        List<LLMEvent> events = transform.transformSseEvent("", data);
        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.ReasoningDelta.class, events.get(0));
        assertEquals("Let me think...", ((LLMEvent.ReasoningDelta) events.get(0)).delta());
    }

    @Test
    void transformSseEvent_toolCall() throws Exception {
        // Delta with tool_calls emits ToolInputStart
        JsonNode deltaData = MAPPER.readTree("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read\",\"arguments\":\"{\\\"path\\\":\\\"test.txt\\\"}\"}}]},\"finish_reason\":null}]}");
        List<LLMEvent> deltaEvents = transform.transformSseEvent("", deltaData);
        assertTrue(deltaEvents.size() >= 1);
        assertTrue(deltaEvents.stream().anyMatch(e -> e instanceof LLMEvent.ToolInputStart));

        // finish_reason "tool_calls" emits ToolCall
        JsonNode finishData = MAPPER.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        List<LLMEvent> finishEvents = transform.transformSseEvent("", finishData);
        assertTrue(finishEvents.stream().anyMatch(e -> e instanceof LLMEvent.ToolCall));
    }

    @Test
    void transformSseEvent_finishReason() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        List<LLMEvent> events = transform.transformSseEvent("", data);
        assertTrue(events.stream().anyMatch(e -> e instanceof LLMEvent.Finish));
        LLMEvent.Finish finish = (LLMEvent.Finish) events.stream()
            .filter(e -> e instanceof LLMEvent.Finish).findFirst().orElse(null);
        assertNotNull(finish);
        assertEquals("end-turn", finish.reason());
    }

    @Test
    void transformSseEvent_usage() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":null}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}");
        List<LLMEvent> events = transform.transformSseEvent("", data);
        assertTrue(events.stream().anyMatch(e -> e instanceof LLMEvent.Usage));
    }

    @Test
    void transformSseEvent_emptyData() throws Exception {
        JsonNode data = MAPPER.readTree("{}");
        List<LLMEvent> events = transform.transformSseEvent("", data);
        assertTrue(events.isEmpty());
    }

    @Test
    void transformSseEvent_nullData() {
        List<LLMEvent> events = transform.transformSseEvent("", null);
        assertTrue(events.isEmpty());
    }

    @Test
    void transformSseEvent_mixedContentAndReasoning() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\",\"content\":\"answer\"},\"finish_reason\":null}]}");
        List<LLMEvent> events = transform.transformSseEvent("", data);
        assertEquals(2, events.size());
        // Reasoning comes first (matches DeepSeek's order)
        assertInstanceOf(LLMEvent.ReasoningDelta.class, events.get(0));
        assertInstanceOf(LLMEvent.TextDelta.class, events.get(1));
    }

    @Test
    void buildHttpRequest_includesToolParametersFromInputSchema() throws Exception {
        // RunLoop emits tool schemas keyed by "inputSchema" (see RunLoop.buildToolSchemas).
        Map<String, Object> inputSchema = Map.of(
            "type", "object",
            "properties", Map.of("path", Map.of("type", "string")),
            "required", List.of("path"));
        Map<String, Object> tools = Map.of(
            "read", Map.of("description", "Read a file", "inputSchema", inputSchema));

        LlmRequest request = new LlmRequest(
            Model.of("deepseek", "deepseek-chat", new ModelLimit(65536, null, 8192)),
            List.of("sys"), List.of(Map.of("role", "user", "content", "hi")),
            tools, "auto", null, null);

        var httpRequest = transform.buildHttpRequest(request, Map.of("deepseek", "sk-test"));

        JsonNode body = MAPPER.readTree(bodyOf(httpRequest));
        JsonNode fn = body.path("tools").path(0).path("function");
        assertEquals("read", fn.path("name").asText());
        JsonNode params = fn.path("parameters");
        assertFalse(params.isMissingNode(), "tool parameters must be sent, not dropped");
        assertEquals("object", params.path("type").asText());
        assertEquals("string", params.path("properties").path("path").path("type").asText());
    }

    @Test
    void buildHttpRequest_replaysReasoningContentAcrossToolTurnHistory() throws Exception {
        Model model = Model.of("deepseek", "deepseek-chat", new ModelLimit(65536, null, 8192));
        List<Message> history = List.of(
            new Message("u1", "s1", Role.USER, List.of(
                new MessagePart.TextPart("what's the weather?"))),
            new Message("a1", "s1", Role.ASSISTANT, List.of(
                new MessagePart.ReasoningPart("I should call the weather tool."),
                new MessagePart.ToolCallPart("call-1", "weather", "{\"city\":\"Hangzhou\"}"))),
            new Message("u2", "s1", Role.USER, List.of(
                new MessagePart.ToolResultPart("call-1", "weather", "sunny", false))),
            new Message("a2", "s1", Role.ASSISTANT, List.of(
                new MessagePart.ReasoningPart("The tool says it is sunny."),
                new MessagePart.TextPart("It is sunny."))),
            new Message("u3", "s1", Role.USER, List.of(
                new MessagePart.TextPart("what about tomorrow?"))));

        List<Map<String, Object>> modelMessages = MessageConverter.toOpenAIMessages(history, model);
        LlmRequest request = new LlmRequest(model, List.of(), modelMessages, Map.of(), "auto", null, null);

        var httpRequest = transform.buildHttpRequest(request, Map.of("deepseek", "sk-test"));

        JsonNode body = MAPPER.readTree(bodyOf(httpRequest));
        JsonNode messages = body.path("messages");

        assertEquals("I should call the weather tool.", messages.path(1).path("reasoning_content").asText(),
            "DeepSeek follow-up turns must replay prior assistant reasoning_content");
        assertEquals("The tool says it is sunny.", messages.path(3).path("reasoning_content").asText(),
            "DeepSeek follow-up turns must replay later assistant reasoning_content too");
        assertTrue(messages.path(1).path("content").isNull() || messages.path(1).path("content").isTextual(),
            "assistant tool-call history should not be rewritten into custom content arrays");
    }

    // ── helpers ──

    private LlmRequest createTestRequest() {
        return LlmRequest.of(
            Model.of("deepseek", "deepseek-chat", new ModelLimit(65536, null, 8192)),
            List.of("You are a helpful assistant."),
            List.of(Map.of("role", "user", "content", "Hello"))
        );
    }

    private static String bodyOf(java.net.http.HttpRequest request) {
        var publisher = request.bodyPublisher().orElseThrow();
        var buf = new StringBuilder();
        var latch = new java.util.concurrent.CountDownLatch(1);
        publisher.subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(java.nio.ByteBuffer item) {
                buf.append(java.nio.charset.StandardCharsets.UTF_8.decode(item));
            }
            public void onError(Throwable t) { latch.countDown(); }
            public void onComplete() { latch.countDown(); }
        });
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return buf.toString();
    }
}
