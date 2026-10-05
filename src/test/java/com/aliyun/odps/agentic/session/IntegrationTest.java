package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.agent.ModelConfig;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.*;
import com.aliyun.odps.agentic.patch.PatchEngine;
import com.aliyun.odps.agentic.permission.PermissionEngine;
import com.aliyun.odps.agentic.permission.Rule;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolRegistry;
import com.aliyun.odps.agentic.tool.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.aliyun.odps.agentic.permission.PermissionRequest;
import com.aliyun.odps.agentic.permission.PermissionReply;
import com.aliyun.odps.agentic.permission.PermissionService;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test — mock LLM → full loop → tool execution → response.
 * Tests the complete Harness agent lifecycle.
 */
class IntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Simple conversation with no tool calls.
     */
    @Test
    @Timeout(10)
    void simpleConversation_noTools() {
        // Mock LLM client
        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                eventConsumer.accept(new LLMEvent.TextStart("test-model"));
                eventConsumer.accept(new LLMEvent.TextDelta("Hello! Let me help you."));
                eventConsumer.accept(new LLMEvent.Finish("end-turn"));
                eventConsumer.accept(new LLMEvent.TextEnd());
            }

            @Override
            public boolean supports(String providerId) { return true; }
        };

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        ToolRegistry registry = new ToolRegistry();
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        RunLoop loop = new RunLoop(
            mockClient, registry, new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.");

        Session session = new Session(
            "s1", null, SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result = loop.run(session, agent, "Hello!");

        assertNotNull(result);
        assertEquals(Role.ASSISTANT, result.role());
        assertEquals("Hello! Let me help you.", result.getTextContent());
        assertEquals("end-turn", result.finish());

        // Verify events were emitted
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.StepStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.StepFinish));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.TextDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Finished));
    }

    /**
     * Conversation with a tool call, then a text response.
     */
    @Test
    @Timeout(10)
    void conversationWithToolCall() {
        int[] stepCounter = {0};

        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                stepCounter[0]++;
                if (stepCounter[0] == 1) {
                    eventConsumer.accept(new LLMEvent.TextDelta("Let me echo that."));
                    eventConsumer.accept(new LLMEvent.ToolCall("toolu_1", "echo"));
                    eventConsumer.accept(new LLMEvent.ToolInputDelta("{\"message\":\"hello\"}"));
                    eventConsumer.accept(new LLMEvent.Finish("tool-use"));
                } else {
                    eventConsumer.accept(new LLMEvent.TextDelta("The echo returned: hello"));
                    eventConsumer.accept(new LLMEvent.Finish("end-turn"));
                }
            }

            @Override
            public boolean supports(String providerId) { return true; }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        RunLoop loop = new RunLoop(
            mockClient, registry, new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.",
            List.of(new EchoTool()));

        Session session = new Session(
            "s1", "Tool call test", SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result = loop.run(session, agent, "Echo hello");

        assertNotNull(result);
        assertEquals(Role.ASSISTANT, result.role());
        assertEquals("The echo returned: hello", result.getTextContent());
        assertEquals("end-turn", result.finish());
        assertEquals(2, stepCounter[0]);
    }

    /**
     * Test max steps limit prevents infinite loops.
     */
    @Test
    @Timeout(10)
    void maxStepsLimit() {
        LLMClient infiniteClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                eventConsumer.accept(new LLMEvent.ToolCall("toolu_loop", "echo"));
                eventConsumer.accept(new LLMEvent.ToolInputDelta("{\"message\":\"loop\"}"));
                eventConsumer.accept(new LLMEvent.Finish("tool-use"));
            }

            @Override
            public boolean supports(String providerId) { return true; }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        RunLoop loop = new RunLoop(
            infiniteClient, registry, new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.",
            List.of(new EchoTool()), 3); // max 3 steps

        Session session = new Session(
            "s1", null, SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result = loop.run(session, agent, "Start infinite loop");

        assertNotNull(result);
        AgentEvent.Finished finished = events.stream()
            .filter(e -> e instanceof AgentEvent.Finished)
            .map(e -> (AgentEvent.Finished) e)
            .findFirst()
            .orElse(null);
        assertNotNull(finished);
        assertEquals("max-steps", finished.reason());
    }

    // ── Multi-tool conversation ──

    /**
     * LLM calls two different tools in successive steps, then gives a text response.
     * Each step calls one tool. Verifies both tools execute and the loop converges.
     */
    @Test
    @Timeout(10)
    void multiToolConversation() {
        int[] stepCounter = {0};

        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                stepCounter[0]++;
                int step = stepCounter[0];
                if (step == 1) {
                    eventConsumer.accept(new LLMEvent.TextDelta("Calling echo."));
                    eventConsumer.accept(new LLMEvent.ToolCall("toolu_1", "echo", "{\"message\":\"first\"}"));
                    eventConsumer.accept(new LLMEvent.Finish("tool-use"));
                } else if (step == 2) {
                    eventConsumer.accept(new LLMEvent.TextDelta("Calling reverse."));
                    eventConsumer.accept(new LLMEvent.ToolCall("toolu_2", "reverse", "{\"message\":\"hello\"}"));
                    eventConsumer.accept(new LLMEvent.Finish("tool-use"));
                } else {
                    eventConsumer.accept(new LLMEvent.TextDelta("Done: first + olleh"));
                    eventConsumer.accept(new LLMEvent.Finish("end-turn"));
                }
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());
        registry.register(new ReverseTool());

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        RunLoop loop = new RunLoop(
            mockClient, registry, new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.",
            List.of(new EchoTool(), new ReverseTool()));

        Session session = new Session(
            "s1", "Multi-tool test", SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result = loop.run(session, agent, "Use both tools");

        assertNotNull(result);
        assertEquals("Done: first + olleh", result.getTextContent());
        assertEquals(3, stepCounter[0]);

        long toolCompletedCount = events.stream()
            .filter(e -> e instanceof AgentEvent.ToolCallCompleted)
            .count();
        assertTrue(toolCompletedCount >= 2, "At least two tool calls should have completed");
    }

    // ── Permission denied tool call ──

    /**
     * Tool call is denied by permission rules — loop continues with error result.
     */
    @Test
    @Timeout(10)
    void permissionDeniedToolCall() {
        int[] stepCounter = {0};

        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                stepCounter[0]++;
                if (stepCounter[0] == 1) {
                    eventConsumer.accept(new LLMEvent.ToolCall("toolu_1", "guarded"));
                    eventConsumer.accept(new LLMEvent.ToolInputDelta("{\"action\":\"delete\"}"));
                    eventConsumer.accept(new LLMEvent.Finish("tool-use"));
                } else {
                    eventConsumer.accept(new LLMEvent.TextDelta("Permission was denied, moving on."));
                    eventConsumer.accept(new LLMEvent.Finish("end-turn"));
                }
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new GuardedTool());

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        PermissionEngine engine = new PermissionEngine(List.of(Rule.deny("danger")));
        PermissionService permService = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.REJECT));

        RunLoop loop = new RunLoop(
            mockClient, registry, new CompactionEngine(),
            engine, new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), permService
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.",
            List.of(new GuardedTool()));

        Session session = new Session(
            "s1", "Permission test", SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result = loop.run(session, agent, "Do the dangerous thing");

        assertNotNull(result);
        assertEquals("Permission was denied, moving on.", result.getTextContent());
        assertEquals(2, stepCounter[0]);
    }

    // ── Conversation with thinking blocks ──

    /**
     * LLM emits reasoning/thinking blocks before text response.
     */
    @Test
    @Timeout(10)
    void conversationWithThinkingBlocks() {
        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                eventConsumer.accept(new LLMEvent.ReasoningStart("think-1"));
                eventConsumer.accept(new LLMEvent.ReasoningDelta("Let me think about this..."));
                eventConsumer.accept(new LLMEvent.ReasoningEnd("think-1", null));
                eventConsumer.accept(new LLMEvent.TextStart("test-model"));
                eventConsumer.accept(new LLMEvent.TextDelta("Here is the answer."));
                eventConsumer.accept(new LLMEvent.Finish("end-turn"));
                eventConsumer.accept(new LLMEvent.TextEnd());
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        RunLoop loop = new RunLoop(
            mockClient, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.");
        Session session = new Session(
            "s1", null, SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result = loop.run(session, agent, "Think and answer.");

        assertNotNull(result);
        assertEquals("Here is the answer.", result.getTextContent());

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReasoningStart));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReasoningDelta));
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ReasoningEnd));
    }

    // ── Multi-turn conversation ──

    /**
     * Run the loop twice on the same session, verifying message history accumulates.
     */
    @Test
    @Timeout(10)
    void multiTurnConversation() {
        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                int msgCount = request.messages().size();
                String response = "Reply to " + msgCount + " messages";
                eventConsumer.accept(new LLMEvent.TextDelta(response));
                eventConsumer.accept(new LLMEvent.Finish("end-turn"));
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        InMemoryMessageStore store = new InMemoryMessageStore();
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        RunLoop loop = new RunLoop(
            mockClient, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            store, null
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.");
        Session session = new Session(
            "s1", null, SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result1 = loop.run(session, agent, "Turn 1");
        assertNotNull(result1);
        assertEquals(Role.ASSISTANT, result1.role());

        int messagesAfterTurn1 = store.getMessages("s1").size();
        assertTrue(messagesAfterTurn1 >= 2, "After turn 1: at least 2 messages (user + assistant)");

        RunLoop loop2 = new RunLoop(
            mockClient, new ToolRegistry(), new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            store, null
        );

        Session session2 = session.withMessages(store.getMessages("s1"));
        Message result2 = loop2.run(session2, agent, "Turn 2");
        assertNotNull(result2);
        assertEquals(Role.ASSISTANT, result2.role());

        int messagesAfterTurn2 = store.getMessages("s1").size();
        assertTrue(messagesAfterTurn2 > messagesAfterTurn1,
            "After turn 2: more messages than after turn 1");
    }

    // ── Cancel during execution ──

    /**
     * Cancel the loop from another thread during tool execution.
     */
    @Test
    @Timeout(10)
    void cancelDuringExecution() throws Exception {
        CountDownLatch toolStarted = new CountDownLatch(1);

        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                eventConsumer.accept(new LLMEvent.ToolCall("toolu_1", "slow"));
                eventConsumer.accept(new LLMEvent.ToolInputDelta("{}"));
                eventConsumer.accept(new LLMEvent.Finish("tool-use"));
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };

        ToolDef slowTool = new ToolDef() {
            @Override public String getId() { return "slow"; }
            @Override public String getDescription() { return "Slow tool"; }
            @Override public ObjectNode getParametersSchema() { return MAPPER.createObjectNode(); }
            @Override public ToolResult execute(JsonNode args, ToolContext context) {
                toolStarted.countDown();
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return ToolResult.of("done");
            }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(slowTool);

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        RunLoop loop = new RunLoop(
            mockClient, registry, new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.",
            List.of(slowTool), 5);

        Session session = new Session(
            "s1", null, SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        CompletableFuture<Message> future = CompletableFuture.supplyAsync(
            () -> loop.run(session, agent, "Run slow tool"));

        assertTrue(toolStarted.await(3, TimeUnit.SECONDS));
        loop.cancel();

        Message result = future.get(5, TimeUnit.SECONDS);
        assertNotNull(result);

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.SessionAborted),
            "Should have emitted SessionAborted event");
    }

    // ── Empty tool call args ──

    /**
     * Tool receives empty args — verifies graceful handling.
     */
    @Test
    @Timeout(10)
    void emptyToolCallArgs() {
        int[] stepCounter = {0};

        LLMClient mockClient = new LLMClient() {
            @Override
            public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
                stepCounter[0]++;
                if (stepCounter[0] == 1) {
                    eventConsumer.accept(new LLMEvent.ToolCall("toolu_1", "echo"));
                    eventConsumer.accept(new LLMEvent.ToolInputDelta("{}"));
                    eventConsumer.accept(new LLMEvent.Finish("tool-use"));
                } else {
                    eventConsumer.accept(new LLMEvent.TextDelta("Got: (no message)"));
                    eventConsumer.accept(new LLMEvent.Finish("end-turn"));
                }
            }
            @Override
            public boolean supports(String providerId) { return true; }
        };

        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        Model model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        RunLoop loop = new RunLoop(
            mockClient, registry, new CompactionEngine(),
            new PermissionEngine(List.of()), new SystemPromptBuilder(),
            new PatchEngine(), events::add, model, ".", null,
            new InMemoryMessageStore(), null
        );

        AgentDef agent = new SimpleAgentDef("test-agent", "You are a test agent.",
            List.of(new EchoTool()));

        Session session = new Session(
            "s1", null, SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        Message result = loop.run(session, agent, "Echo with no args");

        assertNotNull(result);
        assertEquals("Got: (no message)", result.getTextContent());
        assertEquals("end-turn", result.finish());
    }

    // ── Test helpers ──

    static class SimpleAgentDef implements AgentDef {
        private final String name;
        private final String systemPrompt;
        private final List<ToolDef> tools;
        private final int maxSteps;

        SimpleAgentDef(String name, String systemPrompt) {
            this(name, systemPrompt, List.of(), 200);
        }

        SimpleAgentDef(String name, String systemPrompt, List<ToolDef> tools) {
            this(name, systemPrompt, tools, 200);
        }

        SimpleAgentDef(String name, String systemPrompt, List<ToolDef> tools, int maxSteps) {
            this.name = name;
            this.systemPrompt = systemPrompt;
            this.tools = tools;
            this.maxSteps = maxSteps;
        }

        @Override public String getName() { return name; }

        @Override
        public String getSystemPrompt(Function<String, String> modelProvider) {
            return systemPrompt;
        }

        @Override public List<ToolDef> getTools() { return tools; }
        @Override public int getMaxSteps() { return maxSteps; }
    }

    static class EchoTool implements ToolDef {
        @Override public String getId() { return "echo"; }
        @Override public String getDescription() { return "Echoes back the input message."; }

        @Override
        public ObjectNode getParametersSchema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = MAPPER.createObjectNode();
            props.putObject("message").put("type", "string").put("description", "Message to echo");
            schema.set("properties", props);
            schema.put("required", MAPPER.createArrayNode().add("message"));
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext context) {
            String message = args.path("message").asText("(no message)");
            return ToolResult.of("Echo: " + message, message);
        }
    }

    static class ReverseTool implements ToolDef {
        @Override public String getId() { return "reverse"; }
        @Override public String getDescription() { return "Reverses the input message."; }

        @Override
        public ObjectNode getParametersSchema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = MAPPER.createObjectNode();
            props.putObject("message").put("type", "string");
            schema.set("properties", props);
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext context) {
            String message = args.path("message").asText("");
            String reversed = new StringBuilder(message).reverse().toString();
            return ToolResult.of("Reversed: " + reversed, reversed);
        }
    }

    static class GuardedTool implements ToolDef {
        @Override public String getId() { return "guarded"; }
        @Override public String getDescription() { return "A tool that requires permission."; }

        @Override
        public ObjectNode getParametersSchema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = MAPPER.createObjectNode();
            props.putObject("action").put("type", "string");
            schema.set("properties", props);
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext context) {
            if (context.permissionAsker() != null) {
                boolean allowed = context.permissionAsker().ask("danger", "destructive-op", "Dangerous operation");
                if (!allowed) {
                    return ToolResult.error("Permission denied");
                }
            }
            return ToolResult.of("Action performed");
        }
    }
}
