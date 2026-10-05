package com.aliyun.odps.agentic.operation;

import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

class RealtimeWebSocketTransportTest {
    @Test
    void assemblesTextFragmentsBeforeDispatch() {
        List<String> messages = new ArrayList<>();
        RealtimeWebSocketTransport transport = new RealtimeWebSocketTransport(
            new RealtimeWebSocketTransport.Events() {
                @Override public void onMessage(String json) { messages.add(json); }
                @Override public void onClosed(int code, String reason) {}
                @Override public void onError(Throwable error) {}
            });
        WebSocket socket = fakeSocket((List<String>) null);
        transport.onOpen(socket);
        transport.onText(socket, "{\"type\":", false);
        assertTrue(messages.isEmpty());
        transport.onText(socket, "\"session.updated\"}", true);
        assertEquals(List.of("{\"type\":\"session.updated\"}"), messages);
        transport.close("test complete");
    }

    @Test
    void failedSendIsCountedWithoutTearingDownTheSession() throws Exception {
        RealtimeWebSocketTransport transport = new RealtimeWebSocketTransport(
            new RealtimeWebSocketTransport.Events() {
                @Override public void onMessage(String json) {}
                @Override public void onClosed(int code, String reason) {}
                @Override public void onError(Throwable error) {}
            });
        List<String> sent = new CopyOnWriteArrayList<>();
        WebSocket socket = fakeSocket(sent);
        transport.onOpen(socket);
        try {
            assertTrue(transport.send("{}"));
            assertTrue(transport.send("{}"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (transport.droppedSends() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            assertEquals(2, transport.droppedSends());
            assertEquals(List.of("{}", "{}"), sent);
        } finally {
            transport.close("test complete");
        }
    }

    @Test
    void waitsForPreviousSendCompletionBeforeSendingTheNextFrame() throws Exception {
        RealtimeWebSocketTransport transport = transport();
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CompletableFuture<WebSocket> firstCompletion = new CompletableFuture<>();
        WebSocket socket = fakeSocket((json, ws) -> {
            sent.add(json);
            if (json.equals("output")) {
                firstStarted.countDown();
                return firstCompletion;
            }
            secondStarted.countDown();
            return CompletableFuture.completedFuture(ws);
        });
        transport.onOpen(socket);
        try {
            assertTrue(transport.send("output"));
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
            assertTrue(transport.send("create"));
            assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));
            firstCompletion.complete(socket);
            assertTrue(secondStarted.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("output", "create"), sent);
            assertEquals(0, transport.droppedSends());
        } finally {
            firstCompletion.complete(socket);
            transport.close("test complete");
        }
        assertFalse(transport.send("after close"));
    }

    @Test
    void audioBackpressurePreservesQueuedControlFrames() throws Exception {
        RealtimeWebSocketTransport transport = transport();
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch controlSent = new CountDownLatch(1);
        CompletableFuture<WebSocket> firstCompletion = new CompletableFuture<>();
        WebSocket socket = fakeSocket((json, ws) -> {
            sent.add(json);
            if (json.equals("first")) {
                firstStarted.countDown();
                return firstCompletion;
            }
            if (json.equals("cancel")) controlSent.countDown();
            return CompletableFuture.completedFuture(ws);
        });
        transport.onOpen(socket);
        try {
            assertTrue(transport.send("first"));
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
            assertTrue(transport.send("cancel"));
            for (int i = 0; i < 200; i++) {
                assertTrue(transport.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + i + "\"}"));
            }
            assertTrue(transport.droppedAudioFrames() > 0);
            firstCompletion.complete(socket);
            assertTrue(controlSent.await(3, TimeUnit.SECONDS));
            assertEquals("cancel", sent.get(1));
            assertEquals(0, transport.droppedSends());
        } finally {
            firstCompletion.complete(socket);
            transport.close("test complete");
        }
    }

    @Test
    void closeInterruptsPendingSendAndDropsQueuedFrames() throws Exception {
        RealtimeWebSocketTransport transport = transport();
        List<String> sent = new CopyOnWriteArrayList<>();
        CountDownLatch firstStarted = new CountDownLatch(1);
        AtomicReference<Thread> writer = new AtomicReference<>();
        CompletableFuture<WebSocket> pendingSend = new CompletableFuture<>();
        WebSocket socket = fakeSocket((json, ws) -> {
            sent.add(json);
            writer.set(Thread.currentThread());
            firstStarted.countDown();
            return pendingSend;
        });
        transport.onOpen(socket);
        try {
            assertTrue(transport.send("first"));
            assertTrue(firstStarted.await(3, TimeUnit.SECONDS));
            assertTrue(transport.send("queued"));
            transport.close("rotation");
            writer.get().join(3000);
            assertFalse(writer.get().isAlive(), "Closing must terminate a writer waiting for send completion");
            assertFalse(pendingSend.isDone(), "Closing must not depend on the provider completing the send");
            assertEquals(List.of("first"), sent);
            assertEquals(0, transport.outboxDepth());
            assertEquals(0, transport.droppedSends());
            assertFalse(transport.send("after close"));
        } finally {
            pendingSend.complete(socket);
            transport.close("test complete");
            if (writer.get() != null) writer.get().join(3000);
        }
    }

    private static RealtimeWebSocketTransport transport() {
        return new RealtimeWebSocketTransport(new RealtimeWebSocketTransport.Events() {
            @Override public void onMessage(String json) {}
            @Override public void onClosed(int code, String reason) {}
            @Override public void onError(Throwable error) {}
        });
    }

    private static WebSocket fakeSocket(List<String> sent) {
        return fakeSocket((json, socket) -> {
            if (sent != null) {
                sent.add(json);
                return CompletableFuture.failedFuture(new IllegalStateException("overlap"));
            }
            return CompletableFuture.completedFuture(socket);
        });
    }

    private static WebSocket fakeSocket(BiFunction<String, WebSocket, CompletableFuture<WebSocket>> sender) {
        return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
            new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                if (method.getName().equals("request")) return proxy;
                if (method.getName().equals("sendText")) {
                    return sender.apply((String) args[0], (WebSocket) proxy);
                }
                if (method.getName().equals("sendClose")) return CompletableFuture.completedFuture((WebSocket) proxy);
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
