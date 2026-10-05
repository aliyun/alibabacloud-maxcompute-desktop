package com.aliyun.odps.agentic.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for PermissionService — async permission flow, cascading, auto-resolve,
 * event emission, and shutdown.
 */
class PermissionServiceTest {

    // -- Basic ask flow --

    @Test
    @Timeout(5)
    void askWithAllowRuleReturnsTrue() {
        PermissionEngine engine = new PermissionEngine(List.of(Rule.allow("file")));
        PermissionService service = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.ONCE));

        PermissionRequest req = PermissionRequest.create("s1", "read", "c1", "file", "/tmp/x", "Read file");
        assertTrue(service.ask(req));
    }

    @Test
    @Timeout(5)
    void askWithDenyRuleReturnsFalse() {
        PermissionEngine engine = new PermissionEngine(List.of(Rule.deny("bash")));
        PermissionService service = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.ONCE));

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "rm -rf", "Run command");
        assertFalse(service.ask(req));
    }

    @Test
    @Timeout(5)
    void askWithOnceReplyReturnsTrue() {
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.ONCE));

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List files");
        assertTrue(service.ask(req));
    }

    @Test
    @Timeout(5)
    void askWithRejectReplyReturnsFalse() {
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.REJECT));

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List files");
        assertFalse(service.ask(req));
    }

    // -- ALWAYS reply adds runtime rule --

    @Test
    @Timeout(5)
    void alwaysReplyAutoApprovesSubsequentRequests() {
        PermissionEngine engine = new PermissionEngine(List.of());
        int[] askCount = {0};
        PermissionService service = new PermissionService(engine, req -> {
            askCount[0]++;
            return CompletableFuture.completedFuture(PermissionReply.ALWAYS);
        });

        PermissionRequest req1 = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        assertTrue(service.ask(req1));
        assertEquals(1, askCount[0]);

        PermissionRequest req2 = PermissionRequest.create("s1", "shell", "c2", "bash", "ls", "List again");
        assertTrue(service.ask(req2));
        assertEquals(1, askCount[0], "Second request should be auto-approved by runtime rule");
    }

    // -- Reply directly --

    @Test
    @Timeout(5)
    void replyCompletesBlockedAsk() throws Exception {
        PermissionEngine engine = new PermissionEngine(List.of());
        CountDownLatch askCalled = new CountDownLatch(1);
        AtomicReference<String> capturedId = new AtomicReference<>();

        PermissionService service = new PermissionService(engine, req -> {
            capturedId.set(req.id());
            askCalled.countDown();
            return new CompletableFuture<>(); // never completes on its own
        });

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> service.ask(req));

        assertTrue(askCalled.await(2, TimeUnit.SECONDS));
        service.reply(capturedId.get(), PermissionReply.ONCE);

        assertTrue(result.get(2, TimeUnit.SECONDS));
    }

    @Test
    @Timeout(5)
    void replyWithUnknownIdDoesNotCrash() {
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.ONCE));

        assertDoesNotThrow(() -> service.reply("nonexistent-id", PermissionReply.ONCE));
    }

    // -- Cascade reject --

    @Test
    @Timeout(5)
    void rejectCascadesToAllPendingInSameSession() throws Exception {
        PermissionEngine engine = new PermissionEngine(List.of());
        List<CompletableFuture<PermissionReply>> neverComplete = new ArrayList<>();

        PermissionService service = new PermissionService(engine, req -> {
            CompletableFuture<PermissionReply> f = new CompletableFuture<>();
            neverComplete.add(f);
            return f;
        });

        PermissionRequest req1 = PermissionRequest.create("s1", "shell", "c1", "bash", "cmd1", "d1");
        PermissionRequest req2 = PermissionRequest.create("s1", "shell", "c2", "bash", "cmd2", "d2");

        CompletableFuture<Boolean> r1 = CompletableFuture.supplyAsync(() -> service.ask(req1));
        CompletableFuture<Boolean> r2 = CompletableFuture.supplyAsync(() -> service.ask(req2));

        Thread.sleep(200); // wait for both to register

        service.reply(req1.id(), PermissionReply.REJECT);

        assertFalse(r1.get(2, TimeUnit.SECONDS));
        assertFalse(r2.get(2, TimeUnit.SECONDS));
    }

    // -- List pending --

    @Test
    @Timeout(5)
    void listPendingReturnsAllPending() throws Exception {
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            new CompletableFuture<>());

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        CompletableFuture.runAsync(() -> service.ask(req));

        Thread.sleep(200);

        List<PermissionRequest> pending = service.listPending();
        assertEquals(1, pending.size());
        assertEquals(req.id(), pending.get(0).id());
    }

    @Test
    @Timeout(5)
    void listPendingBySessionFiltersCorrectly() throws Exception {
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            new CompletableFuture<>());

        PermissionRequest req1 = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        PermissionRequest req2 = PermissionRequest.create("s2", "shell", "c2", "bash", "ls", "List");

        CompletableFuture.runAsync(() -> service.ask(req1));
        CompletableFuture.runAsync(() -> service.ask(req2));

        Thread.sleep(200);

        assertEquals(1, service.listPending("s1").size());
        assertEquals(1, service.listPending("s2").size());
        assertEquals(0, service.listPending("s3").size());
    }

    // -- Shutdown --

    @Test
    @Timeout(5)
    void shutdownRejectsAllPending() throws Exception {
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            new CompletableFuture<>());

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> service.ask(req));

        Thread.sleep(200);
        service.shutdown();

        assertFalse(result.get(2, TimeUnit.SECONDS));
    }

    // -- Event listener --

    @Test
    @Timeout(5)
    void eventListenerReceivesAskedAndRepliedEvents() {
        PermissionEngine engine = new PermissionEngine(List.of());
        List<PermissionService.PermissionEvent> events = new ArrayList<>();

        // 询问器返回一个**未完成**的 future —— 也就是真有人要看这张审批卡。只有这种情况
        // 才该广播 Asked / Replied（见下一个用例：同步放行不广播）。
        CompletableFuture<PermissionReply> answer = new CompletableFuture<>();
        PermissionService service = new PermissionService(engine, req -> answer);
        service.setEventListener(events::add);

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        new Thread(() -> {
            // 等 ask() 真正把请求挂起后再回答，模拟人点按钮。
            while (service.listPending().isEmpty()) {
                Thread.onSpinWait();
            }
            answer.complete(PermissionReply.ONCE);
        }).start();
        service.ask(req);

        assertTrue(events.stream().anyMatch(e -> e instanceof PermissionService.PermissionEvent.Asked));
        assertTrue(events.stream().anyMatch(e -> e instanceof PermissionService.PermissionEvent.Replied));
    }

    @Test
    @Timeout(5)
    void synchronouslyApprovedAskEmitsNoEvents() {
        // 询问器同步放行（CLI 自动允许、宿主的会话级自动批准）返回的是一个**已完成**的
        // future —— 这次询问从来没有人需要看。此前 Asked 无条件先于 askAsync 发出，宿主照着
        // 它建审批卡，于是屏幕上出现一张没人要求、也没人来关的幽灵卡：
        // MaxQuery 侧实测顺序是 tool_call_executing（清 pending）→ tool_call_pending（又立起来），
        // 而 tool_call_executed 只清 executing —— 卡就一直挂在那里。
        //
        // 契约因此收紧：只有真正挂起等人的询问才广播事件。审计需求请走宿主自己的
        // 工具调用事件（BeforeToolCall / AfterToolCall），它们对自动放行的调用同样齐全。
        PermissionEngine engine = new PermissionEngine(List.of());
        List<PermissionService.PermissionEvent> events = new ArrayList<>();

        PermissionService service = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.ONCE));
        service.setEventListener(events::add);

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        assertTrue(service.ask(req));
        assertTrue(events.isEmpty(),
            "auto-approved ask must not surface an approval card: " + events);
    }

    @Test
    @Timeout(5)
    void noEventListenerDoesNotCrash() {
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.ONCE));

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        assertDoesNotThrow(() -> service.ask(req));
    }

    // -- Fail-closed（0.4.0）：asker 异常/超时不得让 run 死锁 --

    @Test
    @Timeout(5)
    void exceptionallyCompletedAskerFailsClosed() {
        // 宿主 asker 的 future 异常完成（内部出错、宿主自己的超时策略触发等）。
        // 旧实现只挂 thenAccept，异常路径不回调，future.get() 永久悬挂 → 整个 run 死锁。
        // whenComplete 覆盖异常路径，fail-closed 为 REJECT。
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req -> {
            CompletableFuture<PermissionReply> f = new CompletableFuture<>();
            f.completeExceptionally(new RuntimeException("host asker blew up"));
            return f;
        });

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "rm -rf /", "Danger");
        assertFalse(service.ask(req), "exceptional asker must fail closed to deny, not deadlock");
    }

    @Test
    @Timeout(5)
    void askTimeoutThrowsPermissionTimeoutException() {
        // 宿主 asker 永不回复。配置短超时后，ask() 必须抛出 PermissionTimeoutException
        // （0.4.0 改为异常而非返回 false，让 RunLoop 能区分"超时终止"和"用户拒绝"）。
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            new CompletableFuture<>(), java.time.Duration.ofMillis(200));

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        long start = System.nanoTime();
        assertThrows(PermissionTimeoutException.class, () -> service.ask(req));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 4000, "ask must throw within the timeout, took " + elapsedMs + "ms");
    }

    @Test
    @Timeout(5)
    void defaultConstructorUsesFiveMinuteTimeout() {
        // 默认构造给 5 分钟超时——不是无限悬挂。这里只验证它不会立即可用于永不回复的 asker 之外的路径。
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine, req ->
            CompletableFuture.completedFuture(PermissionReply.ONCE));
        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");
        assertTrue(service.ask(req));
    }

    // -- Disabled engine --

    @Test
    @Timeout(5)
    void disabledEngineAlwaysAllows() {
        PermissionEngine engine = new PermissionEngine(List.of(), true);
        int[] askCount = {0};
        PermissionService service = new PermissionService(engine, req -> {
            askCount[0]++;
            return CompletableFuture.completedFuture(PermissionReply.ONCE);
        });

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "rm -rf /", "Danger");
        assertTrue(service.ask(req));
        assertEquals(0, askCount[0], "Disabled engine should not delegate to asker");
    }
}
