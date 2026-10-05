package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.aliyun.odps.agentic.skill.SkillLoader;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SkillTool — loading a skill must surface its full content plus the
 * base directory and a sampled file list, so bundled resources (scripts/, etc.)
 * are usable. Faithful to opencode tool/skill.ts toModelOutput.
 */
class SkillToolTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolContext ctx() {
        return new ToolContext("s1", "m1", "build", "c1", List.of(), Map.of(), x -> {}, null, null);
    }

    @Test
    void execute_returnsContentBaseDirAndFileList() throws IOException {
        Path skillDir = tempDir.resolve("pdf-tools");
        Files.createDirectories(skillDir.resolve("scripts"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: pdf-tools
            description: Work with PDFs
            ---
            Use the bundled scripts to manipulate PDFs.
            """);
        Files.writeString(skillDir.resolve("scripts").resolve("extract.py"), "print('hi')");
        Files.writeString(skillDir.resolve("reference.md"), "# Reference");

        SkillLoader loader = new SkillLoader(List.of(tempDir), List.of());
        SkillTool tool = new SkillTool(loader);

        ObjectNode args = MAPPER.createObjectNode();
        args.put("name", "pdf-tools");
        ToolResult result = tool.execute(args, ctx());

        assertFalse(result.isError(), "loading an existing skill must not error");
        String out = result.output();
        assertTrue(out.contains("<skill_content name=\"pdf-tools\">"), "must wrap content");
        assertTrue(out.contains("Use the bundled scripts"), "must include skill body");
        assertTrue(out.contains("Base directory for this skill:"), "must state base directory");
        assertTrue(out.contains("file:"), "base directory must be a file URI");
        assertTrue(out.contains("<skill_files>"), "must include a file list block");
        assertTrue(out.contains("extract.py"), "nested sibling script must be listed");
        assertTrue(out.contains("reference.md"), "sibling file must be listed");
        assertFalse(out.contains("SKILL.md</file>"), "SKILL.md itself must be excluded from the file list");
    }

    @Test
    void execute_skillNotFound_returnsError() {
        SkillLoader loader = new SkillLoader(List.of(tempDir), List.of());
        SkillTool tool = new SkillTool(loader);

        ObjectNode args = MAPPER.createObjectNode();
        args.put("name", "does-not-exist");
        ToolResult result = tool.execute(args, ctx());

        assertTrue(result.isError());
    }

    @Test
    void execute_rejectsSkillOutsideAgentProfileWhitelist() throws IOException {
        Path skillDir = tempDir.resolve("sql-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: sql-skill
            description: MaxCompute SQL
            ---
            Follow the SQL workflow.
            """);
        SkillTool tool = new SkillTool(new SkillLoader(List.of(tempDir), List.of()));
        ToolContext restricted = new ToolContext(
            "s1", "m1", "build", "c1", List.of(),
            Map.of(SkillTool.INCLUDED_SKILLS_CONTEXT_KEY, List.of("udf-skill")),
            ignored -> {}, null, null);

        ToolResult result = tool.execute(
            MAPPER.createObjectNode().put("name", "sql-skill"), restricted);

        assertTrue(result.isError());
        assertTrue(result.output().contains("not enabled for this agent profile"));
    }
}
