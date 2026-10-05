package com.aliyun.odps.agentic.permission;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Rule, Action, PermissionService, PermissionReply.
 */
class PermissionSystemTest {

    // ── Rule ──

    @Test
    void ruleAllow() {
        Rule r = Rule.allow("file");
        assertEquals("file", r.permission());
        assertEquals("*", r.pattern());
        assertEquals(Action.ALLOW, r.action());
    }

    @Test
    void ruleAllowWithPattern() {
        Rule r = Rule.allow("file", "/safe/**");
        assertEquals("/safe/**", r.pattern());
    }

    @Test
    void ruleDeny() {
        Rule r = Rule.deny("bash");
        assertEquals(Action.DENY, r.action());
    }

    @Test
    void ruleDenyWithPattern() {
        Rule r = Rule.deny("bash", "rm *");
        assertEquals("rm *", r.pattern());
    }

    @Test
    void ruleAsk() {
        Rule r = Rule.ask("file");
        assertEquals(Action.ASK, r.action());
    }

    @Test
    void ruleAskWithPattern() {
        Rule r = Rule.ask("bash", "sudo *");
        assertEquals("sudo *", r.pattern());
    }

    // ── Action ──

    @Test
    void actionValues() {
        assertEquals(3, Action.values().length);
        assertNotNull(Action.ALLOW);
        assertNotNull(Action.DENY);
        assertNotNull(Action.ASK);
    }

    // ── PermissionReply ──

    @Test
    void permissionReplyValues() {
        assertEquals(3, PermissionReply.values().length);
        assertNotNull(PermissionReply.ONCE);
        assertNotNull(PermissionReply.ALWAYS);
        assertNotNull(PermissionReply.REJECT);
    }

    // ── PermissionService ──

    @Test
    void permissionServiceAutoAllow() throws Exception {
        List<Rule> rules = List.of(Rule.allow("file"), Rule.deny("bash"));
        PermissionEngine engine = new PermissionEngine(rules);
        AsyncPermissionAsker asker = req -> CompletableFuture.completedFuture(PermissionReply.ONCE);
        PermissionService service = new PermissionService(engine, asker);

        boolean result = service.ask(PermissionRequest.create("sess-1", "read_tool", "call-1", "file", "/tmp/test.txt", "read file"));
        assertTrue(result);
    }

    @Test
    void permissionServiceAutoDeny() throws Exception {
        List<Rule> rules = List.of(Rule.deny("bash"));
        PermissionEngine engine = new PermissionEngine(rules);
        AsyncPermissionAsker asker = req -> CompletableFuture.completedFuture(PermissionReply.ONCE);
        PermissionService service = new PermissionService(engine, asker);

        boolean result = service.ask(PermissionRequest.create("sess-1", "shell_tool", "call-1", "bash", "rm -rf /", "dangerous"));
        assertFalse(result);
    }

    @Test
    void permissionServiceAskAllowed() throws Exception {
        List<Rule> rules = List.of(Rule.ask("file"));
        PermissionEngine engine = new PermissionEngine(rules);
        AsyncPermissionAsker asker = req -> CompletableFuture.completedFuture(PermissionReply.ONCE);
        PermissionService service = new PermissionService(engine, asker);

        boolean result = service.ask(PermissionRequest.create("sess-1", "read_tool", "call-1", "file", "/etc/passwd", "read"));
        assertTrue(result);
    }

    @Test
    void permissionServiceAskRejected() throws Exception {
        List<Rule> rules = List.of(Rule.ask("file"));
        PermissionEngine engine = new PermissionEngine(rules);
        AsyncPermissionAsker asker = req -> CompletableFuture.completedFuture(PermissionReply.REJECT);
        PermissionService service = new PermissionService(engine, asker);

        boolean result = service.ask(PermissionRequest.create("sess-1", "read_tool", "call-1", "file", "/etc/passwd", "read"));
        assertFalse(result);
    }

    @Test
    void permissionServiceAlwaysAddsRule() throws Exception {
        List<Rule> rules = List.of(Rule.ask("file"));
        PermissionEngine engine = new PermissionEngine(rules);
        AsyncPermissionAsker asker = req -> CompletableFuture.completedFuture(PermissionReply.ALWAYS);
        PermissionService service = new PermissionService(engine, asker);

        boolean result = service.ask(PermissionRequest.create("sess-1", "read_tool", "call-1", "file", "/tmp/f.txt", "read"));
        assertTrue(result);
    }

    @Test
    void permissionServiceDisabled() {
        PermissionEngine engine = new PermissionEngine(List.of(), true);
        AsyncPermissionAsker asker = req -> CompletableFuture.completedFuture(PermissionReply.REJECT);
        PermissionService service = new PermissionService(engine, asker);

        boolean result = service.ask(PermissionRequest.create("sess-1", "shell_tool", "call-1", "bash", "rm -rf /", "dangerous"));
        assertTrue(result);
    }

    // ── PermissionRequest ──

    @Test
    void permissionRequestCreation() {
        PermissionRequest req = PermissionRequest.create("sess-1", "read_tool", "call-1", "file", "/tmp/f.txt", "read file");
        assertNotNull(req.id());
        assertEquals("sess-1", req.sessionId());
        assertEquals("read_tool", req.tool());
        assertEquals("call-1", req.callId());
        assertEquals("file", req.permission());
        assertEquals("read file", req.description());
        assertEquals("/tmp/f.txt", req.target());
    }
}
