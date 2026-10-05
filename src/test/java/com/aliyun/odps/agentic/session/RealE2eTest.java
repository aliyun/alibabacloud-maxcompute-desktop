package com.aliyun.odps.agentic.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.agent.AgentDefBuilder;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.llm.SseLlmClient;
import com.aliyun.odps.agentic.llm.provider.OpenAI;
import com.aliyun.odps.agentic.llm.provider.ProviderInstance;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.permission.Rule;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real end-to-end integration tests — hits the OpenAI API with a real API key.
 * Skipped automatically when OPENAI_API_KEY is not set.
 *
 * <p>Run with: {@code OPENAI_API_KEY=sk-xxx mvn test -Dtest=RealE2eTest}
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class RealE2eTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HarnessEngine engine;
    private static Model model;

    @BeforeAll
    static void setup() {
        ProviderInstance pi = OpenAI.configure(null);
        String modelName = System.getenv().getOrDefault("E2E_TEST_MODEL", "qwen3-coder-plus");
        model = pi.model(modelName, new ModelLimit(128000, null, 4096));
        engine = HarnessEngine.builder()
            .llmClient(new SseLlmClient())
            .model(model)
            .build();
    }

    // -- 1. Simple conversation --

    @Test
    @Timeout(30)
    void simpleConversation() {
        AgentDef agent = AgentDefBuilder.create("test-agent")
            .systemPrompt("You are a helpful assistant. Be concise.")
            .build();

        Session session = engine.createSession(agent);
        Message result = engine.run(session, agent, "What is 2+2? Reply with just the number.");

        assertNotNull(result);
        assertEquals(Role.ASSISTANT, result.role());
        assertNotNull(result.getTextContent());
        assertTrue(result.getTextContent().contains("4"),
            "Response should contain '4', got: " + result.getTextContent());
    }

    // -- 2. Conversation with tool call --

    @Test
    @Timeout(30)
    void conversationWithToolCall() {
        CalculatorTool calc = new CalculatorTool();

        AgentDef agent = AgentDefBuilder.create("tool-agent")
            .systemPrompt("You are a calculator assistant. Use the calculator tool to compute results. Be concise.")
            .addTool(calc)
            .build();

        engine.getToolRegistry().register(calc);
        try {
            Session session = engine.createSession(agent);
            Message result = engine.run(session, agent, "Use the calculator tool to add 17 and 25.");

            assertNotNull(result);
            assertEquals(Role.ASSISTANT, result.role());
            assertTrue(calc.wasCalled, "Calculator tool should have been called");
            assertTrue(result.getTextContent().contains("42"),
                "Response should contain '42', got: " + result.getTextContent());
        } finally {
            engine.getToolRegistry().unregister("calculator");
        }
    }

    // -- 3. Streaming events --

    @Test
    @Timeout(30)
    void streamingEvents() {
        AgentDef agent = AgentDefBuilder.create("stream-agent")
            .systemPrompt("You are a helpful assistant. Be concise.")
            .build();

        Session session = engine.createSession(agent);
        List<AgentEvent> events = new ArrayList<>();
        Message result = engine.runStreaming(session, agent, "Say hello in one word.", events::add);

        assertNotNull(result);
        assertEquals(Role.ASSISTANT, result.role());

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.TextDelta),
            "Should have received TextDelta events");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.StepStart),
            "Should have received StepStart event");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.Finished),
            "Should have received Finished event");
    }

    // -- 4. Multi-turn conversation --

    @Test
    @Timeout(60)
    void multiTurnConversation() {
        AgentDef agent = AgentDefBuilder.create("multi-turn-agent")
            .systemPrompt("You are a helpful assistant. Be concise. Remember context from previous messages.")
            .build();

        Session session = engine.createSession(agent);
        Message result1 = engine.run(session, agent, "My favorite color is blue. Just acknowledge.");

        assertNotNull(result1);
        assertEquals(Role.ASSISTANT, result1.role());

        Session session2 = new Session(
            session.id(), session.title(), session.status(),
            engine.getMessages(session.id()),
            session.agent(), session.model(), session.cost(), session.tokens(),
            session.createdAt(), session.updatedAt(), session.permission()
        );
        Message result2 = engine.run(session2, agent, "What is my favorite color?");

        assertNotNull(result2);
        String text = result2.getTextContent().toLowerCase();
        assertTrue(text.contains("blue"),
            "Second turn should remember 'blue', got: " + result2.getTextContent());
    }

    // -- 5. Max steps limit --

    @Test
    @Timeout(30)
    void maxStepsLimit() {
        CalculatorTool calc = new CalculatorTool();

        AgentDef agent = AgentDefBuilder.create("limited-agent")
            .systemPrompt("You must always use the calculator tool before answering any math question.")
            .addTool(calc)
            .maxSteps(1)
            .build();

        engine.getToolRegistry().register(calc);
        try {
            Session session = engine.createSession(agent);
            HarnessEngine.RunResult runResult = engine.runWithEvents(session, agent, "What is 3+5?");

            assertNotNull(runResult.finalMessage());

            boolean hasFinished = runResult.events().stream()
                .anyMatch(e -> e instanceof AgentEvent.Finished);
            assertTrue(hasFinished, "Should have emitted Finished event");
        } finally {
            engine.getToolRegistry().unregister("calculator");
        }
    }

    // -- 6. Tool permission denied --

    @Test
    @Timeout(30)
    void toolPermissionDenied() {
        CalculatorTool calc = new CalculatorTool();

        AgentDef agent = AgentDefBuilder.create("denied-agent")
            .systemPrompt("You are a calculator assistant. Try to use the calculator tool. If denied, explain that you cannot use it.")
            .addTool(calc)
            .addPermissionRule(Rule.deny("calculator"))
            .build();

        HarnessEngine deniedEngine = HarnessEngine.builder()
            .llmClient(new SseLlmClient())
            .model(model)
            .permissionRules(List.of(Rule.deny("calculator")))
            .build();
        deniedEngine.getToolRegistry().register(calc);

        try {
            Session session = deniedEngine.createSession(agent);
            Message result = deniedEngine.run(session, agent, "Use the calculator to add 1+1.");

            assertNotNull(result);
            assertEquals(Role.ASSISTANT, result.role());
            assertFalse(result.getTextContent().isEmpty(), "Should still produce a text response");
        } finally {
            deniedEngine.shutdown();
        }
    }

    // -- Test helpers --

    static class CalculatorTool implements ToolDef {
        volatile boolean wasCalled = false;

        @Override public String getId() { return "calculator"; }
        @Override public String getDescription() { return "Adds two numbers together. Returns the sum."; }

        @Override
        public ObjectNode getParametersSchema() {
            ObjectNode schema = MAPPER.createObjectNode();
            schema.put("type", "object");
            ObjectNode props = MAPPER.createObjectNode();
            props.putObject("a").put("type", "number").put("description", "First number");
            props.putObject("b").put("type", "number").put("description", "Second number");
            schema.set("properties", props);
            schema.set("required", MAPPER.createArrayNode().add("a").add("b"));
            return schema;
        }

        @Override
        public ToolResult execute(JsonNode args, ToolContext context) {
            wasCalled = true;
            double a = args.path("a").asDouble(0);
            double b = args.path("b").asDouble(0);
            double sum = a + b;
            String result = String.valueOf((int) sum);
            return ToolResult.of("Sum: " + result, result);
        }
    }
}
