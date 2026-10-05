package com.aliyun.odps.agentic.engine;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.session.InMemoryMessageStore;
import com.aliyun.odps.agentic.session.MessageStore;
import com.aliyun.odps.agentic.session.SessionSnapshot;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for external session/message persistence: pluggable MessageStore,
 * deterministic session ids, initial-history seeding, and session snapshots.
 */
class SessionPersistenceTest {

    private static final Model MOCK_MODEL =
        Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096));

    // ── Pluggable MessageStore ──

    @Test
    void builder_usesInjectedMessageStore() {
        MessageStore custom = new InMemoryMessageStore();
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .messageStore(custom)
            .build();

        assertSame(custom, engine.getMessageStore());
    }

    @Test
    void builder_defaultsToInMemoryMessageStore() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .build();

        assertNotNull(engine.getMessageStore());
        assertTrue(engine.getMessageStore() instanceof InMemoryMessageStore);
    }

    // ── Deterministic session ids ──

    @Test
    void createSession_withExplicitId_isDeterministic() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .build();
        AgentDef agent = new SimpleTestAgent();

        Session s = engine.createSession(agent, "conversation-123");

        assertEquals("conversation-123", s.id());
        assertEquals("test-agent", s.agent());
    }

    @Test
    void createSession_withMessages_storesHistory() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .build();
        AgentDef agent = new SimpleTestAgent();

        List<Message> history = List.of(
            userMsg("conv-1", "What is 2+2?"),
            assistantMsg("conv-1", "4"));

        Session s = engine.createSession(agent, "conv-1", history);

        assertEquals("conv-1", s.id());
        assertEquals(2, s.messages().size());
    }

    // ── Initial history survives the RunLoop reload ──

    @Test
    void run_withSeededHistory_passesHistoryToLlm() {
        MessageCapturingLlmClient client = new MessageCapturingLlmClient();
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(client)
            .model(MOCK_MODEL)
            .build();
        AgentDef agent = new SimpleTestAgent();

        List<Message> history = List.of(
            userMsg("conv-hist", "Remember the secret word BANANA."),
            assistantMsg("conv-hist", "Got it, BANANA."));

        Session session = engine.createSession(agent, "conv-hist", history);
        engine.runStreaming(session, agent, "What was the secret word?", e -> {});

        String seen = client.lastMessages.toString();
        assertTrue(seen.contains("BANANA"),
            "Expected prior history to reach the LLM request, got: " + seen);
    }

    // ── Snapshot export/import ──

    @Test
    void exportSession_prefersMessageStoreOverSessionField() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .model(MOCK_MODEL)
            .build();
        AgentDef agent = new SimpleTestAgent();

        // Session field carries one (stale) message...
        Session session = engine.createSession(agent, "conv-export",
            List.of(userMsg("conv-export", "stale")));
        // ...but the store has the authoritative two.
        engine.getMessageStore().updateMessage("conv-export", userMsg("conv-export", "fresh-1"));
        engine.getMessageStore().updateMessage("conv-export", assistantMsg("conv-export", "fresh-2"));

        SessionSnapshot snapshot = engine.exportSession(session);

        assertEquals("conv-export", snapshot.sessionId());
        assertEquals(2, snapshot.messages().size());
        assertTrue(snapshot.messages().stream()
            .noneMatch(m -> m.getTextContent().equals("stale")));
    }

    @Test
    void importSession_restoresHistoryRunnable() {
        MessageCapturingLlmClient client = new MessageCapturingLlmClient();
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(client)
            .model(MOCK_MODEL)
            .build();
        AgentDef agent = new SimpleTestAgent();

        SessionSnapshot snapshot = new SessionSnapshot(
            "conv-import", "test-agent", "mock-model",
            List.of(userMsg("conv-import", "secret word is KIWI"),
                    assistantMsg("conv-import", "ok KIWI")),
            0.0, null, java.time.Instant.now(), java.time.Instant.now(), Map.of());

        Session session = engine.importSession(snapshot, agent);
        assertEquals("conv-import", session.id());

        engine.runStreaming(session, agent, "what was it?", e -> {});
        assertTrue(client.lastMessages.toString().contains("KIWI"));
    }

    // ── MessageStore default methods ──

    @Test
    void messageStore_updateMessages_batchInserts() {
        MessageStore store = new InMemoryMessageStore();
        store.updateMessages("s1", List.of(
            userMsg("s1", "a"), assistantMsg("s1", "b")));
        assertEquals(2, store.getMessages("s1").size());
    }

    @Test
    void messageStore_getMessage_findsById() {
        MessageStore store = new InMemoryMessageStore();
        Message m = userMsg("s1", "hello");
        store.updateMessage("s1", m);

        assertTrue(store.getMessage("s1", m.id()).isPresent());
        assertEquals("hello", store.getMessage("s1", m.id()).get().getTextContent());
        assertTrue(store.getMessage("s1", "missing").isEmpty());
    }

    // ── Fixtures ──

    private static Message userMsg(String sessionId, String text) {
        return new Message(UUID.randomUUID().toString(), sessionId, Role.USER,
            List.of(new MessagePart.TextPart(text)));
    }

    private static Message assistantMsg(String sessionId, String text) {
        return new Message(UUID.randomUUID().toString(), sessionId, Role.ASSISTANT,
            List.of(new MessagePart.TextPart(text)));
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

    static class MessageCapturingLlmClient implements LLMClient {
        volatile List<Map<String, Object>> lastMessages = List.of();

        @Override
        public boolean supports(String providerId) {
            return "mock".equals(providerId);
        }

        @Override
        public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
            this.lastMessages = request.messages();
            eventConsumer.accept(new LLMEvent.TextStart("mock-model"));
            eventConsumer.accept(new LLMEvent.TextDelta("ack"));
            eventConsumer.accept(new LLMEvent.TextEnd());
            eventConsumer.accept(new LLMEvent.Finish("end-turn"));
        }
    }

    static class MockLlmClient implements LLMClient {
        @Override
        public boolean supports(String providerId) {
            return "mock".equals(providerId);
        }

        @Override
        public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
            eventConsumer.accept(new LLMEvent.TextStart("mock-model"));
            eventConsumer.accept(new LLMEvent.TextDelta("Hello!"));
            eventConsumer.accept(new LLMEvent.TextEnd());
            eventConsumer.accept(new LLMEvent.Finish("end-turn"));
        }
    }
}
