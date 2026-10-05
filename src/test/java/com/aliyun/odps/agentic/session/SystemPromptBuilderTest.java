package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.skill.SkillInfo;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SystemPromptBuilder tests — verifies prompt assembly and claims tracking.
 */
class SystemPromptBuilderTest {

    private SystemPromptBuilder builder;
    private Model model;
    private AgentDef testAgent;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        builder = new SystemPromptBuilder();
        Route route = new Route("anthropic", new AnthropicTransform(), "http://localhost",
            Auth.none, MessageFormat.ANTHROPIC);
        model = route.model("claude-sonnet-4-20250514", new ModelLimit(200000, null, 16384));
        testAgent = new AgentDef() {
            @Override public String getName() { return "test-agent"; }
            @Override public String getDescription() { return "Test agent for unit tests"; }
        };
    }

    // ── Build prompt ──

    @Test
    void buildReturnsNonNullPrompt() {
        String prompt = builder.build(testAgent, model, tempDir.toString());
        assertNotNull(prompt);
        assertFalse(prompt.isBlank());
    }

    @Test
    void buildContainsEnvironmentSection() {
        String prompt = builder.build(testAgent, model, tempDir.toString());
        assertTrue(prompt.contains("<env>"));
        assertTrue(prompt.contains("</env>"));
        assertTrue(prompt.contains("claude-sonnet-4-20250514"));
    }

    @Test
    void buildContainsWorkingDirectory() {
        String prompt = builder.build(testAgent, model, tempDir.toString());
        assertTrue(prompt.contains("<working_directory>"));
        assertTrue(prompt.contains(tempDir.toString()));
    }

    @Test
    void buildContainsDate() {
        String prompt = builder.build(testAgent, model, tempDir.toString());
        // Date is formatted as yyyy/MM/dd
        assertTrue(prompt.contains("2026/") || prompt.contains("2025/") || prompt.contains("2027/"),
            "Prompt should contain a year in yyyy/MM/dd format");
    }

    @Test
    void buildWithSkillsIncludesSkillSection() {
        SkillInfo skill = new SkillInfo("test-skill", "A test skill", null, null);
        String prompt = builder.build(model, testAgent, List.of(skill), tempDir.toString());
        assertTrue(prompt.contains("test-skill"));
        assertTrue(prompt.contains("A test skill"));
    }

    @Test
    void buildWithoutSkillsOmitsSkillSection() {
        String prompt = builder.build(model, testAgent, List.of(), tempDir.toString());
        assertFalse(prompt.contains("<skill"));
    }

    // ── Provider prompt resolution ──

    @Test
    void resolveProviderPromptClaude() {
        String prompt = builder.resolveProviderPrompt("claude-sonnet-4-20250514");
        assertNotNull(prompt);
    }

    @Test
    void resolveProviderPromptGPT4() {
        String prompt = builder.resolveProviderPrompt("gpt-4o");
        assertNotNull(prompt);
    }

    @Test
    void resolveProviderPromptNullReturnsDefault() {
        String prompt = builder.resolveProviderPrompt(null);
        assertNotNull(prompt);
    }

    @Test
    void resolveProviderPromptEmptyReturnsDefault() {
        String prompt = builder.resolveProviderPrompt("");
        assertNotNull(prompt);
    }

    @Test
    void resolveProviderPromptGemini() {
        String prompt = builder.resolveProviderPrompt("gemini-2.5-pro");
        assertNotNull(prompt);
    }

    @Test
    void resolveProviderPromptUnknownReturnsDefault() {
        String prompt = builder.resolveProviderPrompt("some-unknown-model");
        assertNotNull(prompt);
    }

    @Test
    void resolveProviderPromptIsModelIndependent() {
        String prompt = builder.resolveProviderPrompt("gpt-6");
        assertEquals(prompt, builder.resolveProviderPrompt("gpt-5"));
        assertEquals(prompt, builder.resolveProviderPrompt("claude-sonnet-4-20250514"));
        assertEquals(prompt, builder.resolveProviderPrompt(null));
        assertFalse(prompt.contains("opencode"));
    }

    @Test
    void modelNamesUseTheSameNeutralPrompt() {
        String expected = builder.resolveProviderPrompt(null);
        assertFalse(expected.isBlank());
        for (String modelId : List.of("", "gpt-4o", "gpt-codex", "claude-example",
                "gemini-example", "kimi-example", "trinity-example", "custom-model")) {
            assertEquals(expected, builder.resolveProviderPrompt(modelId), modelId);
        }
        assertFalse(expected.toLowerCase().contains("opencode"));
        assertTrue(expected.contains("permissions"));
        assertTrue(expected.contains("observed tool results"));
    }

    @Test
    void exactHostPromptRemainsVerbatim() {
        AgentDef exactAgent = new AgentDef() {
            @Override public String getName() { return "host-agent"; }
            @Override public java.util.Optional<String> getExactSystemPrompt() {
                return java.util.Optional.of("Host instructions with their own envelope");
            }
        };
        assertEquals("Host instructions with their own envelope",
            builder.build(exactAgent, model, tempDir.toString()));
    }

    // ── Claims tracking ──

    @Test
    void claimsInitiallyNotClaimed() {
        assertFalse(builder.isClaimed("msg1", "AGENTS.md"));
    }

    @Test
    void claimThenIsClaimed() {
        builder.claim("msg1", "AGENTS.md");
        assertTrue(builder.isClaimed("msg1", "AGENTS.md"));
    }

    @Test
    void differentMessageNotClaimed() {
        builder.claim("msg1", "AGENTS.md");
        assertFalse(builder.isClaimed("msg2", "AGENTS.md"));
    }

    @Test
    void differentFileNotClaimed() {
        builder.claim("msg1", "AGENTS.md");
        assertFalse(builder.isClaimed("msg1", "CLAUDE.md"));
    }

    @Test
    void clearClaimsRemovesAll() {
        builder.claim("msg1", "AGENTS.md");
        builder.claim("msg1", "CONTEXT.md");
        builder.clearClaims("msg1");
        assertFalse(builder.isClaimed("msg1", "AGENTS.md"));
        assertFalse(builder.isClaimed("msg1", "CONTEXT.md"));
    }

    @Test
    void clearClaimsDoesNotAffectOtherMessages() {
        builder.claim("msg1", "AGENTS.md");
        builder.claim("msg2", "AGENTS.md");
        builder.clearClaims("msg1");
        assertFalse(builder.isClaimed("msg1", "AGENTS.md"));
        assertTrue(builder.isClaimed("msg2", "AGENTS.md"));
    }

    // ── Instruction files ──

    @Test
    void buildWithExistingInstructionFile() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("AGENTS.md"), "# Test Instructions\nBe helpful.");
        String prompt = builder.build(testAgent, model, tempDir.toString());
        assertTrue(prompt.contains("<instruction_file"));
        assertTrue(prompt.contains("AGENTS.md"));
        assertTrue(prompt.contains("Be helpful"));
    }

    @Test
    void buildWithAbsoluteInstructionFilePath() throws Exception {
        Path instructionFile = tempDir.resolve("custom-instructions.md");
        java.nio.file.Files.writeString(instructionFile, "# Absolute Instructions\nUse absolute path.");
        AgentDef agentWithAbsoluteInstructionFile = new AgentDef() {
            @Override public String getName() { return "absolute-agent"; }
            @Override public List<String> getInstructionFiles() { return List.of(instructionFile.toString()); }
        };

        String prompt = builder.build(agentWithAbsoluteInstructionFile, model, tempDir.resolve("workspace").toString());

        assertTrue(prompt.contains("Absolute Instructions"));
        assertTrue(prompt.contains(instructionFile.toString()));
    }

    @Test
    void buildIgnoresGlobalClaudeInstructions() throws Exception {
        String originalHome = System.getProperty("user.home");
        Path fakeHome = tempDir.resolve("fake-home");
        Path globalClaudeMd = fakeHome.resolve(".claude").resolve("CLAUDE.md");
        java.nio.file.Files.createDirectories(globalClaudeMd.getParent());
        java.nio.file.Files.writeString(globalClaudeMd, "# Global Instructions\nUse normalized path.");
        System.setProperty("user.home", fakeHome + "/");
        try {
            String prompt = builder.build(testAgent, model, tempDir.resolve("workspace").toString());

            assertFalse(prompt.contains("Global Instructions"));
            assertFalse(prompt.contains(globalClaudeMd.toString()));
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", originalHome);
            }
        }
    }

    @Test
    void projectAgentsFileTakesPrecedenceOverClaudeFile() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("AGENTS.md"), "Current project instructions");
        java.nio.file.Files.writeString(tempDir.resolve("CLAUDE.md"), "Old model-specific instructions");

        String prompt = builder.build(testAgent, model, tempDir.toString());

        assertTrue(prompt.contains("Current project instructions"));
        assertFalse(prompt.contains("Old model-specific instructions"));
    }

    @Test
    void projectClaudeFileIsFallbackWhenAgentsFileIsAbsent() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("CLAUDE.md"), "Fallback project instructions");

        String prompt = builder.build(testAgent, model, tempDir.toString());

        assertTrue(prompt.contains("Fallback project instructions"));
    }

    @Test
    void buildInNonGitDirectoryOmitsGitContext() {
        String prompt = builder.build(testAgent, model, tempDir.toString());
        assertFalse(prompt.contains("<git_context>"));
    }

    // ── Custom agent prompt ──

    @Test
    void customAgentPromptOverridesProvider() {
        AgentDef customAgent = new AgentDef() {
            @Override public String getName() { return "custom"; }
            @Override public String getSystemPrompt(java.util.function.Function<String, String> modelProvider) {
                return "Custom system prompt override";
            }
        };

        String prompt = builder.build(customAgent, model, tempDir.toString());
        assertTrue(prompt.contains("Custom system prompt override"));
    }
}
