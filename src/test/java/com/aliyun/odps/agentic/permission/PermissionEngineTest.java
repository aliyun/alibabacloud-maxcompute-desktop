package com.aliyun.odps.agentic.permission;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionEngineTest {

    @Test
    void allowRule() {
        PermissionEngine engine = new PermissionEngine(
            List.of(new Rule("file", "*", Action.ALLOW)));
        assertEquals(Action.ALLOW, engine.evaluate("file", "/tmp/test.txt"));
    }

    @Test
    void denyRule() {
        PermissionEngine engine = new PermissionEngine(
            List.of(new Rule("bash", "*", Action.DENY)));
        assertEquals(Action.DENY, engine.evaluate("bash", "rm -rf /"));
    }

    @Test
    void askRule() {
        PermissionEngine engine = new PermissionEngine(
            List.of(new Rule("file", "*", Action.ASK)));
        assertEquals(Action.ASK, engine.evaluate("file", "/tmp/test.txt"));
    }

    @Test
    void firstMatchWins() {
        List<Rule> rules = List.of(
            new Rule("file", "*", Action.ALLOW),
            new Rule("bash", "*", Action.DENY),
            new Rule("mcp", "*", Action.ASK)
        );
        PermissionEngine engine = new PermissionEngine(rules);

        assertEquals(Action.ALLOW, engine.evaluate("file", "test"));
        assertEquals(Action.DENY, engine.evaluate("bash", "test"));
        assertEquals(Action.ASK, engine.evaluate("mcp", "test"));
    }

    @Test
    void noMatchingRule_returnsAsk() {
        PermissionEngine engine = new PermissionEngine(List.of());
        assertEquals(Action.ASK, engine.evaluate("bash", "test"));
    }

    @Test
    void disabledEngine_returnsAllow() {
        PermissionEngine engine = new PermissionEngine(List.of(), true);
        assertEquals(Action.ALLOW, engine.evaluate("bash", "dangerous"));
    }

    @Test
    void globPattern() {
        List<Rule> rules = List.of(
            new Rule("file", "*.txt", Action.ALLOW),
            new Rule("file", "*.secret", Action.DENY)
        );
        PermissionEngine engine = new PermissionEngine(rules);

        assertEquals(Action.ALLOW, engine.evaluate("file", "notes.txt"));
        assertEquals(Action.DENY, engine.evaluate("file", "keys.secret"));
    }

    @Test
    void wildcardPermission() {
        List<Rule> rules = List.of(new Rule("*", "*", Action.ALLOW));
        PermissionEngine engine = new PermissionEngine(rules);
        assertEquals(Action.ALLOW, engine.evaluate("anything", "anything"));
    }

    @Test
    void ruleRecord() {
        Rule rule = new Rule("file", "*.txt", Action.ALLOW);
        assertEquals("file", rule.permission());
        assertEquals("*.txt", rule.pattern());
        assertEquals(Action.ALLOW, rule.action());
    }

    @Test
    void actionValues() {
        assertEquals("ALLOW", Action.ALLOW.name());
        assertEquals("DENY", Action.DENY.name());
        assertEquals("ASK", Action.ASK.name());
    }
}
