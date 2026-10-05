package com.aliyun.odps.agentic.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for MemoryConfig and MemoryEntry records.
 */
class MemoryRecordTest {

    // -- MemoryConfig --

    @Test
    void memoryConfigCanonicalConstructor() {
        MemoryConfig config = new MemoryConfig("custom.md", "notes", 50000);
        assertEquals("custom.md", config.longTermFile());
        assertEquals("notes", config.dailyNotesDir());
        assertEquals(50000, config.maxDailyNoteSize());
    }

    @Test
    void memoryConfigDefaultConfig() {
        MemoryConfig config = MemoryConfig.defaultConfig();
        assertEquals("MEMORY.md", config.longTermFile());
        assertEquals("memory", config.dailyNotesDir());
        assertEquals(100_000, config.maxDailyNoteSize());
    }

    @Test
    void memoryConfigEquality() {
        MemoryConfig c1 = MemoryConfig.defaultConfig();
        MemoryConfig c2 = new MemoryConfig("MEMORY.md", "memory", 100_000);
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    @Test
    void memoryConfigInequality() {
        MemoryConfig c1 = MemoryConfig.defaultConfig();
        MemoryConfig c2 = new MemoryConfig("OTHER.md", "memory", 100_000);
        assertNotEquals(c1, c2);
    }

    // -- MemoryEntry --

    @Test
    void memoryEntryConstruction() {
        MemoryEntry entry = new MemoryEntry("project", "SDK architecture notes", 0.85);
        assertEquals("project", entry.category());
        assertEquals("SDK architecture notes", entry.content());
        assertEquals(0.85, entry.score(), 0.001);
    }

    @Test
    void memoryEntryEquality() {
        MemoryEntry e1 = new MemoryEntry("user", "preference", 1.0);
        MemoryEntry e2 = new MemoryEntry("user", "preference", 1.0);
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    void memoryEntryInequality() {
        MemoryEntry e1 = new MemoryEntry("user", "preference", 1.0);
        MemoryEntry e2 = new MemoryEntry("user", "preference", 0.5);
        assertNotEquals(e1, e2);
    }

    @Test
    void memoryEntryZeroScore() {
        MemoryEntry entry = new MemoryEntry("misc", "low relevance", 0.0);
        assertEquals(0.0, entry.score(), 0.001);
    }
}
