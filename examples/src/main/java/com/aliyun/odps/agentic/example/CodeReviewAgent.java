package com.aliyun.odps.agentic.example;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.llm.provider.Anthropic;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.permission.Action;
import com.aliyun.odps.agentic.permission.Rule;
import com.aliyun.odps.agentic.session.AgentEvent;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Example: A read-only code review agent that can read and search code
 * but cannot write or execute shell commands.
 *
 * <p>This demonstrates the permission system — the agent has access to
 * reading tools but is denied destructive operations.
 */
public class CodeReviewAgent {

    public static void main(String[] args) {
        String filePath = args.length > 0 ? args[0] : "src/main/java/";

        // ── 1. Configure LLM via Provider facade ──
        String apiKey = System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set ANTHROPIC_API_KEY environment variable");
        }

        SseLlmClient llmClient = new SseLlmClient();
        Model model = Anthropic.configure(apiKey)
            .model("claude-sonnet-4-20250514", new ModelLimit(200000, null, 16384));

        // ── 2. Build engine ──
        HarnessEngine engine = HarnessEngine.builder()
            .workDir(Path.of(System.getProperty("user.dir")))
            .llmClient(llmClient)
            .model(model)
            .build();

        // ── 3. Define read-only review agent ──
        AgentDef agent = new ReviewAgentDef();

        // ── 4. Run review ──
        Session session = engine.createSession(agent);
        String prompt = "Review the code in " + filePath + ". "
            + "Look for bugs, style issues, and improvement opportunities. "
            + "Be thorough but constructive.";

        System.out.println("🔍 Code Review Agent starting...");
        System.out.println("📁 Target: " + filePath);

        Message result = engine.runStreaming(session, agent, prompt, event -> {
            switch (event) {
                case AgentEvent.TextDelta td -> System.out.print(td.delta());
                case AgentEvent.ToolCallStarted tc ->
                    System.out.println("\n📖 Reading: " + tc.tool());
                case AgentEvent.StepStart ss ->
                    System.out.println("\n--- Step " + ss.step() + " ---");
                case AgentEvent.Finished f ->
                    System.out.println("\n\n✅ Review complete (" + f.reason() + ")");
                default -> {}
            }
        });
    }

    /**
     * Read-only code review agent with strict permissions.
     */
    static class ReviewAgentDef implements AgentDef {

        @Override
        public String getName() { return "code-reviewer"; }

        @Override
        public String getSystemPrompt(Function<String, String> metaProvider) {
            return """
                You are a senior code reviewer. You review code for:

                1. **Bugs**: Logic errors, null pointer risks, resource leaks
                2. **Security**: Injection, XSS, insecure defaults
                3. **Performance**: Unnecessary allocations, O(n²) algorithms
                4. **Style**: Naming, structure, documentation
                5. **Maintainability**: Coupling, testability, extensibility

                You have read-only access. Read files, search code, but never modify.
                Provide specific line references and concrete suggestions.
                """;
        }

        @Override
        public Set<String> getIncludedTools() { return Set.of("read", "glob", "grep"); }

        @Override
        public int getMaxSteps() { return 20; }

        @Override
        public List<Rule> getPermissionRules() {
            return List.of(
                Rule.allow("file"),      // Allow file read operations
                Rule.deny("bash"),       // Deny shell commands
                Rule.deny("mcp"),        // Deny MCP operations
                Rule.ask("web")          // Ask before web access
            );
        }
    }
}
