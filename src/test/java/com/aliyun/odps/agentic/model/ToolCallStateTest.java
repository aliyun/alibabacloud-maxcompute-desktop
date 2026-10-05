package com.aliyun.odps.agentic.model;

import org.junit.jupiter.api.*;
import com.aliyun.odps.agentic.model.ToolCallState.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for ToolCallState — sealed interface with 4 variants.
 * Ensures lifecycle semantics: Pending → Running → Completed/Error.
 */
class ToolCallStateTest {

    @Test
    void pendingDefault() {
        Pending p = new Pending();
        assertNull(p.input());
        assertEquals("", p.raw());
    }

    @Test
    void pendingWithInput() {
        Pending p = new Pending(Map.of("cmd", "ls"), "raw-json");
        assertEquals("ls", ((Map<?,?>) p.input()).get("cmd"));
        assertEquals("raw-json", p.raw());
    }

    @Test
    void runningConstruction() {
        Running r = new Running(Map.of("path", "/tmp"), "json-raw");
        assertNotNull(r.input());
        assertEquals("json-raw", r.raw());
    }

    @Test
    void completedConstruction() {
        ToolOutput output = new ToolOutput("Shell", "hello world");
        ToolTime time = new ToolTime(1000L, 2000L);
        Completed c = new Completed(Map.of("cmd", "echo"), "raw", output, time);
        assertEquals("Shell", c.output().title());
        assertEquals("hello world", c.output().output());
        assertEquals(1000L, c.time().start());
        assertEquals(2000L, c.time().end());
    }

    @Test
    void completedOutputWithMetadata() {
        ToolOutput output = new ToolOutput("Title", "Result", Map.of("exitCode", 0));
        assertEquals(0, output.metadata().get("exitCode"));
    }

    @Test
    void errorConstruction() {
        ToolTime time = new ToolTime(1000L, 1500L);
        ToolCallState.Error err = new ToolCallState.Error(Map.of("cmd", "rm"), "raw", "permission denied", time);
        assertEquals("permission denied", err.error());
        assertFalse(err.isInterrupted());
    }

    @Test
    void errorInterrupted() {
        ToolTime time = new ToolTime(1000L, 1500L);
        ToolCallState.Error err = new ToolCallState.Error(null, "", "cancelled", time, true);
        assertTrue(err.isInterrupted());
    }

    @Test
    void errorDefaultNotInterrupted() {
        ToolTime time = new ToolTime(1000L);
        ToolCallState.Error err = new ToolCallState.Error(null, "", "fail", time);
        assertFalse(err.isInterrupted());
    }

    @Test
    void toolTimeConstruction() {
        ToolTime t = new ToolTime(100L);
        assertEquals(100L, t.start());
        assertNull(t.end());
        assertNull(t.compacted());
    }

    @Test
    void toolTimeFull() {
        ToolTime t = new ToolTime(100L, 200L, 300L);
        assertEquals(100L, t.start());
        assertEquals(200L, t.end());
        assertEquals(300L, t.compacted());
    }

    @Test
    void toolTimeIsCompacted() {
        ToolTime compacted = new ToolTime(100L, 200L, 300L);
        ToolTime notCompacted = new ToolTime(100L, 200L);
        assertTrue(compacted.isCompacted());
        assertFalse(notCompacted.isCompacted());
    }

    @Test
    void lifecycleSequence() {
        // Pending → Running → Completed
        Pending pending = new Pending(Map.of("cmd", "ls"), "raw");
        Running running = new Running(pending.input(), pending.raw());
        ToolOutput output = new ToolOutput("Shell", "file.txt");
        Completed completed = new Completed(running.input(), running.raw(), output, new ToolTime(1L, 2L));
        assertEquals("file.txt", completed.output().output());
    }

    @Test
    void lifecycleError() {
        // Pending → Running → Error
        Pending pending = new Pending(Map.of("cmd", "rm"), "raw");
        Running running = new Running(pending.input(), pending.raw());
        ToolCallState.Error error = new ToolCallState.Error(running.input(), running.raw(), "permission denied", new ToolTime(1L, 2L));
        assertEquals("permission denied", error.error());
    }

    @Test
    void sealedInterfacePatternMatching() {
        ToolCallState state = new Completed(null, "", new ToolOutput("T", "O"), new ToolTime(1L));
        String result = switch (state) {
            case Pending p -> "pending";
            case Running r -> "running";
            case Completed c -> "completed:" + c.output().title();
            case ToolCallState.Error e -> "error:" + e.error();
        };
        assertEquals("completed:T", result);
    }

    @Test
    void toolOutputMinimal() {
        ToolOutput output = new ToolOutput("Title", "Content");
        assertEquals("Title", output.title());
        assertEquals("Content", output.output());
        assertNull(output.metadata());
    }
}
