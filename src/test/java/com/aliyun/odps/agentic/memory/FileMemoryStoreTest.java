package com.aliyun.odps.agentic.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileMemoryStoreTest {

    @TempDir Path tempDir;
    private FileMemoryStore store;

    @BeforeEach
    void setup() {
        store = new FileMemoryStore(tempDir, MemoryConfig.defaultConfig());
    }

    // ── search ──

    @Test
    void search_emptyDir_returnsEmpty() {
        List<MemoryEntry> results = store.search("anything", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_nullQuery_returnsEmpty() {
        List<MemoryEntry> results = store.search(null, 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_blankQuery_returnsEmpty() {
        List<MemoryEntry> results = store.search("   ", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_longTermMemory_hit() throws IOException {
        Files.writeString(tempDir.resolve("MEMORY.md"),
            "# Long-term\n\nProject uses Java 21 and Maven.\n");

        List<MemoryEntry> results = store.search("Java", 5);
        assertEquals(1, results.size());
        assertEquals("long-term", results.get(0).category());
        assertTrue(results.get(0).score() > 0);
    }

    @Test
    void search_longTermMemory_miss() throws IOException {
        Files.writeString(tempDir.resolve("MEMORY.md"),
            "# Long-term\n\nProject uses Python.\n");

        List<MemoryEntry> results = store.search("Rust", 5);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_dailyNotes_hit() throws IOException {
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("2026-06-04.md"),
            "# Daily\n\nFixed a bug in RunLoop today.\n");

        List<MemoryEntry> results = store.search("RunLoop", 5);
        assertEquals(1, results.size());
        assertEquals("daily", results.get(0).category());
    }

    @Test
    void search_multipleResults_sortedByScore() throws IOException {
        // Long-term has higher weight (1.5x)
        Files.writeString(tempDir.resolve("MEMORY.md"),
            "# Memory\n\nJava Java Java everywhere\n");
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("2026-06-04.md"),
            "# Daily\n\nUsed Java once\n");

        List<MemoryEntry> results = store.search("Java", 5);
        assertEquals(2, results.size());
        // Long-term should be first (higher score due to more occurrences + weight)
        assertEquals("long-term", results.get(0).category());
        assertEquals("daily", results.get(1).category());
    }

    @Test
    void search_limitApplied() throws IOException {
        Files.writeString(tempDir.resolve("MEMORY.md"), "Java content here");
        Path memoryDir = tempDir.resolve("memory");
        Files.createDirectories(memoryDir);
        Files.writeString(memoryDir.resolve("2026-06-03.md"), "Java in daily note 1");
        Files.writeString(memoryDir.resolve("2026-06-04.md"), "Java in daily note 2");

        List<MemoryEntry> results = store.search("Java", 2);
        assertEquals(2, results.size());
    }

    // ── append ──

    @Test
    void append_longTerm_createsFile() throws IOException {
        store.append("long-term", "Important decision: use PostgreSQL");

        Path file = tempDir.resolve("MEMORY.md");
        assertTrue(Files.exists(file));
        String content = Files.readString(file);
        assertTrue(content.contains("Important decision: use PostgreSQL"));
        assertTrue(content.contains("## ")); // has date header
    }

    @Test
    void append_longTerm_appendsToExisting() throws IOException {
        Files.writeString(tempDir.resolve("MEMORY.md"), "# Long-term\n\nExisting content\n");
        store.append("long-term", "New content added");

        String content = Files.readString(tempDir.resolve("MEMORY.md"));
        assertTrue(content.contains("Existing content"));
        assertTrue(content.contains("New content added"));
    }

    @Test
    void append_daily_createsDailyFile() {
        store.append("daily", "Fixed a bug today");

        Path dailyDir = tempDir.resolve("memory");
        assertTrue(Files.isDirectory(dailyDir));
    }

    @Test
    void append_nullContent_noOp() {
        store.append("long-term", null);
        assertFalse(Files.exists(tempDir.resolve("MEMORY.md")));
    }

    @Test
    void append_blankContent_noOp() {
        store.append("long-term", "   ");
        assertFalse(Files.exists(tempDir.resolve("MEMORY.md")));
    }

    // ── compact ──

    @Test
    void compact_longTerm_replacesContent() throws IOException {
        Files.writeString(tempDir.resolve("MEMORY.md"),
            "# Long-term\n\nLots of detailed content that should be summarized.\n");

        store.compact("long-term", "Key summary: use Java 21 + Maven");

        String content = Files.readString(tempDir.resolve("MEMORY.md"));
        assertTrue(content.contains("Key summary: use Java 21 + Maven"));
        assertFalse(content.contains("Lots of detailed content"));
        assertTrue(content.contains("compacted"));
    }

    @Test
    void compact_createsFileIfMissing() {
        store.compact("long-term", "Fresh compacted summary");

        assertTrue(Files.exists(tempDir.resolve("MEMORY.md")));
    }

    @Test
    void compact_nullContent_noOp() {
        store.compact("long-term", null);
        assertFalse(Files.exists(tempDir.resolve("MEMORY.md")));
    }

    @Test
    void compact_blankContent_noOp() {
        store.compact("long-term", "  ");
        assertFalse(Files.exists(tempDir.resolve("MEMORY.md")));
    }
}
