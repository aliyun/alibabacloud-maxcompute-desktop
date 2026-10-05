package com.aliyun.odps.agentic.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SkillLoader — skill discovery and loading.
 */
class SkillLoaderTest {

    @TempDir
    Path tempDir;

    private SkillLoader loader;

    @BeforeEach
    void setUp() throws IOException {
        // Create a test skill directory structure
        Path skillDir = tempDir.resolve("skills");
        Files.createDirectories(skillDir.resolve("my-skill"));
        Files.createDirectories(skillDir.resolve("another-skill"));

        // Write SKILL.md files
        String skill1 = """
            ---
            name: my-skill
            description: A test skill
            ---
            This is my skill content.
            It does useful things.
            """;
        Files.writeString(skillDir.resolve("my-skill").resolve("SKILL.md"), skill1);

        String skill2 = """
            ---
            name: another-skill
            description: Another test skill
            ---
            Another skill's content.
            """;
        Files.writeString(skillDir.resolve("another-skill").resolve("SKILL.md"), skill2);

        // Skill without frontmatter
        Files.createDirectories(skillDir.resolve("no-frontmatter"));
        Files.writeString(skillDir.resolve("no-frontmatter").resolve("SKILL.md"),
            "Just content without frontmatter.");

        loader = new SkillLoader(List.of(skillDir), List.of());
    }

    @Test
    void discover_findsSkillsWithFrontmatter() {
        List<SkillInfo> skills = loader.discover();

        assertEquals(3, skills.size());

        SkillInfo mySkill = skills.stream()
            .filter(s -> s.name().equals("my-skill"))
            .findFirst().orElse(null);
        assertNotNull(mySkill);
        assertEquals("A test skill", mySkill.description());
        assertTrue(mySkill.content().contains("This is my skill content"));
    }

    @Test
    void discover_skillWithoutFrontmatter() {
        List<SkillInfo> skills = loader.discover();

        SkillInfo noFm = skills.stream()
            .filter(s -> s.name().equals("no-frontmatter"))
            .findFirst().orElse(null);
        assertNotNull(noFm);
        assertEquals("Just content without frontmatter.", noFm.content());
    }

    @Test
    void discover_parsesFoldedMultilineDescription() throws IOException {
        Path skillDir = tempDir.resolve("skills").resolve("maxframe");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
            ---
            name: maxframe
            description: >
                Use this skill for MaxFrame SDK development and documentation navigation.
                Trigger for both English and Chinese MaxFrame queries.
            license: MIT
            ---
            Load references only when needed.
            """);

        SkillInfo skill = loader.discover().stream()
            .filter(s -> s.name().equals("maxframe"))
            .findFirst().orElseThrow();

        assertEquals(
            "Use this skill for MaxFrame SDK development and documentation navigation. "
                + "Trigger for both English and Chinese MaxFrame queries.",
            skill.description());
    }

    @Test
    void load_existingSkill() {
        SkillInfo skill = loader.load("my-skill").orElse(null);
        assertNotNull(skill);
        assertEquals("my-skill", skill.name());
    }

    @Test
    void load_nonexistentSkill() {
        assertFalse(loader.load("nonexistent").isPresent());
    }

    @Test
    void buildSkillsPrompt() {
        List<SkillInfo> skills = loader.discover();
        String prompt = loader.buildSkillsPrompt(skills);

        assertTrue(prompt.contains("## Skills"));
        assertTrue(prompt.contains("### my-skill"));
        assertTrue(prompt.contains("### another-skill"));
        assertTrue(prompt.contains("A test skill"));
    }

    @Test
    void buildSkillsPrompt_empty() {
        SkillLoader emptyLoader = new SkillLoader(List.of(), List.of());
        String prompt = emptyLoader.buildSkillsPrompt(List.of());
        assertEquals("", prompt);
    }

    @Test
    void discover_nonexistentPath() {
        SkillLoader loader = new SkillLoader(List.of(Path.of("/nonexistent/path")), List.of());
        List<SkillInfo> skills = loader.discover();
        assertTrue(skills.isEmpty());
    }
}
