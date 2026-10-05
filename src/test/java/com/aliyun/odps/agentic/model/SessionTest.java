package com.aliyun.odps.agentic.model;

import org.junit.jupiter.api.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Session, Role, SessionStatus, Tokens, LatestResult, PendingTask.
 */
class SessionTest {

    // ── Session ──

    @Test
    void sessionMinimal() {
        Session s = new Session("s1", "My Session");
        assertEquals("s1", s.id());
        assertEquals("My Session", s.title());
        assertEquals(SessionStatus.IDLE, s.status());
        assertEquals(0.0, s.cost());
        assertTrue(s.messages().isEmpty());
    }

    @Test
    void sessionWithStatus() {
        Session s = new Session("s1", "test");
        Session running = s.withStatus(SessionStatus.BUSY);
        assertEquals(SessionStatus.BUSY, running.status());
        assertEquals(SessionStatus.IDLE, s.status()); // original unchanged
    }

    @Test
    void sessionWithTitle() {
        Session s = new Session("s1", "old");
        Session updated = s.withTitle("new");
        assertEquals("new", updated.title());
    }

    @Test
    void sessionAddCost() {
        Session s = new Session("s1", "test");
        Session updated = s.addCost(0.05);
        assertEquals(0.05, updated.cost(), 0.001);
        Session more = updated.addCost(0.03);
        assertEquals(0.08, more.cost(), 0.001);
    }

    @Test
    void sessionAddTokens() {
        Session s = new Session("s1", "test");
        Tokens t = new Tokens(100, 50, 10, 30, 20, 210);
        Session updated = s.addTokens(t);
        assertEquals(100, updated.tokens().input());
        assertEquals(50, updated.tokens().output());
    }

    @Test
    void sessionAddUsage() {
        Session s = new Session("s1", "test");
        Tokens t = new Tokens(100, 50, 10, 30, 20, 210);
        Session updated = s.addUsage(0.05, t);
        assertEquals(0.05, updated.cost(), 0.001);
        assertEquals(100, updated.tokens().input());
    }

    @Test
    void sessionWithModel() {
        com.aliyun.odps.agentic.llm.Model modelA = com.aliyun.odps.agentic.llm.Model.of(
            "mock", "model-a", new com.aliyun.odps.agentic.llm.ModelLimit(128000, null, 4096));
        com.aliyun.odps.agentic.llm.Model modelB = com.aliyun.odps.agentic.llm.Model.of(
            "mock", "model-b", new com.aliyun.odps.agentic.llm.ModelLimit(200000, null, 8192));
        Session s = new Session("s1", "test", SessionStatus.IDLE, List.of(),
            "agent", modelA, 0.0, Tokens.empty(), Instant.now(), Instant.now(), null);

        Session switched = s.withModel(modelB);

        assertEquals(modelB, switched.model());
        assertEquals("s1", switched.id());        // same session identity
        assertEquals("agent", switched.agent());  // other fields preserved
        assertEquals(modelA, s.model());           // original unchanged
    }

    @Test
    void sessionWithMessages() {
        Message msg = new Message("m1", "s1", Role.USER, List.of(new MessagePart.TextPart("hello")));
        Session s = new Session("s1", "test");
        Session updated = s.withMessages(List.of(msg));
        assertEquals(1, updated.messages().size());
    }

    @Test
    void sessionUndoLastAssistantTurn() {
        Message user = new Message("m1", "s1", Role.USER, List.of(new MessagePart.TextPart("hello")));
        Message assistant = new Message("m2", "s1", Role.ASSISTANT, List.of(new MessagePart.TextPart("hi")));
        Session s = new Session("s1", "test", SessionStatus.IDLE, List.of(user, assistant),
            null, null, 0.0, Tokens.empty(), Instant.now(), Instant.now(), null);
        Session undone = s.undoLastAssistantTurn();
        assertEquals(1, undone.messages().size());
        assertEquals(Role.USER, undone.messages().get(0).role());
    }

    // ── Status ──

    @Test
    void sessionStatusValues() {
        assertEquals(4, SessionStatus.values().length);
        assertNotNull(SessionStatus.IDLE);
        assertNotNull(SessionStatus.BUSY);
        assertNotNull(SessionStatus.SUSPENDED);
        assertNotNull(SessionStatus.ERROR);
    }

    @Test
    void roleValues() {
        assertEquals(3, Role.values().length);
        assertNotNull(Role.USER);
        assertNotNull(Role.ASSISTANT);
        assertNotNull(Role.SYSTEM);
    }

    @Test
    void taskTypeValues() {
        assertEquals(3, TaskType.values().length);
        assertNotNull(TaskType.SUBTASK);
        assertNotNull(TaskType.COMPACTION);
        assertNotNull(TaskType.OVERFLOW);
    }

    // ── Tokens ──

    @Test
    void tokensEmpty() {
        Tokens t = Tokens.empty();
        assertEquals(0, t.input());
        assertEquals(0, t.output());
        assertEquals(0, t.total());
    }

    @Test
    void tokensAdd() {
        Tokens a = new Tokens(100, 50, 10, 20, 10, 190);
        Tokens b = new Tokens(200, 30, 5, 10, 5, 250);
        Tokens sum = a.add(b);
        assertEquals(300, sum.input());
        assertEquals(80, sum.output());
        assertEquals(15, sum.reasoning());
        assertEquals(30, sum.cacheRead());
        assertEquals(15, sum.cacheWrite());
        assertEquals(440, sum.total());
    }

    @Test
    void tokensFromUsage() {
        // Usage(inputTokens=1000, outputTokens=200, cacheRead=100, cacheCreation=80, reasoning=50)
        com.aliyun.odps.agentic.llm.Usage usage = new com.aliyun.odps.agentic.llm.Usage(1000, 200, 100, 80, 50);
        Tokens t = Tokens.fromUsage(usage);
        // adjustedInput = inputTokens - cacheRead - cacheCreation = 1000 - 100 - 80 = 820
        assertEquals(820, t.input());
        assertEquals(200, t.output());
        // reasoning 单独带出来（此前这里硬写 0，Tokens.reasoning 因此零生产者）。
        assertEquals(50, t.reasoning());
        assertEquals(100, t.cacheRead());
        assertEquals(80, t.cacheWrite());
        // 但 total 仍是 input+output —— reasoning 是 output 的子集，重复计入会让思维链场景的总数翻倍。
        assertEquals(1200, t.total());
    }

    @Test
    void tokensFromUsageNeverLetsReasoningExceedOutput() {
        // provider 偶尔会给出 reasoning_tokens > completion_tokens 的自相矛盾载荷；
        // 钳到 output 上界，避免下游算出「思维链占比 > 100%」。
        Tokens t = Tokens.fromUsage(new com.aliyun.odps.agentic.llm.Usage(500, 40, 0, 0, 90));
        assertEquals(40, t.reasoning());
        assertEquals(40, t.output());
    }

    // ── PendingTask ──

    @Test
    void pendingTaskConstruction() {
        PendingTask pt = new PendingTask(TaskType.SUBTASK, "do something");
        assertEquals(TaskType.SUBTASK, pt.type());
        assertEquals("do something", pt.data());
    }

    // ── LatestResult ──

    @Test
    void latestResultConstruction() {
        Message user = new Message("m1", "s1", Role.USER, List.of(new MessagePart.TextPart("hello")));
        Message assistant = new Message("m2", "s1", Role.ASSISTANT, List.of(new MessagePart.TextPart("hi")));
        LatestResult lr = new LatestResult(user, assistant, new ArrayList<>());
        assertEquals(user, lr.lastUser());
        assertEquals(assistant, lr.lastAssistant());
        assertTrue(lr.pendingTasks().isEmpty());
    }

    @Test
    void latestResultNullAssistant() {
        Message user = new Message("m1", "s1", Role.USER, List.of(new MessagePart.TextPart("hello")));
        LatestResult lr = new LatestResult(user, null, new ArrayList<>());
        assertNull(lr.lastAssistant());
        assertFalse(lr.hasPendingToolCalls());
    }

    @Test
    void latestResultPopTask() {
        Message user = new Message("m1", "s1", Role.USER, List.of(new MessagePart.TextPart("hello")));
        List<PendingTask> tasks = new ArrayList<>();
        tasks.add(new PendingTask(TaskType.SUBTASK, "task1"));
        tasks.add(new PendingTask(TaskType.COMPACTION, "task2"));
        LatestResult lr = new LatestResult(user, null, tasks);
        PendingTask popped = lr.popTask();
        assertEquals(TaskType.COMPACTION, popped.type());
        assertEquals(1, lr.pendingTasks().size());
    }
}
