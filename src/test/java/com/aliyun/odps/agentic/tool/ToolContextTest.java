package com.aliyun.odps.agentic.tool;

import org.junit.jupiter.api.*;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for ToolContext — factory, accessors, callbacks.
 */
class ToolContextTest {

    @Test
    void factoryMethod() {
        ToolContext ctx = ToolContext.of("sess-1", "msg-1", "agent", "call-1",
            List.of(), Map.of("key", "val"), m -> {}, (p, t, d) -> true);
        assertEquals("sess-1", ctx.sessionId());
        assertEquals("msg-1", ctx.messageId());
        assertEquals("agent", ctx.agent());
        assertEquals("call-1", ctx.callId());
        assertEquals("val", ctx.extra().get("key"));
        assertNull(ctx.progressReporter());
    }

    @Test
    void fullConstructor() {
        ToolContext ctx = new ToolContext("s", "m", "a", "c",
            List.of(), Map.of(), m -> {}, (p, t, d) -> false, (title, meta) -> {});
        assertNotNull(ctx.progressReporter());
    }

    @Test
    void permissionAskerCallback() {
        ToolContext.PermissionAsker asker = (perm, target, desc) -> {
            return "file".equals(perm) && target.startsWith("/safe/");
        };
        ToolContext ctx = ToolContext.of("s", "m", "a", "c",
            List.of(), Map.of(), m -> {}, asker);

        assertTrue(ctx.permissionAsker().ask("file", "/safe/file.txt", "read"));
        assertFalse(ctx.permissionAsker().ask("file", "/unsafe/file.txt", "read"));
        assertFalse(ctx.permissionAsker().ask("bash", "rm -rf /", "dangerous"));
    }

    @Test
    void metadataUpdaterCallback() {
        AtomicReference<Map<String, Object>> captured = new AtomicReference<>();
        ToolContext ctx = ToolContext.of("s", "m", "a", "c",
            List.of(), Map.of(), captured::set, (p, t, d) -> true);

        Map<String, Object> meta = Map.of("progress", 50);
        ctx.metadataUpdater().accept(meta);
        assertEquals(50, captured.get().get("progress"));
    }

    @Test
    void progressReporterCallback() {
        AtomicReference<String> capturedTitle = new AtomicReference<>();
        ToolContext.ProgressReporter reporter = (title, meta) -> capturedTitle.set(title);
        ToolContext ctx = new ToolContext("s", "m", "a", "c",
            List.of(), Map.of(), m -> {}, (p, t, d) -> true, reporter);

        ctx.progressReporter().report("Processing...", Map.of("step", 2));
        assertEquals("Processing...", capturedTitle.get());
    }

    @Test
    void messagesInContext() {
        Message msg = new Message("m1", "s1", Role.USER,
            List.of(new MessagePart.TextPart("hello")));
        ToolContext ctx = ToolContext.of("s", "m", "a", "c",
            List.of(msg), Map.of(), m -> {}, (p, t, d) -> true);
        assertEquals(1, ctx.messages().size());
        assertEquals("hello", ctx.messages().get(0).getTextContent());
    }
}
