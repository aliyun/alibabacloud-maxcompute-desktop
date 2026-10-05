package com.aliyun.odps.agentic.llm.transport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 执行器 — 负责打开和管理 WebSocket 连接。
 *
 * <p>提供连接的打开、消息发送与接收、以及 URL 协议转换等功能，
 * 用于支持实时 API 的 WebSocket 传输层。
 */
public final class WebSocketExecutor {

    private final HttpClient httpClient;

    public WebSocketExecutor() {
        this.httpClient = HttpClient.newBuilder()
            .build();
    }

    public WebSocketExecutor(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    // ── WebSocket 请求 ──────────────────────────────────────────────

    /**
     * WebSocket 连接请求。
     *
     * @param url     连接 URL
     * @param headers 请求头
     */
    public record WebSocketRequest(
        String url,
        Map<String, String> headers
    ) {}

    // ── WebSocket 连接 ──────────────────────────────────────────────

    /**
     * 已打开的 WebSocket 连接接口。
     */
    public interface WebSocketConnection {
        /**
         * 发送文本消息。
         *
         * @param message 待发送的消息
         * @throws WebSocketTransportException 发送失败时抛出
         */
        void sendText(String message) throws WebSocketTransportException;

        /**
         * 轮询下一条消息（文本或二进制解码为字符串）。
         * 在超时内无消息可用时返回 null。
         *
         * @param timeout 超时时长
         * @param unit    时间单位
         * @return 收到的文本消息，超时返回 null
         * @throws WebSocketTransportException 接收失败时抛出
         */
        String receiveText(long timeout, TimeUnit unit) throws WebSocketTransportException;

        /**
         * 检查连接是否已结束（正常关闭或发生错误）。
         */
        boolean isEnded();

        /**
         * 获取连接结束时的错误，无错误返回 null。
         */
        WebSocketTransportException getError();

        /**
         * 关闭连接。
         */
        void close();
    }

    // ── 异常 ────────────────────────────────────────────────────────

    /**
     * WebSocket 传输异常。
     */
    public static class WebSocketTransportException extends RuntimeException {
        private final String method;
        private final String url;
        private final String kind;

        public WebSocketTransportException(String method, String message, String url, String kind) {
            super(message);
            this.method = method;
            this.url = url;
            this.kind = kind;
        }

        /** 触发异常的方法名。 */
        public String method() { return method; }
        /** 相关的 URL。 */
        public String url() { return url; }
        /** 错误类别。 */
        public String kind() { return kind; }
    }

    // ── 打开连接 ────────────────────────────────────────────────────

    /**
     * 打开一个 WebSocket 连接。
     *
     * @param request 连接请求（包含 URL 和请求头）
     * @return 已打开的 WebSocket 连接
     * @throws WebSocketTransportException 连接失败时抛出
     */
    public WebSocketConnection open(WebSocketRequest request) throws WebSocketTransportException {
        String wsUrl = toWebSocketUrl(request.url());
        var messageQueue = new LinkedBlockingQueue<Object>(128);
        // 队列中的哨兵值
        var END_MARKER = new Object();

        try {
            WebSocket.Builder builder = httpClient.newWebSocketBuilder();
            if (request.headers() != null) {
                for (var entry : request.headers().entrySet()) {
                    builder.header(entry.getKey(), entry.getValue());
                }
            }

            CompletableFuture<WebSocket> wsFuture = builder.buildAsync(
                URI.create(wsUrl),
                new WebSocket.Listener() {
                    private final StringBuilder textAccumulator = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        textAccumulator.append(data);
                        if (last) {
                            messageQueue.offer(textAccumulator.toString());
                            textAccumulator.setLength(0);
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        messageQueue.offer(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        if (statusCode == 1000 || statusCode == 1005) {
                            messageQueue.offer(END_MARKER);
                        } else {
                            messageQueue.offer(new WebSocketTransportException(
                                "message",
                                "WebSocket closed with code " + statusCode,
                                request.url(), "close"
                            ));
                        }
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        messageQueue.offer(new WebSocketTransportException(
                            "message",
                            "WebSocket error: " + error.getMessage(),
                            request.url(), "message"
                        ));
                    }
                }
            );

            WebSocket ws = wsFuture.join();

            return new WebSocketConnection() {
                private volatile boolean ended = false;
                private volatile WebSocketTransportException error = null;

                @Override
                public void sendText(String message) throws WebSocketTransportException {
                    try {
                        ws.sendText(message, true).join();
                    } catch (Exception e) {
                        throw new WebSocketTransportException(
                            "sendText",
                            e.getMessage() != null ? e.getMessage() : "Failed to send WebSocket message",
                            request.url(), "write"
                        );
                    }
                }

                @Override
                public String receiveText(long timeout, TimeUnit unit) throws WebSocketTransportException {
                    if (ended) return null;
                    try {
                        Object item = messageQueue.poll(timeout, unit);
                        if (item == null) return null;
                        if (item == END_MARKER) {
                            ended = true;
                            return null;
                        }
                        if (item instanceof WebSocketTransportException ex) {
                            ended = true;
                            error = ex;
                            throw ex;
                        }
                        return (String) item;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }

                @Override
                public boolean isEnded() {
                    return ended;
                }

                @Override
                public WebSocketTransportException getError() {
                    return error;
                }

                @Override
                public void close() {
                    ended = true;
                    try {
                        if (!ws.isOutputClosed()) {
                            ws.sendClose(1000, "").join();
                        }
                    } catch (Exception ignored) {
                        // 尽力关闭
                    }
                }
            };

        } catch (WebSocketTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new WebSocketTransportException(
                "open",
                e.getMessage() != null ? e.getMessage() : "Failed to construct WebSocket",
                request.url(), "open"
            );
        }
    }

    // ── URL 转换 ────────────────────────────────────────────────────

    /**
     * 将 HTTP(S) URL 转换为 WebSocket URL（ws/wss）。
     *
     * @param httpUrl HTTP 或 HTTPS URL
     * @return 对应的 WebSocket URL
     * @throws WebSocketTransportException URL 格式无效或协议不支持时抛出
     */
    static String toWebSocketUrl(String httpUrl) {
        try {
            URI uri = URI.create(httpUrl);
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme)) {
                return httpUrl.replaceFirst("(?i)^https", "wss");
            }
            if ("http".equalsIgnoreCase(scheme)) {
                return httpUrl.replaceFirst("(?i)^http", "ws");
            }
            if ("wss".equalsIgnoreCase(scheme) || "ws".equalsIgnoreCase(scheme)) {
                return httpUrl; // 已经是 WebSocket URL
            }
            throw new WebSocketTransportException(
                "prepare",
                "Unsupported WebSocket URL protocol " + scheme,
                httpUrl, "websocket"
            );
        } catch (WebSocketTransportException e) {
            throw e;
        } catch (Exception e) {
            throw new WebSocketTransportException(
                "prepare",
                "Invalid WebSocket URL: " + e.getMessage(),
                httpUrl, "websocket"
            );
        }
    }

    /**
     * 将消息解码为文本字符串。
     *
     * @param message 原始消息（{@link String} 或 {@code byte[]}）
     * @return 文本字符串
     */
    public static String messageText(Object message) {
        if (message instanceof String s) return s;
        if (message instanceof byte[] bytes) return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        return message.toString();
    }
}
