package com.aliyun.odps.agentic.operation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
    private record OutFrame(String json, boolean audio) {}
    private final LinkedBlockingQueue<OutFrame> outbox = new LinkedBlockingQueue<>(1024);
    private static final int AUDIO_BACKLOG_DROP_AT = 150;
    private final AtomicLong droppedSends = new AtomicLong();
    private final AtomicLong droppedAudioFrames = new AtomicLong();
    private volatile Thread writerThread;

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

    /** Accept frames in order; a single writer waits for each asynchronous send to finish. */
    public boolean send(String json) {
        Objects.requireNonNull(json, "json");
        synchronized (sendLock) {
            if (socket == null) return false;
            boolean audio = json.contains("\"input_audio_buffer.append\"");
            if (audio && outbox.size() >= AUDIO_BACKLOG_DROP_AT) {
                outbox.removeIf(OutFrame::audio);
                long dropped = droppedAudioFrames.incrementAndGet();
                if (dropped % 10 == 1) {
                    log.warn("[realtime] stale audio backlog dropped ({} events)", dropped);
                }
            }
            boolean accepted = outbox.offer(new OutFrame(json, audio));
            if (!accepted) log.warn("[realtime] upstream outbox full ({})", droppedSends.incrementAndGet());
            return accepted;
        }
    }

    public void close(String reason) {
        WebSocket current = stopWriter();
        if (current != null) {
            try {
                current.sendClose(WebSocket.NORMAL_CLOSURE, reason);
            } catch (Exception ignored) {}
        }
    }

    public long droppedSends() { return droppedSends.get(); }
    /** Number of stale audio backlog purge events, matching the Studio transport contract. */
    public long droppedAudioFrames() { return droppedAudioFrames.get(); }
    public int outboxDepth() { return outbox.size(); }

    private WebSocket stopWriter() {
        synchronized (sendLock) {
            WebSocket current = socket;
            socket = null;
            Thread writer = writerThread;
            writerThread = null;
            if (writer != null) writer.interrupt();
            outbox.clear();
            return current;
        }
    }

    private void writeFrames(WebSocket current) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                OutFrame frame = outbox.take();
                if (socket != current) break;
                try {
                    // Waiting must remain interruptible when close/rotation stops this writer.
                    current.sendText(frame.json(), true).get();
                } catch (InterruptedException closing) {
                    throw closing;
                } catch (Exception error) {
                    log.warn("[realtime] upstream send failed ({}): {}", droppedSends.incrementAndGet(),
                        String.valueOf(error.getMessage()));
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public void onOpen(WebSocket webSocket) {
        synchronized (sendLock) {
            socket = webSocket;
            writerThread = Thread.ofPlatform().daemon().name("agentic-realtime-writer")
                .start(() -> writeFrames(webSocket));
        }
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
        stopWriter();
        events.onClosed(statusCode, reason);
        return null;
    }

    @Override public void onError(WebSocket webSocket, Throwable error) {
        stopWriter();
        events.onError(error);
    }
}
