package com.aliyun.odps.agentic.skill;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SkillDiscoveryTest {

    @TempDir Path tempDir;

    private final SkillDiscovery discovery = new SkillDiscovery(Path.of("/tmp/skill-cache-test"));

    // ── parseIndex (tested indirectly via local file simulation) ──

    @Test
    void discoverFromRepository_invalidUrl_returnsEmpty() {
        List<SkillInfo> results = discovery.discoverFromRepository("http://localhost:99999/nonexistent");
        assertTrue(results.isEmpty());
    }

    @Test
    void discoverFromRepository_emptyUrl_returnsEmpty() {
        List<SkillInfo> results = discovery.discoverFromRepository("");
        assertTrue(results.isEmpty());
    }

    // ── SkillInfo record tests ──

    @Test
    void skillInfo_recordAccessors() {
        SkillInfo info = new SkillInfo("test-skill", "A test skill", "/path", "# Content");
        assertEquals("test-skill", info.name());
        assertEquals("A test skill", info.description());
        assertEquals("/path", info.location());
        assertEquals("# Content", info.content());
    }

    @Test
    void skillInfo_nullFields() {
        SkillInfo info = new SkillInfo("minimal", null, null, null);
        assertEquals("minimal", info.name());
        assertNull(info.description());
        assertNull(info.location());
        assertNull(info.content());
    }

    // ── Integration with SkillLoader (local filesystem) ──

    @Test
    void skillLoader_discoversSkillsFromDir() throws IOException {
        // Create a temporary skill directory structure
        Path skillDir = tempDir.resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# My Skill\n\nDoes something useful.\n");

        SkillLoader loader = new SkillLoader(List.of(tempDir), List.of());
        List<SkillInfo> skills = loader.discover();

        assertEquals(1, skills.size());
        assertEquals("my-skill", skills.get(0).name());
        assertTrue(skills.get(0).content().contains("Does something useful"));
    }

    @Test
    void skillLoader_ignoresDirWithoutSkillMd() throws IOException {
        Path noSkill = tempDir.resolve("no-skill-dir");
        Files.createDirectories(noSkill);
        Files.writeString(noSkill.resolve("README.md"), "# Not a skill");

        SkillLoader loader = new SkillLoader(List.of(tempDir), List.of());
        List<SkillInfo> skills = loader.discover();
        assertTrue(skills.isEmpty());
    }

    @Test
    void skillLoader_multipleSkills() throws IOException {
        for (String name : List.of("skill-a", "skill-b", "skill-c")) {
            Path dir = tempDir.resolve(name);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("SKILL.md"), "# " + name + "\n\nDescription of " + name);
        }

        SkillLoader loader = new SkillLoader(List.of(tempDir), List.of());
        List<SkillInfo> skills = loader.discover();
        assertEquals(3, skills.size());
    }

    @Test
    void skillLoader_nonexistentDir_returnsEmpty() {
        SkillLoader loader = new SkillLoader(List.of(tempDir.resolve("nonexistent")), List.of());
        List<SkillInfo> skills = loader.discover();
        assertTrue(skills.isEmpty());
    }
}
