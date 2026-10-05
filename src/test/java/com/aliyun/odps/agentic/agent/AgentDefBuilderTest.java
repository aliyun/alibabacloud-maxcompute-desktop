package com.aliyun.odps.agentic.agent;

import org.junit.jupiter.api.*;
import com.aliyun.odps.agentic.memory.MemoryConfig;
import com.aliyun.odps.agentic.mcp.McpServerConfig;
import com.aliyun.odps.agentic.permission.Rule;
import com.aliyun.odps.agentic.permission.Action;
import com.aliyun.odps.agentic.skill.SkillConfig;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for AgentDefBuilder — builder pattern, defaults, validation.
 */
class AgentDefBuilderTest {

    @Test
    void minimalBuild() {
        AgentDef def = AgentDefBuilder.create("test-agent")
            .systemPrompt("You are a test agent.")
            .build();
        assertEquals("test-agent", def.getName());
        assertEquals("You are a test agent.", def.getSystemPrompt(m -> "prefix"));
    }

    @Test
    void fullBuild() {
        AgentDef def = AgentDefBuilder.create("full-agent")
            .description("A fully configured agent")
            .systemPrompt("Do things")
            .maxSteps(50)
            .model(new ModelConfig("anthropic", "claude-sonnet-4-20250514"))
            .temperature(0.7)
            .topP(0.9)
            .memoryConfig(MemoryConfig.defaultConfig())
            .build();
        assertEquals("full-agent", def.getName());
        assertEquals("A fully configured agent", def.getDescription());
        assertEquals(50, def.getMaxSteps());
    }

    @Test
    void defaultMaxSteps() {
        AgentDef def = AgentDefBuilder.create("agent").systemPrompt("hi").build();
        assertEquals(200, def.getMaxSteps());
    }

    @Test
    void permissionRules() {
        AgentDef def = AgentDefBuilder.create("agent")
            .systemPrompt("hi")
            .addPermissionRule(Rule.allow("file"))
            .addPermissionRule(Rule.deny("bash"))
            .build();
        assertEquals(2, def.getPermissionRules().size());
        assertEquals(Action.ALLOW, def.getPermissionRules().get(0).action());
        assertEquals(Action.DENY, def.getPermissionRules().get(1).action());
    }

    @Test
    void mcpServers() {
        McpServerConfig server = new McpServerConfig("my-server", "node", List.of("server.js"));
        AgentDef def = AgentDefBuilder.create("agent")
            .systemPrompt("hi")
            .addMcpServer(server)
            .build();
        assertEquals(1, def.getMcpServers().size());
        assertEquals("my-server", def.getMcpServers().get(0).name());
    }

    @Test
    void mcpRemoteServer() {
        McpServerConfig server = McpServerConfig.remote("remote-server", "http://localhost:3001");
        AgentDef def = AgentDefBuilder.create("agent")
            .systemPrompt("hi")
            .addMcpServer(server)
            .build();
        assertEquals(1, def.getMcpServers().size());
        assertTrue(server.isRemote());
    }

    @Test
    void skillConfig() {
        SkillConfig skill = SkillConfig.fromPath("/skills/search");
        AgentDef def = AgentDefBuilder.create("agent")
            .systemPrompt("hi")
            .addSkill(skill)
            .build();
        assertEquals(1, def.getSkills().size());
        assertEquals("/skills/search", def.getSkills().get(0).path());
    }

    @Test
    void skillRepository() {
        AgentDef def = AgentDefBuilder.create("agent")
            .systemPrompt("hi")
            .addSkillRepository("https://github.com/skills/repo")
            .build();
        assertEquals(1, def.getSkillRepositories().size());
    }

    @Test
    void systemPromptProvider() {
        AgentDef def = AgentDefBuilder.create("agent")
            .systemPrompt(modelProvider -> "Hello from " + modelProvider)
            .build();
        String prompt = def.getSystemPrompt(m -> "claude");
        assertTrue(prompt.contains("claude"));
    }

    @Test
    void instructionFiles() {
        AgentDef def = AgentDefBuilder.create("agent")
            .systemPrompt("hi")
            .build();
        // Default instruction files includes AGENTS.md
        assertTrue(def.getInstructionFiles().contains("AGENTS.md"));
    }

    @Test
    void includedTools() {
        AgentDef def = AgentDefBuilder.create("agent")
            .systemPrompt("hi")
            .build();
        // Default should be empty or have some defaults
        assertNotNull(def.getIncludedTools());
    }
}
