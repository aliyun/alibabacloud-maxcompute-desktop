package com.aliyun.odps.agentic.operation;

import org.junit.jupiter.api.Test;

import java.net.http.WebSocket;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
        WebSocket socket = fakeSocket(null);
        transport.onOpen(socket);
        transport.onText(socket, "{\"type\":", false);
        assertTrue(messages.isEmpty());
        transport.onText(socket, "\"session.updated\"}", true);
        assertEquals(List.of("{\"type\":\"session.updated\"}"), messages);
    }

    @Test
    void overlappingSendDropsFrameAndKeepsSessionOpen() {
        RealtimeWebSocketTransport transport = new RealtimeWebSocketTransport(
            new RealtimeWebSocketTransport.Events() {
                @Override public void onMessage(String json) {}
                @Override public void onClosed(int code, String reason) {}
                @Override public void onError(Throwable error) {}
            });
        List<String> sent = new ArrayList<>();
        WebSocket socket = fakeSocket(sent);
        transport.onOpen(socket);
        assertFalse(transport.send("{}"));
        assertFalse(transport.send("{}"));
        assertEquals(List.of("{}", "{}"), sent);
    }

    private static WebSocket fakeSocket(List<String> sent) {
        return (WebSocket) Proxy.newProxyInstance(WebSocket.class.getClassLoader(),
            new Class<?>[]{WebSocket.class}, (proxy, method, args) -> {
                if (method.getName().equals("request")) return proxy;
                if (method.getName().equals("sendText")) {
                    if (sent != null) {
                        sent.add((String) args[0]);
                        throw new IllegalStateException("overlap");
                    }
                    return CompletableFuture.completedFuture((WebSocket) proxy);
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
