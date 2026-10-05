package com.aliyun.odps.agentic.llm.transport;

import com.aliyun.odps.agentic.llm.Auth;

import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * WebSocket JSON 传输层 — 基于 JSON-over-WebSocket 的实时 API 传输。
 *
 * <p>将请求体序列化为 JSON 消息通过 WebSocket 发送，
 * 并提供帧迭代器用于逐帧接收响应。
 *
 * <p>使用示例：
 * <pre>{@code
 * var transport = WebSocketTransport.json(body -> body, msg -> jsonMapper.writeValueAsString(msg));
 * var prepared = transport.prepare(url, headers, body);
 * var frames = transport.frames(prepared, executor);
 * }</pre>
 */
public final class WebSocketTransport {

    private WebSocketTransport() {}

    /** 传输标识符：{@code "websocket-json"}。 */
    public static final String ID = "websocket-json";

    // ── 预备请求 ────────────────────────────────────────────────────

    /**
     * 已预备的 WebSocket 请求。
     *
     * @param url     WebSocket URL
     * @param headers 请求头
     * @param message 初始消息（已编码的 JSON 字符串）
     */
    public record JsonPrepared(
        String url,
        Map<String, String> headers,
        String message
    ) {}

    // ── JSON 传输接口 ───────────────────────────────────────────────

    /**
     * JSON-over-WebSocket 传输接口。
     *
     * @param <Body>    请求体类型
     * @param <Message> WebSocket 消息类型
     */
    public interface JsonTransport<Body, Message> {
        /** 传输标识符。 */
        String id();

        /**
         * 预备 WebSocket 请求：解析 URL、请求头和初始消息。
         *
         * @param url     目标 URL
         * @param headers 请求头
         * @param body    请求体
         * @return 已预备的请求
         */
        JsonPrepared prepare(String url, Map<String, String> headers, Body body);

        /**
         * 打开 WebSocket，发送初始消息，返回帧迭代器。
         *
         * @param prepared 已预备的请求
         * @param executor WebSocket 执行器
         * @return 响应帧的迭代器
         */
        Iterator<String> frames(JsonPrepared prepared, WebSocketExecutor executor);
    }

    // ── 工厂方法 ────────────────────────────────────────────────────

    /**
     * 创建 JSON-over-WebSocket 传输实例。
     *
     * @param toMessage     将请求体转换为消息类型的函数
     * @param encodeMessage 将消息编码为 JSON 字符串的函数
     * @param <Body>        请求体类型
     * @param <Message>     WebSocket 消息类型
     * @return 传输实例
     */
    public static <Body, Message> JsonTransport<Body, Message> json(
            Function<Body, Message> toMessage,
            Function<Message, String> encodeMessage) {
        return new JsonTransport<>() {
            @Override
            public String id() {
                return ID;
            }

            @Override
            public JsonPrepared prepare(String url, Map<String, String> headers, Body body) {
                String wsUrl = WebSocketExecutor.toWebSocketUrl(url);
                Message msg = toMessage.apply(body);
                String encoded = encodeMessage.apply(msg);
                return new JsonPrepared(wsUrl, headers, encoded);
            }

            @Override
            public Iterator<String> frames(JsonPrepared prepared, WebSocketExecutor executor) {
                var request = new WebSocketExecutor.WebSocketRequest(prepared.url(), prepared.headers());
                var connection = executor.open(request);

                try {
                    connection.sendText(prepared.message());
                } catch (Exception e) {
                    connection.close();
                    throw e;
                }

                return new Iterator<>() {
                    private String next = null;
                    private boolean done = false;

                    @Override
                    public boolean hasNext() {
                        if (done) return false;
                        if (next != null) return true;
                        try {
                            next = connection.receiveText(30, TimeUnit.SECONDS);
                            if (next == null) {
                                done = true;
                                connection.close();
                                return false;
                            }
                            return true;
                        } catch (WebSocketExecutor.WebSocketTransportException e) {
                            done = true;
                            connection.close();
                            throw e;
                        }
                    }

                    @Override
                    public String next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        String result = next;
                        next = null;
                        return result;
                    }
                };
            }
        };
    }
}
