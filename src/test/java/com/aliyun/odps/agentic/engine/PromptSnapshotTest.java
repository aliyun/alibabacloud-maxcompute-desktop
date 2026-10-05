package com.aliyun.odps.agentic.engine;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.session.AgentEvent;
import com.aliyun.odps.agentic.session.PromptSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the PromptPrepared audit event / PromptSnapshot — captures what
 * was sent to the LLM (system prompt, projected messages, enabled tools)
 * right before each LLM call.
 */
class PromptSnapshotTest {

    private static final Model MOCK_MODEL =
        Model.of("mock", "mock-model", new ModelLimit(128000, null, 4096));

    @TempDir
    Path tempDir;

    @Test
    void run_emitsPromptPreparedBeforeLlmCall() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .model(MOCK_MODEL)
            .build();
        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent, "conv-prompt");

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        engine.runStreaming(session, agent, "Hello there!", events::add);

        List<PromptSnapshot> snapshots = events.stream()
            .filter(e -> e instanceof AgentEvent.PromptPrepared)
            .map(e -> ((AgentEvent.PromptPrepared) e).snapshot())
            .toList();

        assertFalse(snapshots.isEmpty(), "expected at least one PromptPrepared event");

        PromptSnapshot snap = snapshots.getFirst();
        assertEquals("conv-prompt", snap.sessionId());
        assertEquals("test-agent", snap.agentName());
        assertEquals("mock-model", snap.modelId());
        assertNotNull(snap.systemPrompt());
        assertFalse(snap.systemPrompt().isBlank());
        assertNotNull(snap.enabledTools());
        assertFalse(snap.enabledTools().isEmpty());
        assertNotNull(snap.createdAt());

        // projected messages should include the user turn we just sent
        assertFalse(snap.modelMessages().isEmpty(),
            "expected projected model messages, got empty list");
        assertTrue(snap.modelMessages().toString().contains("Hello there!"),
            "expected projected messages to contain the user input: " + snap.modelMessages());
    }

    @Test
    void promptPrepared_emittedBeforeLlmStart() {
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .model(MOCK_MODEL)
            .build();
        AgentDef agent = new SimpleTestAgent();
        Session session = engine.createSession(agent, "conv-order");

        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        engine.runStreaming(session, agent, "hi", events::add);

        int promptIdx = indexOf(events, AgentEvent.PromptPrepared.class);
        int llmStartIdx = indexOf(events, AgentEvent.LlmStart.class);
        assertTrue(promptIdx >= 0, "no PromptPrepared event");
        assertTrue(llmStartIdx >= 0, "no LlmStart event");
        assertTrue(promptIdx < llmStartIdx,
            "PromptPrepared (" + promptIdx + ") should precede LlmStart (" + llmStartIdx + ")");
    }

    @Test
    void skillCatalogIsOnlyAdvertisedWhenAgentEnablesSkillTool() throws Exception {
        Path skillDir = tempDir.resolve("maxframe");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: maxframe
            description: >
                MaxFrame development and documentation navigation.
                Use for English and Chinese MaxFrame queries.
            ---
            Follow the MaxFrame workflow.
            """);

        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .model(MOCK_MODEL)
            .skillPaths(List.of(skillDir))
            .build();

        PromptSnapshot withoutSkill = runAndGetSnapshot(engine, new ToolLimitedAgent(Set.of("read")), "no-skill");
        assertFalse(withoutSkill.systemPrompt().contains("<available_skills>"));
        assertFalse(withoutSkill.enabledTools().containsKey("skill"));

        PromptSnapshot withSkill = runAndGetSnapshot(
            engine, new ToolLimitedAgent(Set.of("read", "skill")), "with-skill");
        assertTrue(withSkill.systemPrompt().contains("<available_skills>"));
        assertTrue(withSkill.systemPrompt().contains("<name>maxframe</name>"));
        assertTrue(withSkill.systemPrompt().contains(
            "MaxFrame development and documentation navigation. "
                + "Use for English and Chinese MaxFrame queries."));
        assertEquals(Boolean.TRUE, withSkill.enabledTools().get("skill"));
    }

    @Test
    void skillCatalogIsFilteredByAgentProfile() throws Exception {
        for (String name : List.of("sql-skill", "udf-skill")) {
            Path skillDir = tempDir.resolve(name);
            Files.createDirectories(skillDir);
            Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: %s
                description: Dedicated %s workflow
                ---
                Follow this workflow.
                """.formatted(name, name));
        }
        HarnessEngine engine = HarnessEngine.builder()
            .llmClient(new MockLlmClient())
            .model(MOCK_MODEL)
            .skillPaths(List.of(tempDir))
            .build();

        AgentDef sqlAgent = new ToolLimitedAgent(Set.of("skill")) {
            @Override public Set<String> getIncludedSkills() { return Set.of("sql-skill"); }
        };
        PromptSnapshot snapshot = runAndGetSnapshot(engine, sqlAgent, "sql-profile");

        assertTrue(snapshot.systemPrompt().contains("<name>sql-skill</name>"));
        assertFalse(snapshot.systemPrompt().contains("<name>udf-skill</name>"));
    }

    private static PromptSnapshot runAndGetSnapshot(
            HarnessEngine engine, AgentDef agent, String conversationId) {
        Session session = engine.createSession(agent, conversationId);
        List<AgentEvent> events = new CopyOnWriteArrayList<>();
        engine.runStreaming(session, agent, "hello", events::add);
        return events.stream()
            .filter(AgentEvent.PromptPrepared.class::isInstance)
            .map(AgentEvent.PromptPrepared.class::cast)
            .map(AgentEvent.PromptPrepared::snapshot)
            .findFirst()
            .orElseThrow();
    }

    private static int indexOf(List<AgentEvent> events, Class<?> type) {
        for (int i = 0; i < events.size(); i++) {
            if (type.isInstance(events.get(i))) return i;
        }
        return -1;
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

    static class ToolLimitedAgent extends SimpleTestAgent {
        private final Set<String> includedTools;

        ToolLimitedAgent(Set<String> includedTools) {
            this.includedTools = includedTools;
        }

        @Override
        public Set<String> getIncludedTools() {
            return includedTools;
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
            eventConsumer.accept(new LLMEvent.TextDelta("Hi!"));
            eventConsumer.accept(new LLMEvent.TextEnd());
            eventConsumer.accept(new LLMEvent.Finish("end-turn"));
        }
    }
}
