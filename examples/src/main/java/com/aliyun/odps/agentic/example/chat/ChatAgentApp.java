package com.aliyun.odps.agentic.example.chat;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.agent.AgentDefBuilder;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.llm.provider.Anthropic;
import com.aliyun.odps.agentic.llm.provider.OpenAI;
import com.aliyun.odps.agentic.llm.provider.OpenAICompatible;
import com.aliyun.odps.agentic.llm.provider.ProviderInstance;
import com.aliyun.odps.agentic.permission.*;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Spring Boot application that serves a web-based chat UI
 * backed by Agentic SDK.
 *
 * <p>Usage:
 * <pre>{@code
 * ANTHROPIC_API_KEY=sk-... mvn spring-boot:run
 * # or
 * OPENAI_API_KEY=sk-... AGENT_PROVIDER=openai AGENT_MODEL=your-model-id mvn spring-boot:run
 * }</pre>
 *
 * Then open http://localhost:8080 in your browser.
 */
@SpringBootApplication
public class ChatAgentApp {

    public static void main(String[] args) {
        SpringApplication.run(ChatAgentApp.class, args);
    }

    @Bean
    public HarnessEngine harnessEngine() {
        String providerName = env("AGENT_PROVIDER", env("OPENCODE_PROVIDER", "openai"));
        String modelId = env("AGENT_MODEL", env("OPENCODE_MODEL", null));

        if (modelId == null) throw new IllegalStateException("Set AGENT_MODEL to a model available on your provider");
        // SDK-owned settings take precedence; keep legacy aliases for existing examples.
        String apiKey = env("AGENT_API_KEY", env("OPENCODE_API_KEY", null));
        if (apiKey == null) {
            apiKey = resolveApiKey(providerName);
        }

        // Custom API URL
        String apiUrl = env("AGENT_API_URL", env("OPENCODE_API_URL", null));

        // Configure the selected provider route and model.
        ProviderInstance provider = resolveProvider(providerName, apiKey, apiUrl);

        // Build model via Route
        ModelLimit limit = "anthropic".equals(providerName)
            ? new ModelLimit(200000, null, 16384)
            : new ModelLimit(128000, null, 16384);
        Model model = provider.model(modelId, limit);

        // LLM client — no more manual transform/key registration needed
        SseLlmClient llmClient = new SseLlmClient();

        // Discover skill paths from environment or defaults
        List<Path> skillPaths = new ArrayList<>();
        String extraSkillPaths = env("AGENT_SKILL_PATHS", env("OPENCODE_SKILL_PATHS", null));
        if (extraSkillPaths != null) {
            for (String p : extraSkillPaths.split(":")) {
                if (!p.isBlank()) skillPaths.add(Path.of(p));
            }
        }

        // Web-mode AsyncPermissionAsker: returns a never-completing future.
        // The PermissionService stores the pending request; the HTTP API
        // (POST /api/permissions/{id}/reply) calls reply() to resume.
        AsyncPermissionAsker webAsker = request ->  new CompletableFuture<>();

        // Permission rules — shell commands default to ASK
        List<Rule> permissionRules = List.of(
            Rule.ask("bash"),
            Rule.ask("shell"),
            Rule.allow("file", "**"),
            Rule.allow("glob"),
            Rule.allow("grep")
        );

        return HarnessEngine.builder()
            .workDir(Path.of(System.getProperty("user.dir")))
            .llmClient(llmClient)
            .model(model)
            .skillPaths(skillPaths)
            .asyncPermissionAsker(webAsker)
            .permissionRules(permissionRules)
            .build();
    }

    @Bean
    public AgentDef chatAgent() {
        return new AgentDefBuilder()
            .name("coding-assistant")
            .description("An expert coding assistant with full tool access")
            .systemPrompt("""
                You are an expert coding assistant. You help users write, review, \
                debug, and understand code.

                You have access to tools for reading, writing, and editing files, \
                running shell commands, searching code with glob and grep, and \
                fetching web content. Use them to accomplish the user's tasks.

                Guidelines:
                - Read files before editing them
                - Make targeted, minimal changes
                - Run tests to verify your changes when appropriate
                - Explain what you are doing as you go
                - When showing code, use markdown code blocks with the language specified
                - Be concise but thorough in your explanations
                """)
            .maxSteps(50)
            .build();
    }

    /**
     * Resolve the provider protocol by its configured name.
     */
    private static ProviderInstance resolveProvider(String name, String apiKey, String baseUrl) {
        return switch (name) {
            case "anthropic" -> Anthropic.configure(apiKey, baseUrl);
            case "openai" -> OpenAI.configure(apiKey, baseUrl);
            case "deepseek" -> OpenAICompatible.deepseek(apiKey);
            default -> OpenAICompatible.configure(name, baseUrl, apiKey);
        };
    }

    private static String resolveApiKey(String provider) {
        String key = null;
        for (String envVar : new String[]{
            "OPENCODE_API_KEY",
            "OPENAI_KEY",
            "OPENAI_API_KEY",
            "ANTHROPIC_API_KEY"
        }) {
            key = System.getenv(envVar);
            if (key != null && !key.isBlank()) break;
        }
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "No API key found. Set ANTHROPIC_API_KEY, OPENAI_API_KEY, or OPENAI_KEY environment variable.");
        }
        return key;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        return (value != null && !value.isBlank()) ? value : defaultValue;
    }
}
