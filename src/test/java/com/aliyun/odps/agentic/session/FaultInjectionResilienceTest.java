package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.llm.LlmApiException;
import com.aliyun.odps.agentic.llm.LlmRequest;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.llm.Usage;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.model.SessionStatus;
import com.aliyun.odps.agentic.tool.ToolDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 故障注入端到端测试 —— 把「注入故障 → 观测到重试/压缩/计数」串起来断言。
 *
 * <p>这是三套韧性机制（重试、溢出压缩恢复、思考链 token 记账）从「死代码」
 * 变成「在跑」的验收网：此前它们各自缺失的正是这样一条端到端断言，
 * 所以断点能长期存活而无人察觉。
 */
@Timeout(30)
class FaultInjectionResilienceTest {

    /** 测试前把重试退避压到 1ms——免除真实多秒退避，既快又不给邻近测试制造调度压力。 */
    @org.junit.jupiter.api.BeforeEach
    void shrinkRetryBackoff() {
        RunLoop.retryDelayOverrideForTest = d -> 1;
    }

    @org.junit.jupiter.api.AfterEach
    void restoreRetryBackoff() {
        RunLoop.retryDelayOverrideForTest = null;
    }

    private AgentDef testAgent() {
        return new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "You are test."; }
            @Override public List<ToolDef> getTools() { return List.of(); }
            @Override public int getMaxSteps() { return 10; }
        };
    }

    private HarnessEngine newEngine(LLMClient client) {
        return HarnessEngine.builder()
            .workDir(Path.of("."))
            .llmClient(client)
            .model(Model.of("test", "test-model", new ModelLimit(100000, null, 4096)))
            .build();
    }

    /** 跳过 TitleGenerator 的异步 LLM 调用，避免干扰故障注入计数。 */
    private Session titledSession(AgentDef agent) {
        Instant now = Instant.now();
        return new Session(
            UUID.randomUUID().toString(), "test-title", SessionStatus.IDLE, List.of(),
            agent.getName(), Model.of("test", "test-model", new ModelLimit(100000, null, 4096)),
            0.0, null, now, now, agent.getPermissionRules()
        );
    }

    private static LLMEvent[] okTurn(String text) {
        return new LLMEvent[]{
            new LLMEvent.TextStart("test-model"),
            new LLMEvent.TextDelta(text),
            new LLMEvent.Finish("end-turn"),
            new LLMEvent.TextEnd()
        };
    }

    // ── ① 重试可达性 ────────────────────────────────────────────────

    /** 前两次抛 429，第三次成功 —— 断言确实重试了且最终成功。 */
    @Test
    void httpErrorThrowsAndIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient flaky = new LLMClient() {
            @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
                int n = calls.incrementAndGet();
                if (n < 3) {
                    throw LlmApiException.fromHttpStatus(429, null, "rate limit");
                }
                for (LLMEvent e : okTurn("ok-after-retry")) c.accept(e);
            }
            @Override public boolean supports(String p) { return true; }
        };

        HarnessEngine engine = newEngine(flaky);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);
            Message result = engine.run(session, agent, "hi");

            assertEquals(3, calls.get(), "should retry twice then succeed");
            assertEquals("ok-after-retry", result.getTextContent());
            assertNull(result.error(), "successful retry should not leave an error");
        } finally {
            engine.shutdown();
        }
    }

    /** 永远 500 —— 重试耗尽后错误必须以 error 字段落库，而非无声成功。 */
    @Test
    void retriesExhausted_surfacesErrorMessage() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient alwaysDown = new LLMClient() {
            @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
                calls.incrementAndGet();
                throw LlmApiException.fromHttpStatus(500, null, "boom");
            }
            @Override public boolean supports(String p) { return true; }
        };

        HarnessEngine engine = newEngine(alwaysDown);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);
            Message result = engine.run(session, agent, "hi");

            // DEFAULT_MAX_RETRIES = 3 → 1 次首发 + 3 次重试 = 4 次调用
            assertEquals(RetryLogic.DEFAULT_MAX_RETRIES + 1, calls.get());
            assertEquals("error", result.finish());
            assertNotNull(result.error(), "exhausted retries must surface a non-null error");
            assertTrue(result.error().contains("500"), () -> "error should carry status: " + result.error());
        } finally {
            engine.shutdown();
        }
    }

    /** 网络/IO 故障（无状态码）同样可重试。 */
    @Test
    void networkFailureIsRetried() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient netFlaky = new LLMClient() {
            @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
                int n = calls.incrementAndGet();
                if (n < 2) {
                    throw LlmApiException.fromNetwork(new java.io.IOException("connection reset"));
                }
                for (LLMEvent e : okTurn("recovered")) c.accept(e);
            }
            @Override public boolean supports(String p) { return true; }
        };

        HarnessEngine engine = newEngine(netFlaky);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);
            Message result = engine.run(session, agent, "hi");

            assertEquals(2, calls.get());
            assertEquals("recovered", result.getTextContent());
        } finally {
            engine.shutdown();
        }
    }

    // ── ② 溢出压缩恢复可达性 ─────────────────────────────────────────

    /** 流式途中收到 context_length_exceeded —— 必须填 error 并触发溢出压缩。 */
    @Test
    void midStreamOverflow_populatesErrorAndTriggersCompaction() {
        // 第一次：流式吐出溢出错误；后续：正常结束（压缩后重跑成功）。
        AtomicInteger calls = new AtomicInteger();
        LLMClient overflowOnce = new LLMClient() {
            @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
                int n = calls.incrementAndGet();
                if (n == 1) {
                    c.accept(new LLMEvent.ProviderError("context_length_exceeded: too many tokens"));
                    c.accept(new LLMEvent.Finish("error"));
                    return;
                }
                for (LLMEvent e : okTurn("after-compaction-" + n)) c.accept(e);
            }
            @Override public boolean supports(String p) { return true; }
        };

        HarnessEngine engine = newEngine(overflowOnce);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);

            // 预填足够长的历史，使压缩能产生真实的"缩减"（changed()==true），
            // 否则 RunLoop 会因"压缩无缩减"而直接 break——那是小历史的正确行为，不是缺陷。
            String sid = session.id();
            for (int i = 0; i < 8; i++) {
                engine.getMessageStore().updateMessage(sid, userMsg(sid, "历史问题 " + i));
                engine.getMessageStore().updateMessage(sid, assistantMsg(sid, "历史回答 " + i));
            }

            List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            engine.runStreaming(session, agent, "hi", events::add);

            // 压缩恢复确实发生过（AUTO + overflow）
            boolean compactionFired = events.stream()
                .filter(e -> e instanceof AgentEvent.CompactionStart)
                .map(e -> (AgentEvent.CompactionStart) e)
                .anyMatch(AgentEvent.CompactionStart::overflow);
            assertTrue(compactionFired,
                () -> "overflow compaction should fire; events=" + abbrev(events));

            // 溢出错误消息的 error 字段已被填充（机制可达的关键证据）
            boolean sawOverflowError = engine.getMessages(sid).stream()
                .anyMatch(m -> m.error() != null && m.error().contains("context_length_exceeded"));
            assertTrue(sawOverflowError, "assistant message must carry the overflow error field");
        } finally {
            engine.shutdown();
        }
    }

    private static Message userMsg(String sid, String text) {
        return new Message(UUID.randomUUID().toString(), sid, com.aliyun.odps.agentic.model.Role.USER,
            List.of(new com.aliyun.odps.agentic.model.MessagePart.TextPart(text)));
    }

    private static Message assistantMsg(String sid, String text) {
        return new Message(UUID.randomUUID().toString(), sid, com.aliyun.odps.agentic.model.Role.ASSISTANT,
            null, null,
            List.of(new com.aliyun.odps.agentic.model.MessagePart.TextPart(text)),
            "test", null, "end-turn", null, null, null, null, Instant.now());
    }

    private static String abbrev(List<AgentEvent> events) {
        return events.stream().map(e -> e.getClass().getSimpleName()).toList().toString();
    }

    // ── ③ reasoning token 记账 ───────────────────────────────────────

    /** Usage 携带 reasoningTokens —— 合并后必须保留真值且不被四参构造归零。 */
    @Test
    void reasoningTokensSurviveExtraction() {
        StreamProcessor sp = new StreamProcessor(new com.aliyun.odps.agentic.tool.ToolRegistry());

        // 分两个 Usage 事件发送，验证合并路径也保留 reasoning（这正是被四参构造吃掉的地方）
        Usage merged = sp.extractUsage(List.of(
            new LLMEvent.Usage(new Usage(100, 0, 0, 0, 0)),
            new LLMEvent.Usage(new Usage(100, 50, 0, 0, 30))
        ));
        assertEquals(30, merged.reasoningTokens(), "reasoning tokens must survive the merge");
        assertEquals(50, merged.outputTokens());
    }

    // ── 可观测性（0.4.0 / P1-I）：重试与 doom-loop 不再黑屏/静默 ──

    /** 重试时必须发出 Retrying 事件——前端不再只看到一个卡住的画面。 */
    @Test
    void retryEmitsRetryingEvent() {
        AtomicInteger calls = new AtomicInteger();
        LLMClient flaky = new LLMClient() {
            @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
                int n = calls.incrementAndGet();
                if (n < 3) throw LlmApiException.fromHttpStatus(429, null, "rate limit");
                for (LLMEvent e : okTurn("ok")) c.accept(e);
            }
            @Override public boolean supports(String p) { return true; }
        };

        HarnessEngine engine = newEngine(flaky);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);
            List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            Message result = engine.runStreaming(session, agent, "hi", events::add);

            long retries = events.stream().filter(e -> e instanceof AgentEvent.Retrying).count();
            assertEquals(2, retries, () -> "two failed attempts should each emit a Retrying event; events=" + abbrev(events));
            assertEquals("ok", events.stream().filter(AgentEvent.TextDelta.class::isInstance)
                .map(AgentEvent.TextDelta.class::cast).map(AgentEvent.TextDelta::delta)
                .collect(java.util.stream.Collectors.joining()));
            assertEquals("ok", result.getTextContent());
            assertNull(result.error());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void failedPartialStreamDoesNotReplayDifferentTextOrTools() {
        for (LLMEvent partial : List.of(new LLMEvent.TextDelta("partial"),
                new LLMEvent.ReasoningStart("reasoning-1"),
                new LLMEvent.ToolCall("call-1", "read", "{}"))) {
            AtomicInteger calls = new AtomicInteger();
            LLMClient client = new LLMClient() {
                @Override public void stream(LlmRequest request, Consumer<LLMEvent> consumer) {
                    calls.incrementAndGet();
                    consumer.accept(partial);
                    throw LlmApiException.fromNetwork(new java.io.IOException("connection reset"));
                }
                @Override public boolean supports(String provider) { return true; }
            };
            HarnessEngine engine = newEngine(client);
            try {
                AgentDef agent = testAgent();
                List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
                Message result = engine.runStreaming(titledSession(agent), agent, "hi", events::add);
                assertEquals(1, calls.get(), partial.toString());
                assertEquals("error", result.finish());
                assertNotNull(result.error());
                assertEquals(0, events.stream().filter(AgentEvent.Retrying.class::isInstance).count());
                assertEquals(0, events.stream().filter(AgentEvent.ToolCallCompleted.class::isInstance).count());
            } finally {
                engine.shutdown();
            }
        }
    }

    /** doom-loop 必须发出 DoomLoopDetected 事件，且纠偏后仍重复才退出。 */
    @Test
    void doomLoopEmitsEventAndBreaksAfterNudge() {
        // 模型反复用相同参数调用同一工具 → 触发 doom-loop。
        LLMClient looping = new LLMClient() {
            @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
                c.accept(new LLMEvent.ToolCall("c1", "read", "{\"path\":\"/x\"}"));
                c.accept(new LLMEvent.Finish("tool-calls"));
            }
            @Override public boolean supports(String p) { return true; }
        };

        HarnessEngine engine = newEngine(looping);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);
            List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            engine.runStreaming(session, agent, "read the file", events::add);

            boolean doomDetected = events.stream().anyMatch(e -> e instanceof AgentEvent.DoomLoopDetected);
            assertTrue(doomDetected, () -> "doom loop must surface a DoomLoopDetected event; events=" + abbrev(events));
            // 纠偏提示应已注入（一条含纠偏文字的 user 消息）
            boolean nudged = engine.getMessages(session.id()).stream()
                .anyMatch(m -> m.role() == com.aliyun.odps.agentic.model.Role.USER
                    && m.getTextContent() != null && m.getTextContent().contains("repeating the same tool call"));
            assertTrue(nudged, "a corrective nudge message should be injected before giving up");
            assertFalse(engine.isRunning(session.id()));
        } finally {
            engine.shutdown();
        }
    }
}
