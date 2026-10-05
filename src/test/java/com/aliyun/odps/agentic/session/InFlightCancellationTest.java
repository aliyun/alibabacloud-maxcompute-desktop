package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.llm.LlmRequest;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 在途流可取消（0.4.0 / P1-E）的端到端测试。
 *
 * <p>此前 {@code cancel()} 只置协作标志，阻塞中的 {@code HttpClient.send} / 流式读取
 * 无法被中断，用户点停止后请求仍烧 token 直到提供者自然结束。现在 cancel 会中断运行
 * 线程，让在途调用立刻抛出中断而收尾。
 */
@Timeout(20)
class InFlightCancellationTest {

    private AgentDef testAgent() {
        return new AgentDef() {
            @Override public String getName() { return "test"; }
            @Override public String getSystemPrompt(Function<String, String> mp) { return "You are test."; }
            @Override public List<ToolDef> getTools() { return List.of(); }
            @Override public int getMaxSteps() { return 10; }
        };
    }

    private Session titledSession(AgentDef agent) {
        Instant now = Instant.now();
        return new Session(
            UUID.randomUUID().toString(), "t", SessionStatus.IDLE, List.of(),
            agent.getName(), Model.of("test", "test-model", new ModelLimit(100000, null, 4096)),
            0.0, null, now, now, agent.getPermissionRules()
        );
    }

    /** 模拟一个"挂起"的提供者：stream() 阻塞在一个可中断的等待上，永不自然返回。 */
    private static class HangingLlm implements LLMClient {
        final CountDownLatch entered = new CountDownLatch(1);
        @Override public void stream(LlmRequest req, Consumer<LLMEvent> c) {
            entered.countDown();
            try {
                // 模拟半开连接：阻塞直到被中断
                Thread.sleep(60_000);
            } catch (InterruptedException ie) {
                // 运行线程被 cancel() 中断 —— 重新置位并抛错，让 RunLoop 识别为取消
                Thread.currentThread().interrupt();
                throw new RuntimeException("interrupted mid-stream", ie);
            }
            c.accept(new LLMEvent.TextDelta("should never arrive"));
            c.accept(new LLMEvent.Finish("end-turn"));
        }
        @Override public boolean supports(String p) { return true; }
    }

    @Test
    void cancelInterruptsInFlightBlockingCall() throws Exception {
        HangingLlm llm = new HangingLlm();
        HarnessEngine engine = HarnessEngine.builder()
            .workDir(Path.of("."))
            .llmClient(llm)
            .model(Model.of("test", "test-model", new ModelLimit(100000, null, 4096)))
            .build();
        try {
            AgentDef agent = testAgent();
            Session session = titledSession(agent);

            CompletableFuture<Message> run = engine.runAsync(session, agent, "hi");
            assertTrue(llm.entered.await(5, TimeUnit.SECONDS), "run should reach the LLM call");

            long start = System.nanoTime();
            assertTrue(engine.cancel(session.id()), "an active run should be cancellable");
            Message result = run.get(5, TimeUnit.SECONDS); // 若 cancel 无效，这里会等到 60s 超时
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs < 5000, "cancelled run must return promptly, took " + elapsedMs + "ms");
            assertNotNull(result, "cancelled run still returns a terminal message");
            assertFalse(engine.isRunning(session.id()), "run should be deregistered after cancel");
        } finally {
            engine.shutdown();
        }
    }
}
