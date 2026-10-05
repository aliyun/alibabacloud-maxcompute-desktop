package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 基于 SSE 的 LLM 客户端，通过 Server-Sent Events 协议连接 LLM 提供者。
 *
 * <p>核心流程：构建 HTTP 请求、发送至提供者 API、解析 SSE 流、
 * 通过转换器将提供者事件转换为统一的 {@link LLMEvent}。
 */
public class SseLlmClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(SseLlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient;
    private final Map<String, ProviderTransform> transforms = new HashMap<>();
    private final Map<String, String> apiKeys = new HashMap<>();

    public SseLlmClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    @Override
    public boolean supports(String providerId) {
        return transforms.containsKey(providerId) || "anthropic".equals(providerId);
    }

    /**
     * 注册提供者的协议转换器。
     *
     * @param providerId 提供者标识
     * @param transform  转换器实例
     */
    public void registerTransform(String providerId, ProviderTransform transform) {
        transforms.put(providerId, transform);
    }

    /**
     * 注册提供者的 API Key。
     *
     * @param providerId 提供者标识
     * @param apiKey     API Key
     */
    public void registerApiKey(String providerId, String apiKey) {
        apiKeys.put(providerId, apiKey);
    }

    /**
     * 以流式方式发起聊天补全请求。
     *
     * <p>当 {@link Model} 携带 {@link Route} 时，将直接使用路由的鉴权管线构建请求。
     *
     * <p><b>错误语义（自 0.4.0 起）：</b>HTTP {@code >= 400} 与网络/IO 故障会以
     * {@link LlmApiException} 抛出，交由运行循环进行重试判定；不再静默吞成
     * {@code ProviderError} 事件。这是重试机制可达的前提。
     *
     * @throws LlmApiException 当请求返回 HTTP {@code >= 400} 或发生网络/IO 故障时
     */
    @Override
    public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
        Route route = request.model().route();
        ProviderTransform transform;
        HttpRequest httpRequest;

        if (route != null) {
            transform = route.protocol();
            httpRequest = buildRequest(() -> route.buildAuthenticatedRequest(request));
        } else {
            transform = transforms.get(request.model().providerId());
            if (transform == null) {
                transform = new AnthropicTransform();
            }
            final ProviderTransform t = transform;
            httpRequest = buildRequest(() -> t.buildHttpRequest(request, apiKeys));
        }

        // 添加会话标识头以支持代理缓存路由亲和性。
        // 注意：重建请求时必须同时复制超时设置，否则该路径会丢失请求级超时。
        if (request.sessionId() != null) {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(httpRequest.uri())
                .timeout(httpRequest.timeout().orElse(REQUEST_TIMEOUT))
                .method(httpRequest.method(), httpRequest.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
            httpRequest.headers().map().forEach((name, values) ->
                values.forEach(value -> builder.header(name, value)));
            builder.header("X-Session-Id", request.sessionId());
            httpRequest = builder.build();
        } else if (httpRequest.timeout().isEmpty()) {
            // 转换器未设置超时时补上请求级超时，避免半开连接无限期挂住。
            httpRequest = withTimeout(httpRequest, REQUEST_TIMEOUT);
        }

        // 发送并处理 SSE 流
        final HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(
                httpRequest,
                HttpResponse.BodyHandlers.ofLines()
            );
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("LLM request network failure", e);
            throw LlmApiException.fromNetwork(e);
        }

        if (response.statusCode() >= 400) {
            String errorBody;
            try (Stream<String> body = response.body()) {
                errorBody = body.collect(java.util.stream.Collectors.joining("\n"));
            }
            log.error("LLM API error: HTTP {} — {}", response.statusCode(), errorBody);
            throw LlmApiException.fromHttpStatus(
                response.statusCode(),
                flattenHeaders(response.headers().map()),
                errorBody
            );
        }

        processSseStream(response.body(), transform, eventConsumer);
    }

    /**
     * 流式消费被线程中断（取消）时抛出，用于从 {@code forEach} 内部跳出。
     * 运行循环会把它识别为取消而非可重试故障。
     */
    static final class StreamAbortedException extends RuntimeException {
        StreamAbortedException() {
            super("SSE stream aborted");
        }
    }

    /**
     * 构建 HTTP 请求，将转换器抛出的受检异常包装为不可重试的 {@link LlmApiException}。
     * 请求构造失败通常是鉴权/配置错误，不应进入重试。
     */
    private static HttpRequest buildRequest(RequestBuilder builder) {
        try {
            return builder.build();
        } catch (LlmApiException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmApiException(
                "Failed to build LLM request: " + e.getMessage(), null, false, null, null, e);
        }
    }

    /** 请求构建函数式接口，允许抛出受检异常。 */
    @FunctionalInterface
    private interface RequestBuilder {
        HttpRequest build() throws Exception;
    }

    /**
     * 为请求补充请求级超时（保留原有 uri/method/headers/body）。
     */
    private static HttpRequest withTimeout(HttpRequest request, Duration timeout) {        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(request.uri())
            .timeout(timeout)
            .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        request.headers().map().forEach((name, values) ->
            values.forEach(value -> builder.header(name, value)));
        return builder.build();
    }

    /**
     * 将响应头映射展平为单值映射（取每个键的首个值）。
     */
    private static Map<String, String> flattenHeaders(Map<String, List<String>> headers) {
        Map<String, String> flat = new LinkedHashMap<>();
        headers.forEach((name, values) -> {
            if (name != null && values != null && !values.isEmpty()) {
                flat.put(name.toLowerCase(Locale.ROOT), values.getFirst());
            }
        });
        return flat;
    }

    /**
     * 处理 SSE 事件流，将每个事件通过转换器转换后推送至消费者。
     *
     * <p>解析遵循 SSE 规范：容忍 {@code data:} 后无空格、多行 {@code data} 拼接、
     * {@code event:}/{@code id:}/{@code retry:} 字段，以及以 {@code :} 开头的注释/心跳行。
     * 流在结束时显式关闭，避免连接泄漏。
     */
    private void processSseStream(Stream<String> lines, ProviderTransform transform,
                                   Consumer<LLMEvent> eventConsumer) {
        String[] currentEvent = {""};
        StringBuilder dataBuffer = new StringBuilder();

        try (lines) {
            lines.forEach(line -> {
                // 响应线程中断（cancel 触发）——立刻停止消费，不再处理后续分片。
                if (Thread.currentThread().isInterrupted()) {
                    throw new StreamAbortedException();
                }
                if (line == null || line.isEmpty()) {
                    // 空行表示一个事件分派的边界
                    if (dataBuffer.length() > 0) {
                        emitBufferedEvent(transform, eventConsumer, currentEvent, dataBuffer);
                    }
                    return;
                }
                if (line.startsWith(":")) {
                    // SSE 注释或心跳，忽略
                    return;
                }
                if (line.startsWith("event:")) {
                    currentEvent[0] = stripLeadingSpace(line.substring(6));
                    return;
                }
                if (line.startsWith("data:")) {
                    String data = stripLeadingSpace(line.substring(5));
                    if (dataBuffer.length() > 0) {
                        dataBuffer.append('\n');
                    }
                    dataBuffer.append(data);
                    return;
                }
                if (line.startsWith("id:") || line.startsWith("retry:")) {
                    // 事件标识与重连退避——当前实现不消费，但需识别以避免误判为数据
                    return;
                }
                // 不含冒号的非空行按 SSE 规范视为字段名为整行、值为空；忽略。
            });
        }

        if (dataBuffer.length() > 0) {
            emitBufferedEvent(transform, eventConsumer, currentEvent, dataBuffer);
        }
     }

    /**
     * 去掉 SSE 字段值前的单个可选空格。
     */
    private static String stripLeadingSpace(String value) {
        if (value != null && value.startsWith(" ")) {
            return value.substring(1);
        }
        return value;
    }

    /**
     * 将缓冲区中的事件数据解析并通过转换器转换后推送。
     */
    private void emitBufferedEvent(ProviderTransform transform,
                                   Consumer<LLMEvent> eventConsumer,
                                   String[] currentEvent,
                                   StringBuilder dataBuffer) {
        String rawData = dataBuffer.toString();
        dataBuffer.setLength(0);
        String savedEvent = currentEvent[0];
        currentEvent[0] = "";

        if ("[DONE]".equals(rawData.trim())) {
            return;
        }

        try {
            JsonNode json = MAPPER.readTree(rawData);

            if (savedEvent.isEmpty() && json.has("type")) {
                savedEvent = json.get("type").asText("");
            }

            List<LLMEvent> events = transform.transformSseEvent(savedEvent, json);
            for (LLMEvent event : events) {
                eventConsumer.accept(event);
            }
        } catch (Exception e) {
            log.warn("Failed to parse SSE event: {}", e.getMessage());
        }
    }
}
