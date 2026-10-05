package com.aliyun.odps.agentic.llm;

import java.util.Map;

/**
 * LLM 提供者 API 调用失败时抛出的非受检异常。
 *
 * <p>携带 HTTP 状态码、是否可重试、响应头与响应体，供运行循环
 * （{@code RunLoop}）据此进行重试判定与错误分类。之所以设计为
 * {@link RuntimeException}，是因为 {@link LLMClient#stream} 的签名不声明
 * 受检异常，而重试机制需要错误以异常形式穿透到运行循环。
 *
 * <p>语义约定：HTTP {@code >= 400}、网络/IO 中断、连接重置等都以本异常抛出；
 * 真正的业务级错误（如模型返回的 content_filter）仍走 {@code LLMEvent.ProviderError}
 * 事件通道，由上层决定如何呈现。
 */
public class LlmApiException extends RuntimeException {

    private final Integer statusCode;
    private final boolean retryable;
    private final Map<String, String> responseHeaders;
    private final String responseBody;

    /**
     * 使用完整错误上下文创建异常。
     *
     * @param message         错误消息
     * @param statusCode      HTTP 状态码（网络错误可为 {@code null}）
     * @param retryable       是否可重试
     * @param responseHeaders 响应头（可为 {@code null}）
     * @param responseBody    响应体（可为 {@code null}）
     * @param cause           原始异常（可为 {@code null}）
     */
    public LlmApiException(String message, Integer statusCode, boolean retryable,
                           Map<String, String> responseHeaders, String responseBody, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.responseHeaders = responseHeaders;
        this.responseBody = responseBody;
    }

    /**
     * 依据 HTTP 状态码创建异常，按状态码自动判定可重试性。
     *
     * <p>判定规则：{@code 429} 与 {@code 5xx} 可重试；其余 {@code 4xx} 不可重试。
     *
     * @param statusCode      HTTP 状态码
     * @param responseHeaders 响应头（可为 {@code null}）
     * @param responseBody    响应体（可为 {@code null}）
     * @return API 异常
     */
    public static LlmApiException fromHttpStatus(int statusCode, Map<String, String> responseHeaders,
                                                 String responseBody) {
        boolean retryable = statusCode == 429 || statusCode >= 500;
        String message = "HTTP " + statusCode + (responseBody != null && !responseBody.isBlank()
            ? ": " + responseBody : "");
        return new LlmApiException(message, statusCode, retryable, responseHeaders, responseBody, null);
    }

    /**
     * 依据网络/IO 故障创建异常（无 HTTP 状态码，视为可重试）。
     *
     * @param cause 原始网络异常
     * @return API 异常
     */
    public static LlmApiException fromNetwork(Throwable cause) {
        String message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return new LlmApiException(message, null, true, null, null, cause);
    }

    /** @return HTTP 状态码，网络错误时为 {@code null} */
    public Integer statusCode() {
        return statusCode;
    }

    /** @return 是否可重试 */
    public boolean retryable() {
        return retryable;
    }

    /** @return 响应头，可为 {@code null} */
    public Map<String, String> responseHeaders() {
        return responseHeaders;
    }

    /** @return 响应体，可为 {@code null} */
    public String responseBody() {
        return responseBody;
    }
}
