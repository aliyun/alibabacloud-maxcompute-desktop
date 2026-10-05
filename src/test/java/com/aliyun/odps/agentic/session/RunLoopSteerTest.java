package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.llm.LlmRequest;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.model.SessionStatus;
import com.aliyun.odps.agentic.tool.ToolDef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * steering（运行中注入用户消息，对齐 OpenCode steer 语义）的回归测试。
 */
@Timeout(20)
class RunLoopSteerTest {

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

    /**
     * 创建带标题的会话 —— 跳过 RunLoop 首步的 TitleGenerator 异步 LLM 调用，
     * 否则它会消耗 EchoLlm 的调用序号/gate，让测试时序失去确定性。
     */
    private Session titledSession(AgentDef agent) {
        Instant now = Instant.now();
        return new Session(
            UUID.randomUUID().toString(), "test-title", SessionStatus.IDLE, List.of(),
            agent.getName(), Model.of("test", "test-model", new ModelLimit(100000, null, 4096)),
            0.0, null, now, now, agent.getPermissionRules()
        );
    }

    /** 每次 LLM 调用都回一句文本并 end-turn；请求体被记录用于断言上下文内容。 */
    private static class EchoLlm implements LLMClient {
        final AtomicInteger calls = new AtomicInteger();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final CountDownLatch firstCallEntered = new CountDownLatch(1);
        final ConcurrentLinkedQueue<CountDownLatch> gates = new ConcurrentLinkedQueue<>();

        @Override
        public void stream(LlmRequest request, Consumer<LLMEvent> c) {
            int n = calls.incrementAndGet();
            requests.add(String.valueOf(request.messages()));
            if (n == 1) {
                firstCallEntered.countDown();
                CountDownLatch gate = gates.peek();
                if (gate != null) {
                    try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                }
            }
            c.accept(new LLMEvent.TextStart("test-model"));
            c.accept(new LLMEvent.TextDelta("reply-" + n));
            c.accept(new LLMEvent.Finish("end-turn"));
            c.accept(new LLMEvent.TextEnd());
        }

        @Override
        public boolean supports(String providerId) { return true; }
    }

    @Test
    void steerDuringLlmCall_answeredInSameRun() throws Exception {
        EchoLlm llm = new EchoLlm();
        CountDownLatch gate = new CountDownLatch(1);
        llm.gates.add(gate);
        HarnessEngine engine = newEngine(llm);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);

            CompletableFuture<Message> run = engine.runAsync(session, agent, "第一条消息");
            assertTrue(llm.firstCallEntered.await(5, TimeUnit.SECONDS), "run should reach first LLM call");

            // LLM 调用进行中注入 —— 不打断、不新建运行
            assertTrue(engine.steer(session.id(), agent.getName(), "运行中的引导"));

            gate.countDown();
            run.get(10, TimeUnit.SECONDS);

            // 消息序列：user1 → asst1 → user2(steered) → asst2，同一次运行内完成
            List<Message> msgs = engine.getMessages(session.id());
            assertEquals(4, msgs.size(), () -> "messages: " + msgs);
            assertEquals(Role.USER, msgs.get(0).role());
            assertEquals(Role.ASSISTANT, msgs.get(1).role());
            assertEquals(Role.USER, msgs.get(2).role());
            assertEquals("运行中的引导", msgs.get(2).getTextContent());
            assertEquals(Role.ASSISTANT, msgs.get(3).role());

            // 第二次 LLM 调用的上下文必须包含引导消息
            assertEquals(2, llm.calls.get());
            assertTrue(llm.requests.get(1).contains("运行中的引导"),
                () -> "second request should contain steered text: " + llm.requests.get(1));
            assertFalse(engine.isRunning(session.id()));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void steerDuringWindDown_loopContinues() throws Exception {
        // 在 AfterLLMCall 事件（助手消息已落库、底部退出判断之前）注入，
        // 确定性复现「收尾窗口」——confirmExit 必须拦下这次退出。
        EchoLlm llm = new EchoLlm();
        HarnessEngine engine = newEngine(llm);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);
            AtomicBoolean steered = new AtomicBoolean(false);

            CompletableFuture<Message> run = engine.runAsyncStreaming(session, agent, "第一条消息", event -> {
                if (event instanceof AgentEvent.AfterLLMCall && steered.compareAndSet(false, true)) {
                    assertTrue(engine.steer(session.id(), agent.getName(), "收尾窗口的引导"));
                }
            });
            run.get(10, TimeUnit.SECONDS);

            List<Message> msgs = engine.getMessages(session.id());
            assertEquals(4, msgs.size(), () -> "messages: " + msgs);
            assertEquals("收尾窗口的引导", msgs.get(2).getTextContent());
            assertEquals(2, llm.calls.get());
            assertTrue(llm.requests.get(1).contains("收尾窗口的引导"));
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void steerAfterFinish_rejected() throws Exception {
        EchoLlm llm = new EchoLlm();
        HarnessEngine engine = newEngine(llm);
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);

            engine.run(session, agent, "第一条消息");
            assertFalse(engine.isRunning(session.id()));

            // 运行已结束：注入必须被拒绝，且不得落库（调用方应走正常 run）
            assertFalse(engine.steer(session.id(), agent.getName(), "迟到的消息"));
            assertEquals(2, engine.getMessages(session.id()).size());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void steerWithoutActiveRun_rejected() {
        EchoLlm llm = new EchoLlm();
        HarnessEngine engine = newEngine(llm);
        try {
            AgentDef agent = testAgent();
            Session session = engine.createSession(agent);
            assertFalse(engine.steer(session.id(), agent.getName(), "没有运行"));
            assertTrue(engine.getMessages(session.id()).isEmpty());
        } finally {
            engine.shutdown();
        }
    }
}
