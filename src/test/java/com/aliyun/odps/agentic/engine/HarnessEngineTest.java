package com.aliyun.odps.agentic.engine;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.session.AgentEvent;
import com.aliyun.odps.agentic.session.CompactionReason;
import com.aliyun.odps.agentic.session.ManualCompactionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for HarnessEngine — verifies the full SDK flow.
 */
class HarnessEngineTest {

    // ── Builder tests ──

    @Test
    void builder_requiresLlmClient() {
        assertThrows(IllegalStateException.class, () ->
            HarnessEngine.builder().build());
    }

    @Test
    void builder_withLlmClient_succeeds() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .build();
        assertNotNull(engine);
        assertNotNull(engine.getToolRegistry());
        assertNotNull(engine.getSkillLoader());
    }

    @Test
    void builder_withCustomWorkDir() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .workDir(Path.of("/tmp"))
            .build();
        assertEquals(Path.of("/tmp"), engine.getWorkDir());
    }

    // ── Session creation tests ──

    @Test
    void createSession_returnsValidSession() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .build();

        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent);

        assertNotNull(session.id());
        assertEquals("test-agent", session.agent());
        assertNotNull(session.createdAt());
    }

    @Test
    void createSession_differentIds() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .build();

        AgentDef agent = new SimpleTestAgent();
        Session s1 = engine.createSession(agent);
        Session s2 = engine.createSession(agent);

        assertNotEquals(s1.id(), s2.id());
    }

    // ── Run with mock LLM tests ──

    @Test
    void run_withMockLlm_returnsResponse() {
        MockLlmClient mockClient = new MockLlmClient();
        Model model = Model.of("mock", "mock-model",
            new ModelLimit(128000, null, 4096));

        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockClient)
            .model(model)
            .build();

        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent);

        Message result = engine.run(session, agent, "Hello!");
        assertNotNull(result);
    }

    @Test
    void runWithEvents_collectsEvents() {
        MockLlmClient mockClient = new MockLlmClient();
        Model model = Model.of("mock", "mock-model",
            new ModelLimit(128000, null, 4096));

        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockClient)
            .model(model)
            .build();

        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent);

        HarnessEngine.RunResult result = engine.runWithEvents(session, agent, "Hello!");
        assertNotNull(result.finalMessage());
        assertNotNull(result.events());
        assertFalse(result.events().isEmpty());
    }

    @Test
    void runStreaming_receivesEvents() {
        MockLlmClient mockClient = new MockLlmClient();
        Model model = Model.of("mock", "mock-model",
            new ModelLimit(128000, null, 4096));

        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockClient)
            .model(model)
            .build();

        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent);

        List<AgentEvent> received = new ArrayList<>();
        Message result = engine.runStreaming(session, agent, "Hello!", received::add);

        assertNotNull(result);
        assertFalse(received.isEmpty());
    }

    @Test
    void runStreaming_preservesStructuredUserParts() {
        List<LlmRequest> observed = new java.util.concurrent.CopyOnWriteArrayList<>();
        MockLlmClient client = new MockLlmClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                observed.add(request);
                super.stream(request, events);
            }
        };
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(client)
            .model(Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096)))
            .build();
        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent);
        MessagePart.ImagePart image = MessagePart.ImagePart.fromUrl(
            "https://example.test/image.png", "image/png");

        engine.runStreaming(session, agent,
            List.of(image, new MessagePart.TextPart("Describe this image")), event -> {});

        Message user = engine.getMessageStore().getMessages(session.id()).stream()
            .filter(message -> message.role() == Role.USER)
            .findFirst().orElseThrow();
        assertEquals(image, user.parts().getFirst());
        assertEquals("Describe this image", user.getTextContent());
        assertFalse(observed.isEmpty());
        assertTrue(observed.stream().anyMatch(request -> request.messages().toString()
            .contains("https://example.test/image.png")),
            "the image must reach the provider request, not only the message store");
    }

    @Test
    void cancel_activeSessionStopsTrackedRun() throws Exception {
        BlockingLlmClient client = new BlockingLlmClient();
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(client)
            .model(Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096)))
            .build();
        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent, "cancel-me");
        List<AgentEvent> events = Collections.synchronizedList(new ArrayList<>());

        var future = engine.runAsyncStreaming(session, agent, "wait", events::add);
        assertTrue(client.started.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(engine.isRunning(session.id()));
        assertTrue(engine.cancel(session.id()));
        client.release.countDown();

        assertNotNull(future.get(5, java.util.concurrent.TimeUnit.SECONDS));
        assertFalse(engine.isRunning(session.id()));
        assertFalse(engine.cancel(session.id()));
        assertFalse(events.isEmpty(), "the cancelled run should still close its observable event stream");
    }

    @Test
    void compact_withSeededHistoryExposesManualApi() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .model(Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096)))
            .build();

        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent).withMessages(createLongHistory("session-compact", 12));

        ManualCompactionResult result = engine.compact(session, agent);

        assertTrue(result.changed());
        assertEquals(CompactionReason.MANUAL, result.reason());
        assertNotNull(result.summaryMessage());
    }

    // ── Session-level model switching ──

    @Test
    void run_usesSessionModel_whenSet() {
        ModelCapturingLlmClient mockClient = new ModelCapturingLlmClient();
        Model defaultModel = Model.of("mock", "default-model", new ModelLimit(128000, null, 4096));
        Model switchedModel = Model.of("mock", "switched-model", new ModelLimit(200000, null, 8192));

        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockClient)
            .model(defaultModel)
            .build();

        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent);

        // First run uses the engine default model
        engine.run(session, agent, "Hello!");
        assertEquals("default-model", mockClient.lastModelId);

        // Switching the session model takes effect on the next run
        Session switched = session.withModel(switchedModel);
        engine.run(switched, agent, "Hello again!");
        assertEquals("switched-model", mockClient.lastModelId);
    }

    @Test
    void run_fallsBackToEngineModel_whenSessionModelNull() {
        ModelCapturingLlmClient mockClient = new ModelCapturingLlmClient();
        Model defaultModel = Model.of("mock", "default-model", new ModelLimit(128000, null, 4096));

        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(mockClient)
            .model(defaultModel)
            .build();

        AgentDef agent = new SimpleTestAgent();
        // Session with null model should fall back to the engine default
        Session session = new Session("s-null-model", null,
            com.aliyun.odps.agentic.model.SessionStatus.IDLE, List.of(),
            agent.getName(), null, 0.0, null,
            java.time.Instant.now(), java.time.Instant.now(), null);

        engine.run(session, agent, "Hello!");
        assertEquals("default-model", mockClient.lastModelId);
    }

    // ── Tool registry tests ──

    @Test
    void builtinTools_registered() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .build();

        // 12 builtin tools should be registered
        var tools = engine.getToolRegistry().all();
        assertTrue(tools.size() >= 12, "Expected at least 12 builtin tools, got " + tools.size());

        // Check specific tools exist
        assertNotNull(engine.getToolRegistry().resolve("apply_patch"));
        assertNotNull(engine.getToolRegistry().resolve("read"));
        assertNotNull(engine.getToolRegistry().resolve("write"));
        assertNotNull(engine.getToolRegistry().resolve("shell"));
    }

    // ── Test fixtures ──

    private static List<Message> createLongHistory(String sessionId, int turns) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            messages.add(new Message(UUID.randomUUID().toString(), sessionId, Role.USER,
                List.of(new MessagePart.TextPart("User message " + i + " " + "x".repeat(200)))));
            messages.add(new Message(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT,
                List.of(new MessagePart.TextPart("Assistant response " + i + " " + "y".repeat(200)))));
        }
        return messages;
    }

    static class SimpleTestAgent implements AgentDef {
        @Override
        public String getName() { return "test-agent"; }

        @Override
        public String getSystemPrompt(Function<String, String> metaProvider) {
            return "You are a test agent.";
        }

        @Override
        public int getMaxSteps() { return 5; }
    }

    /**
     * Mock LLM client that records the model id of the last request.
     */
    static class ModelCapturingLlmClient implements LLMClient {
        volatile String lastModelId;

        @Override
        public boolean supports(String providerId) {
            return "mock".equals(providerId);
        }

        @Override
        public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
            lastModelId = request.model().apiId();
            eventConsumer.accept(new LLMEvent.TextStart(request.model().apiId()));
            eventConsumer.accept(new LLMEvent.TextDelta("ok"));
            eventConsumer.accept(new LLMEvent.TextEnd());
            eventConsumer.accept(new LLMEvent.Finish("end-turn"));
        }
    }

    /**
     * Mock LLM client that returns a simple text response.
     */
    static class MockLlmClient implements LLMClient {

        @Override
        public boolean supports(String providerId) {
            return "mock".equals(providerId);
        }

        @Override
        public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
            // Emit a simple text response
            eventConsumer.accept(new LLMEvent.TextStart("mock-model"));
            eventConsumer.accept(new LLMEvent.TextDelta("Hello! I'm a mock agent response."));
            eventConsumer.accept(new LLMEvent.TextEnd());
            eventConsumer.accept(new LLMEvent.Finish("end-turn"));
        }
    }

    static class BlockingLlmClient implements LLMClient {
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        @Override
        public boolean supports(String providerId) {
            return "mock".equals(providerId);
        }

        @Override
        public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
            started.countDown();
            try {
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            eventConsumer.accept(new LLMEvent.TextStart("mock-model"));
            eventConsumer.accept(new LLMEvent.TextDelta("released"));
            eventConsumer.accept(new LLMEvent.TextEnd());
            eventConsumer.accept(new LLMEvent.Finish("end-turn"));
        }
    }
}
