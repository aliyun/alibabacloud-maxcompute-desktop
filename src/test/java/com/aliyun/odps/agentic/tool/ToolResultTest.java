package com.aliyun.odps.agentic.tool;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for ToolResult — construction, error detection, factory methods.
 */
class ToolResultTest {

    @Test
    void ofOutputOnly() {
        ToolResult r = ToolResult.of("hello");
        assertNull(r.title());
        assertEquals("hello", r.output());
        assertFalse(r.isError());
        assertTrue(r.attachments().isEmpty());
    }

    @Test
    void ofTitleAndOutput() {
        ToolResult r = ToolResult.of("Summary", "Full content here");
        assertEquals("Summary", r.title());
        assertEquals("Full content here", r.output());
        assertFalse(r.isError());
    }

    @Test
    void errorResult() {
        ToolResult r = ToolResult.error("Permission denied");
        assertEquals("Error", r.title());
        assertEquals("Permission denied", r.output());
        assertTrue(r.isError());
    }

    @Test
    void successResult() {
        ToolResult r = ToolResult.success("3 files found");
        assertNull(r.title());
        assertEquals("3 files found", r.output());
        assertFalse(r.isError());
    }

    @Test
    void successWithTitle() {
        ToolResult r = ToolResult.success("Search", "3 files found");
        assertEquals("Search", r.title());
        assertEquals("3 files found", r.output());
    }

    @Test
    void fullConstructor() {
        ToolResult r = new ToolResult("Title", Map.of("key", "val"), "output", null);
        assertEquals("Title", r.title());
        assertEquals("val", r.metadata().get("key"));
        assertEquals("output", r.output());
        assertNull(r.attachments());
    }

    @Test
    void isErrorOnlyWhenMetadataHasErrorTrue() {
        ToolResult r1 = new ToolResult("OK", Map.of(), "output", null);
        assertFalse(r1.isError());

        ToolResult r2 = new ToolResult("OK", Map.of("error", false), "output", null);
        assertFalse(r2.isError());

        ToolResult r3 = new ToolResult("OK", Map.of("error", true), "output", null);
        assertTrue(r3.isError());
    }
}
