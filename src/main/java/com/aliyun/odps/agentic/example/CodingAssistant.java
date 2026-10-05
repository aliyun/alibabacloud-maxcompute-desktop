package com.aliyun.odps.agentic.example;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.llm.provider.Anthropic;
import com.aliyun.odps.agentic.llm.provider.OpenAI;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.session.AgentEvent;

import java.nio.file.Path;
import java.util.List;

/**
 * Minimal example: a coding assistant agent using the Harness SDK.
 *
 * <p>Usage:
 * <pre>{@code
 * # With Anthropic
 * ANTHROPIC_API_KEY=sk-... java CodingAssistant "Refactor the main method"
 *
 * # With OpenAI
 * OPENAI_API_KEY=sk-... java CodingAssistant "Explain this code"
 * }</pre>
 */
public class CodingAssistant {

    public static void main(String[] args) {
        String userMessage = args.length > 0 ? args[0] : "Hello! What can you help me with?";

        // ── 1. Configure LLM provider via Provider facade ──
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        String openaiKey = System.getenv("OPENAI_API_KEY");
        String provider;
        Model model;

        if (anthropicKey != null && !anthropicKey.isBlank()) {
            provider = "anthropic";
            model = Anthropic.configure(anthropicKey)
                .model("claude-sonnet-4-20250514", new ModelLimit(200000, null, 16384));
        } else if (openaiKey != null && !openaiKey.isBlank()) {
            provider = "openai";
            model = OpenAI.configure(openaiKey)
                .model("gpt-4o", new ModelLimit(128000, null, 16384));
        } else {
            throw new IllegalStateException(
                "Set ANTHROPIC_API_KEY or OPENAI_API_KEY environment variable");
        }

        // ── 2. Create LLM client — no manual transform/key registration needed ──
        SseLlmClient llmClient = new SseLlmClient();

        // ── 3. Build the engine ──
        HarnessEngine engine = HarnessEngine.builder()
            .workDir(Path.of(System.getProperty("user.dir")))
            .llmClient(llmClient)
            .model(model)
            .build();

        // ── 4. Define the agent ──
        AgentDef agent = new CodingAgentDef();

        // ── 5. Create session and run with streaming ──
        Session session = engine.createSession(agent);

        System.out.println("🤖 Starting Coding Assistant...");
        System.out.println("📡 Provider: " + provider);
        System.out.println("📝 User: " + userMessage);
        System.out.println("---");

        Message result = engine.runStreaming(session, agent, userMessage, event -> {
            switch (event) {
                case AgentEvent.TextDelta td -> System.out.print(td.delta());
                case AgentEvent.ToolCallStarted tc ->
                    System.out.println("\n🔧 Calling: " + tc.tool() + "(" + tc.callId() + ")");
                case AgentEvent.ToolCallCompleted tc ->
                    System.out.println("  ✅ Done: " + tc.tool());
                case AgentEvent.StepStart ss ->
                    System.out.println("\n--- Step " + ss.step() + " ---");
                case AgentEvent.Finished f ->
                    System.out.println("\n\n🏁 Finished (" + f.reason() + ") ---");
                default -> {}
            }
        });

        System.out.println("\n✅ Final response: " + result.getTextContent());
    }

    /**
     * A simple coding assistant agent definition.
     */
    static class CodingAgentDef implements AgentDef {

        @Override
        public String getName() {
            return "coding-assistant";
        }

        @Override
        public String getSystemPrompt(java.util.function.Function<String, String> metaProvider) {
            return """
                You are an expert coding assistant. You help users write, review, and debug code.

                You have access to tools for reading, writing, and editing files, running shell
                commands, and searching code. Use them to accomplish the user's tasks.

                Guidelines:
                - Read files before editing them
                - Make targeted, minimal changes
                - Run tests to verify your changes
                - Explain what you're doing as you go
                """;
        }

        @Override
        public int getMaxSteps() {
            return 50;
        }
    }
}
