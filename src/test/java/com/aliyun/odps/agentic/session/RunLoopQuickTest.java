package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.*;
import com.aliyun.odps.agentic.patch.PatchEngine;
import com.aliyun.odps.agentic.permission.PermissionEngine;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class RunLoopQuickTest {

    @Test
    void resumedRunIngestsAsyncObservationBeforeExitCheck() {
        AtomicBoolean ready = new AtomicBoolean();
        AtomicBoolean sent = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                if (calls.incrementAndGet() == 2) {
                    assertTrue(request.sourceMessages().stream()
                        .anyMatch(m -> m.getTextContent().contains("async rows ready")));
                }
                events.accept(new LLMEvent.TextDelta(calls.get() == 1 ? "waiting" : "done"));
                events.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String providerId) { return true; }
            @Override public boolean requiresProviderProjection() { return false; }
        };
        RunPolicy policy = new RunPolicy() {
            @Override public FinishDecision beforeFinish(Session s, AgentDef a,
                    Message candidate, List<Message> history) {
                return ready.get() ? FinishDecision.accept()
                    : FinishDecision.suspend("waiting_analysis");
            }
            @Override public List<List<MessagePart>> beforeStep(Session s, AgentDef a,
                    List<Message> history) {
                return ready.get() && sent.compareAndSet(false, true)
                    ? List.of(List.of(new MessagePart.TextPart("async rows ready")))
                    : List.of();
            }
        };
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public RunPolicy getRunPolicy() { return policy; }
        };
        Model model = Model.of("test", "model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        Session session = new Session("async-resume", "Async", SessionStatus.IDLE,
            List.of(), "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());
        RunLoop first = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(), new PatchEngine(),
            event -> {}, model, ".", null, store, null);
        assertTrue(first.runUntilPause(session, agent,
            List.of(new MessagePart.TextPart("analyze"))).suspended());
        ready.set(true);
        RunLoop resumed = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(), new PatchEngine(),
            event -> {}, model, ".", null, store, null);
        assertEquals("done", resumed.resumeUntilPause(session, agent).message().getTextContent());
        assertEquals(2, calls.get());
    }

    @Test
    void hostPlanBatchRunsBeforeFirstModelCallAndUsesOrdinaryToolHistory() {
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicInteger batchCalls = new AtomicInteger();
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                llmCalls.incrementAndGet();
                assertTrue(request.sourceMessages().stream().flatMap(m -> m.parts().stream())
                    .anyMatch(part -> part instanceof MessagePart.ToolResultPart tr
                        && "planned".equals(tr.callID())));
                events.accept(new LLMEvent.TextDelta("done"));
                events.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String providerId) { return true; }
            @Override public boolean requiresProviderProjection() { return false; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        List<AgentEvent> events = new ArrayList<>();
        RunLoop loop = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(), new PatchEngine(),
            events::add, model, ".", null, store, null);
        RunPolicy policy = new RunPolicy() {
            @Override public FinishDecision beforeFinish(Session s, AgentDef a,
                    Message m, List<Message> history) { return FinishDecision.accept(); }
            @Override public List<MessagePart.ToolCallPart> beforeModelCall(Session s,
                    AgentDef a, List<Message> history) {
                return batchCalls.get() == 0
                    ? List.of(new MessagePart.ToolCallPart("planned", "query", "{}"))
                    : List.of();
            }
        };
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public RunPolicy getRunPolicy() { return policy; }
            @Override public Optional<ToolBatchExecutor> getToolBatchExecutor() {
                return Optional.of((calls, early) -> {
                    batchCalls.incrementAndGet();
                    return List.of(new MessagePart.ToolResultPart(
                        "planned", "query", "row", false));
                });
            }
        };
        Session session = new Session("pre-model", "pre-model", SessionStatus.IDLE,
            List.of(), "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());
        Message result = loop.run(session, agent, "Query");
        assertEquals("done", result.getTextContent());
        assertEquals(1, batchCalls.get());
        assertEquals(1, llmCalls.get());
        assertEquals(1, events.stream()
            .filter(event -> event instanceof AgentEvent.BeforeLLMCall).count());
    }

    @Test
    void suspendedRunResumesFromPersistedHistoryWithoutRepeatingUserOrLlmCall() {
        AtomicInteger llmCalls = new AtomicInteger();
        AtomicBoolean analysisReady = new AtomicBoolean();
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                llmCalls.incrementAndGet();
                events.accept(new LLMEvent.TextDelta("Analysis pending"));
                events.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String providerId) { return true; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        List<AgentEvent> events = new ArrayList<>();
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public RunPolicy getRunPolicy() {
                return (session, definition, candidate, history) -> analysisReady.get()
                    ? RunPolicy.FinishDecision.accept()
                    : RunPolicy.FinishDecision.suspend("waiting_analysis");
            }
        };
        Session session = new Session("suspended", "title", SessionStatus.IDLE, List.of(),
            "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());
        RunLoop first = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(), new PatchEngine(),
            events::add, model, ".", null, store, null);

        RunOutcome paused = first.runUntilPause(session, agent,
            List.of(new MessagePart.TextPart("Analyze")));
        assertTrue(paused.suspended());
        assertEquals("waiting_analysis", paused.suspendReason());
        assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Suspended));
        assertFalse(events.stream().anyMatch(event -> event instanceof AgentEvent.Finished));

        analysisReady.set(true);
        RunLoop resumedLoop = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(), new PatchEngine(),
            events::add, model, ".", null, store, null);
        RunOutcome resumed = resumedLoop.resumeUntilPause(session, agent);

        assertFalse(resumed.suspended());
        assertEquals("Analysis pending", resumed.message().getTextContent());
        assertEquals(1, llmCalls.get());
        assertEquals(1, store.getMessages(session.id()).stream()
            .filter(message -> message.role() == Role.USER).count());
    }

    @Test
    void hostBatchPreservesOrderedResultsAndEmitsEarlyCompletionOnce() {
        AtomicInteger turns = new AtomicInteger();
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                if (turns.incrementAndGet() == 1) {
                    events.accept(new LLMEvent.ToolCall("c1", "query", "{}"));
                    events.accept(new LLMEvent.ToolCall("c2", "chart", "{}"));
                    events.accept(new LLMEvent.Finish("tool-use"));
                } else {
                    events.accept(new LLMEvent.TextDelta("Done"));
                    events.accept(new LLMEvent.Finish("end-turn"));
                }
            }
            @Override public boolean supports(String providerId) { return true; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        List<AgentEvent> events = new ArrayList<>();
        RunLoop loop = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(), new PatchEngine(),
            events::add, model, ".", null, store, null);
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public List<ToolDef> getTools() {
                return List.of(advertisedHostTool("query"), advertisedHostTool("chart"));
            }
            @Override public Optional<ToolBatchExecutor> getToolBatchExecutor() {
                return Optional.of((calls, early) -> {
                    assertEquals(List.of("c1", "c2"), calls.stream()
                        .map(MessagePart.ToolCallPart::callID).toList());
                    var first = new MessagePart.ToolResultPart("c1", "query", "2 rows", false);
                    var second = new MessagePart.ToolResultPart("c2", "chart", "chart.png", false);
                    early.accept(second);
                    return List.of(first, second);
                });
            }
        };
        Session session = new Session("host-batch", "batch", SessionStatus.IDLE, List.of(),
            "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());

        Message answer = loop.run(session, agent, "Query and chart");

        assertEquals("Done", answer.getTextContent());
        List<MessagePart.ToolResultPart> persisted = store.getMessages(session.id()).stream()
            .flatMap(message -> message.parts().stream())
            .filter(part -> part instanceof MessagePart.ToolResultPart)
            .map(part -> (MessagePart.ToolResultPart) part).toList();
        assertEquals(List.of("c1", "c2"), persisted.stream()
            .map(MessagePart.ToolResultPart::callID).toList());
        assertEquals(2, events.stream()
            .filter(event -> event instanceof AgentEvent.ToolCallCompleted).count());
        assertEquals(List.of(1, 0), events.stream()
            .filter(event -> event instanceof AgentEvent.HostToolResultSettled)
            .map(event -> ((AgentEvent.HostToolResultSettled) event).batchIndex()).toList());
    }

    private static ToolDef advertisedHostTool(String name) {
        return new ToolDef() {
            @Override public String getId() { return name; }
            @Override public String getDescription() { return name; }
            @Override public com.fasterxml.jackson.databind.node.ObjectNode getParametersSchema() {
                return new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                    .put("type", "object");
            }
            @Override public com.aliyun.odps.agentic.tool.ToolResult execute(
                    com.fasterxml.jackson.databind.JsonNode args,
                    com.aliyun.odps.agentic.tool.ToolContext context) {
                throw new AssertionError("Host tool must be executed by its batch executor");
            }
        };
    }

    @Test
    void hostBatchRejectsUnadvertisedCallAndKeepsMixedResultsPaired() {
        AtomicInteger modelCalls = new AtomicInteger();
        AtomicInteger hostCalls = new AtomicInteger();
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                if (modelCalls.incrementAndGet() == 1) {
                    events.accept(new LLMEvent.ToolCall("unknown-1", "unlisted", "{}"));
                    events.accept(new LLMEvent.ToolCall("query-1", "query", "{}"));
                    events.accept(new LLMEvent.Finish("tool-use"));
                } else {
                    List<MessagePart.ToolResultPart> results = request.sourceMessages().stream()
                        .flatMap(message -> message.parts().stream())
                        .filter(MessagePart.ToolResultPart.class::isInstance)
                        .map(MessagePart.ToolResultPart.class::cast).toList();
                    assertEquals(List.of("unknown-1", "query-1"), results.stream()
                        .map(MessagePart.ToolResultPart::callID).toList());
                    assertTrue(results.getFirst().isError());
                    assertFalse(results.getLast().isError());
                    events.accept(new LLMEvent.TextDelta("Done"));
                    events.accept(new LLMEvent.Finish("end-turn"));
                }
            }
            @Override public boolean supports(String providerId) { return true; }
            @Override public boolean requiresProviderProjection() { return false; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        List<AgentEvent> events = new ArrayList<>();
        RunLoop loop = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(), new PatchEngine(),
            events::add, model, ".", null, store, null);
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public List<ToolDef> getTools() { return List.of(advertisedHostTool("query")); }
            @Override public Optional<ToolBatchExecutor> getToolBatchExecutor() {
                return Optional.of((calls, early) -> {
                    hostCalls.incrementAndGet();
                    assertEquals(List.of("query-1"), calls.stream()
                        .map(MessagePart.ToolCallPart::callID).toList());
                    return List.of(new MessagePart.ToolResultPart("query-1", "query", "row", false));
                });
            }
        };
        Session session = new Session("host-denied", "denied", SessionStatus.IDLE,
            List.of(), "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());

        assertEquals("Done", loop.run(session, agent, "Query").getTextContent());
        assertEquals(1, hostCalls.get());
        assertEquals(List.of(0, 1), events.stream()
            .filter(AgentEvent.HostToolResultSettled.class::isInstance)
            .map(event -> ((AgentEvent.HostToolResultSettled) event).batchIndex()).toList());
    }

    @Test
    void rejectedFinishFeedsBackIntoTheSameRun() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                int call = calls.incrementAndGet();
                events.accept(new LLMEvent.TextStart("test-model"));
                events.accept(new LLMEvent.TextDelta(call == 1 ? "draft" : "verified"));
                events.accept(new LLMEvent.TextEnd());
                events.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String providerId) { return true; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        RunLoop loop = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), event -> {}, model, ".", null, store, null);
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public int getMaxSteps() { return 4; }
            @Override public RunPolicy getRunPolicy() {
                return (session, definition, candidate, history) ->
                    candidate.getTextContent().contains("verified")
                        ? RunPolicy.FinishDecision.accept()
                        : RunPolicy.FinishDecision.continueWith("Verify the result first");
            }
        };
        Session session = new Session("policy-session", "policy", SessionStatus.IDLE, List.of(),
            "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());

        Message result = loop.run(session, agent, "Please answer");

        assertEquals("verified", result.getTextContent());
        assertEquals(2, calls.get());
        assertTrue(store.getMessages(session.id()).stream()
            .anyMatch(message -> message.role() == Role.USER
                && message.getTextContent().contains("Verify the result first")));
    }

    @Test
    void rejectedFinishAtStepLimitReturnsFailure() {
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                events.accept(new LLMEvent.TextStart("test-model"));
                events.accept(new LLMEvent.TextDelta("unverified"));
                events.accept(new LLMEvent.TextEnd());
                events.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String providerId) { return true; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new ArrayList<>();
        RunLoop loop = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null);
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public int getMaxSteps() { return 1; }
            @Override public RunPolicy getRunPolicy() {
                return (session, definition, candidate, history) ->
                    RunPolicy.FinishDecision.continueWith("Still missing evidence");
            }
        };
        Session session = new Session("policy-limit", "policy", SessionStatus.IDLE, List.of(),
            "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());

        Message result = loop.run(session, agent, "Please answer");

        assertNotNull(result.error());
        assertEquals("completion-policy-rejected", result.finish());
        assertTrue(events.stream().anyMatch(event -> event instanceof AgentEvent.Finished finished
            && "completion-policy-rejected-max-steps".equals(finished.reason())));
    }

    @Test
    void acceptedFallbackTextIsPersistedAndReturned() {
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                events.accept(new LLMEvent.TextStart("test-model"));
                events.accept(new LLMEvent.TextEnd());
                events.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String providerId) { return true; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        RunLoop loop = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), event -> {}, model, ".", null, store, null);
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public RunPolicy getRunPolicy() {
                return (session, definition, candidate, history) ->
                    RunPolicy.FinishDecision.acceptWithTextIfEmpty("Evidence-backed summary");
            }
        };
        Session session = new Session("policy-fallback", "policy", SessionStatus.IDLE, List.of(),
            "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());

        Message result = loop.run(session, agent, "Summarize");

        assertEquals("Evidence-backed summary", result.getTextContent());
        assertEquals("Evidence-backed summary", store.getMessages(session.id()).stream()
            .filter(message -> message.role() == Role.ASSISTANT)
            .reduce((first, last) -> last).orElseThrow().getTextContent());
    }

    @Test
    void acceptedReplacementPartsArePersistedAndReturned() {
        LLMClient client = new LLMClient() {
            @Override public void stream(LlmRequest request, Consumer<LLMEvent> events) {
                events.accept(new LLMEvent.TextStart("test-model"));
                events.accept(new LLMEvent.TextDelta("Report: `report.md`"));
                events.accept(new LLMEvent.TextEnd());
                events.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override public boolean supports(String providerId) { return true; }
        };
        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        RunLoop loop = new RunLoop(client, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), event -> {}, model, ".", null, store, null);
        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "Test"; }
            @Override public RunPolicy getRunPolicy() {
                return (session, definition, candidate, history) ->
                    RunPolicy.FinishDecision.acceptWithParts(List.of(
                        new MessagePart.TextPart("Report: `sessions/s/report.md`")));
            }
        };
        Session session = new Session("policy-replacement", "policy", SessionStatus.IDLE, List.of(),
            "test", model, 0.0, null, Instant.now(), Instant.now(), List.of());

        Message result = loop.run(session, agent, "Summarize");

        assertEquals("Report: `sessions/s/report.md`", result.getTextContent());
        assertEquals(result.getTextContent(), store.getMessages(session.id()).stream()
            .filter(message -> message.role() == Role.ASSISTANT)
            .reduce((first, last) -> last).orElseThrow().getTextContent());
    }

    @Test
    void simpleLoop_exitsAfterEndTurn() {
        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                eventConsumer.accept(new LLMEvent.TextStart("test-model"));
                eventConsumer.accept(new LLMEvent.TextDelta("Hello!"));
                eventConsumer.accept(new LLMEvent.Finish("end-turn"));
                eventConsumer.accept(new LLMEvent.TextEnd());
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new ArrayList<>();

        RunLoop loop = new RunLoop(
            mockClient, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null
        );

        AgentDef agent = new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "You are test."; }
            @Override public List<ToolDef> getTools() { return List.of(); }
            @Override public int getMaxSteps() { return 10; }
        };

        Session session = new Session(
            "s1", null, SessionStatus.IDLE, List.of(),
            "test", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result = loop.run(session, agent, "Hello!");
        assertNotNull(result);
        assertEquals(Role.ASSISTANT, result.role());
    }
}
