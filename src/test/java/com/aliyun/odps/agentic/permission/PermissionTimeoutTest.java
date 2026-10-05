package com.aliyun.odps.agentic.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for permission timeout behavior introduced in 0.4.0:
 * - SDK timeout throws PermissionTimeoutException (not false)
 * - Zero timeout disables SDK-side timer
 * - cancel(null) does not throw
 */
class PermissionTimeoutTest {

    @Test
    @Timeout(5)
    void permissionTimeoutThrowsException() {
        // askTimeoutMs=100ms, asker never completes → must throw PermissionTimeoutException
        PermissionEngine engine = new PermissionEngine(List.of());
        PermissionService service = new PermissionService(engine,
            req -> new CompletableFuture<>(), // never completes
            Duration.ofMillis(100));

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "rm -rf /", "Danger");

        PermissionTimeoutException ex = assertThrows(PermissionTimeoutException.class, () -> service.ask(req));
        assertTrue(ex.getMessage().contains("shell"));
        assertTrue(ex.getMessage().contains("100"));
        assertEquals("shell", ex.getToolName());
        assertEquals(100, ex.getTimeoutMs());
    }

    @Test
    @Timeout(5)
    void zeroTimeoutDisablesSdkTimer() throws Exception {
        // askTimeoutMs=0, asker completes after 200ms → must return true (no SDK timeout)
        PermissionEngine engine = new PermissionEngine(List.of());
        CountDownLatch started = new CountDownLatch(1);

        PermissionService service = new PermissionService(engine, req -> {
            CompletableFuture<PermissionReply> f = new CompletableFuture<>();
            started.countDown();
            // Complete after short delay
            Thread.startVirtualThread(() -> {
                try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                f.complete(PermissionReply.ONCE);
            });
            return f;
        }, Duration.ZERO); // zero = disable SDK timer

        PermissionRequest req = PermissionRequest.create("s1", "shell", "c1", "bash", "ls", "List");

        // Run ask in a separate thread so we can time it
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> service.ask(req));

        assertTrue(started.await(2, TimeUnit.SECONDS));
        // Should complete with true (not timeout)
        assertTrue(result.get(3, TimeUnit.SECONDS), "Zero timeout should disable SDK timer; ask should return true");
    }

    @Test
    @Timeout(5)
    void cancelWithNullSessionIdLogsWarning() {
        // cancel(null) must not throw; just log and return false
        // We can't easily test HarnessEngine.cancel() without a full engine setup,
        // but we can verify the method exists and behaves correctly via a minimal engine.
        // For this test we verify directly that calling cancel(null) on a real engine does not throw.
        var engine = com.aliyun.odps.agentic.HarnessEngine.builder()
            .llmClient(new com.aliyun.odps.agentic.llm.LLMClient() {
                @Override
                public void stream(com.aliyun.odps.agentic.llm.LlmRequest request,
                                   java.util.function.Consumer<com.aliyun.odps.agentic.llm.LLMEvent> handler) {}
                @Override
                public boolean supports(String providerId) { return true; }
            })
            .build();

        assertDoesNotThrow(() -> {
            boolean result = engine.cancel(null);
            assertFalse(result, "cancel(null) should return false");
        });

        engine.shutdown();
    }
}
