package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAICompatibleTransformTest {

    private OpenAICompatibleTransform groqTransform;
    private OpenAICompatibleTransform ollamaTransform;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeEach
    void setup() {
        groqTransform = new OpenAICompatibleTransform("groq");
        ollamaTransform = new OpenAICompatibleTransform("ollama");
    }

    // ── buildHttpRequest ──

    @Test
    void buildHttpRequest_usesProviderBaseUrl() throws Exception {
        LlmRequest request = createTestRequest();
        var httpRequest = groqTransform.buildHttpRequest(request,
            Map.of("groq", "gsk-test", "groq_base_url", "https://api.groq.com/openai/v1/chat/completions"));
        assertEquals("https://api.groq.com/openai/v1/chat/completions", httpRequest.uri().toString());
    }

    @Test
    void buildHttpRequest_includesAuthHeader() throws Exception {
        LlmRequest request = createTestRequest();
        var httpRequest = groqTransform.buildHttpRequest(request,
            Map.of("groq", "gsk-test", "groq_base_url", "https://api.groq.com/openai/v1/chat/completions"));
        String auth = httpRequest.headers().firstValue("Authorization").orElse("");
        assertEquals("Bearer gsk-test", auth);
    }

    @Test
    void buildHttpRequest_ollama_noAuthWhenNoKey() throws Exception {
        LlmRequest request = createTestRequest();
        var httpRequest = ollamaTransform.buildHttpRequest(request,
            Map.of("ollama_base_url", "http://localhost:11434/v1/chat/completions"));
        // No Authorization header when no API key
        assertTrue(httpRequest.headers().allValues("Authorization").isEmpty());
    }

    @Test
    void buildHttpRequest_throwsWhenNoBaseUrl() {
        LlmRequest request = createTestRequest();
        assertThrows(IllegalArgumentException.class, () ->
            groqTransform.buildHttpRequest(request, Map.of("groq", "gsk-test")));
    }

    @Test
    void buildHttpRequest_fallsBackToDefaultKey() throws Exception {
        LlmRequest request = createTestRequest();
        var httpRequest = groqTransform.buildHttpRequest(request,
            Map.of("default", "sk-fallback", "groq_base_url", "https://api.groq.com/openai/v1/chat/completions"));
        String auth = httpRequest.headers().firstValue("Authorization").orElse("");
        assertEquals("Bearer sk-fallback", auth);
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
            Model.of("groq", "llama-3.1-70b-versatile", new ModelLimit(131072, null, 8192)),
            List.of("sys"), List.of(Map.of("role", "user", "content", "hi")),
            tools, "auto", null, null);

        var httpRequest = groqTransform.buildHttpRequest(request,
            Map.of("groq", "gsk-test", "groq_base_url", "https://api.groq.com/openai/v1/chat/completions"));

        JsonNode body = MAPPER.readTree(bodyOf(httpRequest));
        JsonNode fn = body.path("tools").path(0).path("function");
        assertEquals("read", fn.path("name").asText());
        JsonNode params = fn.path("parameters");
        assertFalse(params.isMissingNode(), "tool parameters must be sent, not dropped");
        assertEquals("object", params.path("type").asText());
        assertEquals("string", params.path("properties").path("path").path("type").asText());
    }

    @Test
    void buildHttpRequest_includesUsageStreamingAndCompatibleOptions() throws Exception {
        LlmRequest request = new LlmRequest(
            Model.of("alibaba-cn", "qwen3-coder-plus", new ModelLimit(131072, null, 8192)),
            List.of("sys"), List.of(Map.of("role", "user", "content", "hi")),
            Map.of(), "auto", 1.0, 4096,
            Map.of("openaiCompatible", Map.of(
                "reasoningEffort", "high",
                "enableThinking", true
            ))
        );

        var httpRequest = new OpenAICompatibleTransform("alibaba-cn").buildHttpRequest(
            request,
            Map.of("alibaba-cn", "sk-test", "alibaba-cn_base_url", "https://dashscope.example/v1/chat/completions")
        );

        JsonNode body = MAPPER.readTree(bodyOf(httpRequest));
        assertTrue(body.path("stream_options").path("include_usage").asBoolean());
        assertEquals("high", body.path("reasoning_effort").asText());
        assertTrue(body.path("enable_thinking").asBoolean());
    }

    // ── transformSseEvent ──

    @Test
    void transformSseEvent_textDelta() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{\"content\":\"Hello world\"},\"finish_reason\":null}]}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.TextDelta.class, events.get(0));
        assertEquals("Hello world", ((LLMEvent.TextDelta) events.get(0)).delta());
    }

    @Test
    void transformSseEvent_reasoningDelta() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{\"reasoning_content\":\"Let me think\"},\"finish_reason\":null}]}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        assertEquals(1, events.size());
        assertInstanceOf(LLMEvent.ReasoningDelta.class, events.get(0));
        assertEquals("Let me think", ((LLMEvent.ReasoningDelta) events.get(0)).delta());
    }

    @Test
    void transformSseEvent_toolCall() throws Exception {
        // Delta with tool_calls emits ToolInputStart
        JsonNode deltaData = MAPPER.readTree("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}");
        List<LLMEvent> deltaEvents = groqTransform.transformSseEvent("", deltaData);
        assertTrue(deltaEvents.stream().anyMatch(e -> e instanceof LLMEvent.ToolInputStart));

        // finish_reason "tool_calls" emits ToolCall
        JsonNode finishData = MAPPER.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        List<LLMEvent> finishEvents = groqTransform.transformSseEvent("", finishData);
        assertTrue(finishEvents.stream().anyMatch(e -> e instanceof LLMEvent.ToolCall));
    }

    @Test
    void transformSseEvent_finishReason_stop() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        LLMEvent.Finish finish = (LLMEvent.Finish) events.stream()
            .filter(e -> e instanceof LLMEvent.Finish).findFirst().orElse(null);
        assertNotNull(finish);
        assertEquals("end-turn", finish.reason());
    }

    @Test
    void transformSseEvent_finishReason_toolCalls() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        LLMEvent.Finish finish = (LLMEvent.Finish) events.stream()
            .filter(e -> e instanceof LLMEvent.Finish).findFirst().orElse(null);
        assertNotNull(finish);
        assertEquals("tool-use", finish.reason());
    }

    @Test
    void transformSseEvent_usage() throws Exception {
        JsonNode data = MAPPER.readTree("{\"choices\":[{\"delta\":{},\"finish_reason\":null}],\"usage\":{\"prompt_tokens\":50,\"completion_tokens\":100,\"completion_tokens_details\":{\"reasoning_tokens\":7}}}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        LLMEvent.Usage usageEvent = (LLMEvent.Usage) events.stream()
            .filter(e -> e instanceof LLMEvent.Usage).findFirst().orElseThrow();
        assertEquals(7, usageEvent.usage().reasoningTokens());
    }

    @Test
    void transformSseEvent_usageOnlyChunk() throws Exception {
        JsonNode data = MAPPER.readTree("{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34}}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        assertTrue(events.stream().anyMatch(e -> e instanceof LLMEvent.Usage));
    }

    @Test
    void transformSseEvent_providerError() throws Exception {
        JsonNode data = MAPPER.readTree("{\"error\":{\"message\":\"provider boom\"}}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        LLMEvent.ProviderError error = (LLMEvent.ProviderError) events.stream()
            .filter(e -> e instanceof LLMEvent.ProviderError).findFirst().orElseThrow();
        assertEquals("provider boom", error.error());
    }

    @Test
    void transformSseEvent_emptyData() throws Exception {
        JsonNode data = MAPPER.readTree("{}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        assertTrue(events.isEmpty());
    }

    @Test
    void transformSseEvent_nullData() {
        List<LLMEvent> events = groqTransform.transformSseEvent("", null);
        assertTrue(events.isEmpty());
    }

    @Test
    void transformSseEvent_usageTrailerWithEmptyChoicesArray() throws Exception {
        // DashScope / 通义的用量尾包长这样：choices 是一个**空数组**，而不是没有 choices 键。
        // 旧实现只认「没有 choices 键」那一种，这一种会被 isEmpty() 提前返回吞掉，
        // 于是宿主的 token 统计恒为 0 —— 明明请求侧一直在发 stream_options.include_usage。
        JsonNode data = MAPPER.readTree(
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":1200,\"completion_tokens\":80}}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        LLMEvent.Usage usage = (LLMEvent.Usage) events.stream()
            .filter(e -> e instanceof LLMEvent.Usage).findFirst().orElseThrow();
        assertEquals(1200, usage.usage().inputTokens());
        assertEquals(80, usage.usage().outputTokens());
    }

    @Test
    void transformSseEvent_nullErrorFieldIsNotAnError() throws Exception {
        // 网关常在正常分片里带一个 "error": null。把它当成错误会让整轮对话在第一片就断，
        // 所以判据必须是「存在且非 null」，不能只看 has("error")。
        JsonNode data = MAPPER.readTree(
            "{\"error\":null,\"choices\":[{\"delta\":{\"content\":\"hi\"},\"finish_reason\":null}]}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        assertFalse(events.stream().anyMatch(e -> e instanceof LLMEvent.ProviderError));
        assertTrue(events.stream().anyMatch(e -> e instanceof LLMEvent.TextDelta));
    }

    @Test
    void transformSseEvent_usageEmittedOnceWhenChoicesPresent() throws Exception {
        // 用量的处理点上移到方法开头后，末尾那段重复取用量必须删掉，否则同一片发两次 Usage。
        JsonNode data = MAPPER.readTree(
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}");
        List<LLMEvent> events = groqTransform.transformSseEvent("", data);
        assertEquals(1, events.stream().filter(e -> e instanceof LLMEvent.Usage).count());
    }

    @Test
    void buildHttpRequest_enableThinkingFalseIsSentExplicitly() throws Exception {
        // 三态的关键一半：显式 false 必须真的下发 enable_thinking:false。
        // 折叠成 boolean 时「显式关」与「没表态」不可区分，那些服务端默认开思考链的模型就关不掉。
        LlmRequest request = new LlmRequest(
            Model.of("alibaba-cn", "qwen3-coder-plus", new ModelLimit(131072, null, 8192)),
            List.of("sys"), List.of(Map.of("role", "user", "content", "hi")),
            Map.of(), "auto", 1.0, 4096,
            Map.of("openaiCompatible", Map.of("enableThinking", false))
        );
        var httpRequest = new OpenAICompatibleTransform("alibaba-cn").buildHttpRequest(
            request, Map.of("alibaba-cn", "sk-test",
                "alibaba-cn_base_url", "https://dashscope.example/v1/chat/completions"));
        JsonNode body = MAPPER.readTree(bodyOf(httpRequest));
        assertTrue(body.has("enable_thinking"), "explicit false must still be sent");
        assertFalse(body.path("enable_thinking").asBoolean());
    }

    @Test
    void buildHttpRequest_enableThinkingOmittedWhenNotStated() throws Exception {
        // 三态的另一半：没表态就完全不带这个字段，交给提供者的默认值。
        LlmRequest request = new LlmRequest(
            Model.of("alibaba-cn", "qwen3-coder-plus", new ModelLimit(131072, null, 8192)),
            List.of("sys"), List.of(Map.of("role", "user", "content", "hi")),
            Map.of(), "auto", 1.0, 4096, Map.of()
        );
        var httpRequest = new OpenAICompatibleTransform("alibaba-cn").buildHttpRequest(
            request, Map.of("alibaba-cn", "sk-test",
                "alibaba-cn_base_url", "https://dashscope.example/v1/chat/completions"));
        JsonNode body = MAPPER.readTree(bodyOf(httpRequest));
        assertFalse(body.has("enable_thinking"));
    }

    // ── getProviderId ──

    @Test
    void getProviderId() {
        assertEquals("groq", groqTransform.getProviderId());
        assertEquals("ollama", ollamaTransform.getProviderId());
    }

    // ── helpers ──

    private LlmRequest createTestRequest() {
        return LlmRequest.of(
            Model.of("groq", "llama-3.1-70b-versatile", new ModelLimit(131072, null, 8192)),
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
