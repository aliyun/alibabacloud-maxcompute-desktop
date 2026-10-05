package com.aliyun.odps.agentic.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** SDK-owned duplex model transport with ordered sends and complete text frames. */
public final class RealtimeWebSocketTransport implements WebSocket.Listener {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketTransport.class);

    public interface Events {
        void onMessage(String json);
        void onClosed(int code, String reason);
        void onError(Throwable error);
    }

    private volatile WebSocket socket;
    private final StringBuilder partial = new StringBuilder();
    private final Events events;
    private final Object sendLock = new Object();
    private volatile long droppedSends;

    public RealtimeWebSocketTransport(Events events) {
        this.events = Objects.requireNonNull(events, "events");
    }

    public void connect(String url, String apiKey) {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
            .newWebSocketBuilder()
            .header("Authorization", "Bearer " + apiKey)
            .header("OpenAI-Beta", "realtime=v1")
            .buildAsync(URI.create(url), this)
            .orTimeout(20, TimeUnit.SECONDS)
            .whenComplete((ignored, error) -> {
                if (error != null) events.onError(error);
            })
            .join();
    }

    /** Drop overlapping or failed frames without tearing down the voice session. */
    public boolean send(String json) {
        WebSocket current = socket;
        if (current == null) return false;
        synchronized (sendLock) {
            try {
                current.sendText(json, true);
                return true;
            } catch (Exception error) {
                droppedSends++;
                log.warn("[realtime] upstream send dropped ({}): {}", droppedSends,
                    String.valueOf(error.getMessage()));
                return false;
            }
        }
    }

    public void close(String reason) {
        WebSocket current = socket;
        socket = null;
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (Exception ignored) {}
        }
    }

    @Override public void onOpen(WebSocket webSocket) {
        socket = webSocket;
        webSocket.request(Long.MAX_VALUE);
    }

    @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            String full = partial.toString();
            partial.setLength(0);
            try {
                events.onMessage(full);
            } catch (Exception error) {
                log.warn("[realtime] upstream message handler error", error);
            }
        }
        webSocket.request(1);
        return null;
    }

    @Override public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        events.onClosed(statusCode, reason);
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        events.onError(error);
    }
}
