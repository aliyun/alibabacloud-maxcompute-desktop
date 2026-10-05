package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SseLlmClientTest {

    @Test
    void processSseStreamFlushesFinalBufferedEventAtEof() throws Exception {
        SseLlmClient client = new SseLlmClient();
        List<LLMEvent> events = new ArrayList<>();

        ProviderTransform transform = new ProviderTransform() {
            @Override
            public java.net.http.HttpRequest buildHttpRequest(LlmRequest request, java.util.Map<String, String> apiKeys) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.net.http.HttpRequest buildHttpRequest(LlmRequest request, String baseUrl, String apiKey) {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<LLMEvent> transformSseEvent(String eventType, JsonNode data) {
                return List.of(new LLMEvent.Finish(data.path("reason").asText("unknown")));
            }
        };

        Method method = SseLlmClient.class.getDeclaredMethod(
            "processSseStream", Stream.class, ProviderTransform.class, java.util.function.Consumer.class);
        method.setAccessible(true);
        method.invoke(client,
            Stream.of(
                "event: response.completed",
                "data: {\"reason\":\"tool-use\"}"
            ),
            transform,
            (java.util.function.Consumer<LLMEvent>) events::add
        );

        assertEquals(1, events.size());
        assertEquals("tool-use", ((LLMEvent.Finish) events.getFirst()).reason());
    }
}
