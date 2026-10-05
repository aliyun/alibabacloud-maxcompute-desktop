package com.aliyun.odps.agentic.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * LLM API 调用重试逻辑。
 *
 * <p>实现指数退避策略，并支持解析 {@code retry-after-ms} / {@code retry-after} 响应头。
 */
public class RetryLogic {

    private static final Logger log = LoggerFactory.getLogger(RetryLogic.class);

    /** 初始重试延迟（毫秒）。 */
    public static final int RETRY_INITIAL_DELAY = 2000;

    /** 退避因子。 */
    public static final int RETRY_BACKOFF_FACTOR = 2;

    /** 无重试头时的最大延迟（毫秒）。 */
    public static final int RETRY_MAX_DELAY_NO_HEADERS = 30_000;

    /** 全局最大延迟上限。 */
    public static final int RETRY_MAX_DELAY = Integer.MAX_VALUE;

    /** 默认最大重试次数。 */
    public static final int DEFAULT_MAX_RETRIES = 3;

    /**
     * 重试原因分类。
     */
    public enum RetryReason {
        /** 免费配额耗尽。 */
        FREE_TIER_LIMIT,
        /** 账号级别限流。 */
        ACCOUNT_RATE_LIMIT,
        /** 服务端错误。 */
        SERVER_ERROR,
        /** 请求限流。 */
        RATE_LIMITED,
        /** 未知原因。 */
        UNKNOWN
    }

    /**
     * 可重试判定结果。
     *
     * @param message 描述消息
     * @param action 建议动作
     */
    public record Retryable(
        String message,
        RetryAction action
    ) {}

    /**
     * 重试建议动作。
     *
     * @param reason 原因分类
     * @param provider 提供者名称
     * @param title 显示标题
     * @param message 显示消息
     * @param label 操作标签
     * @param link 相关链接
     */
    public record RetryAction(
        RetryReason reason,
        String provider,
        String title,
        String message,
        String label,
        String link
    ) {}

    /**
     * API 错误信息。
     *
     * @param message 错误消息
     * @param statusCode HTTP 状态码
     * @param retryable 是否可重试
     * @param responseHeaders 响应头
     * @param responseBody 响应体
     */
    public record ApiError(
        String message,
        Integer statusCode,
        boolean retryable,
        Map<String, String> responseHeaders,
        String responseBody
    ) {}

    /**
     * 计算第 N 次重试的等待延迟。
     *
     * <p>优先使用 {@code retry-after-ms} / {@code retry-after} 头信息，否则回退到指数退避。
     *
     * @param attempt 当前重试次数（从 1 开始）
     * @param error API 错误信息；可为 {@code null}
     * @return 延迟毫秒数
     */
    public static long delay(int attempt, ApiError error) {
        if (error != null && error.responseHeaders() != null) {
            Map<String, String> headers = error.responseHeaders();

            // 优先读取 retry-after-ms 头
            String retryAfterMs = headers.get("retry-after-ms");
            if (retryAfterMs != null) {
                try {
                    long parsedMs = Long.parseLong(retryAfterMs);
                    return cap(parsedMs);
                } catch (NumberFormatException ignored) {}
            }

            // 其次读取 retry-after 头（秒数或 HTTP 日期）
            String retryAfter = headers.get("retry-after");
            if (retryAfter != null) {
                try {
                    double parsedSeconds = Double.parseDouble(retryAfter);
                    return cap((long) Math.ceil(parsedSeconds * 1000));
                } catch (NumberFormatException ignored) {
                    try {
                        long parsed = Instant.from(DateTimeFormatter.RFC_1123_DATE_TIME.parse(retryAfter)).toEpochMilli()
                            - System.currentTimeMillis();
                        if (parsed > 0) {
                            return cap((long) Math.ceil(parsed));
                        }
                    } catch (IllegalArgumentException ignored2) {}
                }
            }

            // 有响应头但没有 retry-after 时使用指数退避
            return cap(RETRY_INITIAL_DELAY * (long) Math.pow(RETRY_BACKOFF_FACTOR, attempt - 1));
        }

        // 无响应头时使用有上限的指数退避
        return cap(Math.min(
            RETRY_INITIAL_DELAY * (long) Math.pow(RETRY_BACKOFF_FACTOR, attempt - 1),
            RETRY_MAX_DELAY_NO_HEADERS
        ));
    }

    /**
     * 将延迟限制在全局最大值之内。
     */
    private static long cap(long ms) {
        return Math.min(ms, RETRY_MAX_DELAY);
    }

    /**
     * 判断一个 API 错误是否值得重试。
     *
     * @param error API 错误
     * @param provider 提供者名称
     * @return 可重试判定结果；不可重试时返回 {@code null}
     */
    public static Retryable retryable(ApiError error, String provider) {
        if (error == null) return null;

        // 上下文溢出不应重试
        if (error.message() != null && error.message().contains("context_length_exceeded")) {
            return null;
        }

        Integer status = error.statusCode();
        boolean isRetryable = error.retryable();

        // 5xx 始终视为可重试
        if (!isRetryable && (status == null || status < 500)) {
            return null;
        }

        // 免费配额耗尽
        if (error.responseBody() != null && error.responseBody().contains("FreeUsageLimitError")) {
            return new Retryable(
                "Free usage exceeded",
                new RetryAction(
                    RetryReason.FREE_TIER_LIMIT,
                    provider,
                    "Free limit reached",
                    "Usage limit reached on free tier. Consider upgrading your plan.",
                    "upgrade",
                    null
                )
            );
        }

        // 429 限流
        if (status != null && status == 429) {
            return new Retryable(
                "Rate limited",
                new RetryAction(
                    RetryReason.ACCOUNT_RATE_LIMIT,
                    provider,
                    "Rate limited",
                    "Too many requests. Will retry with backoff.",
                    "wait",
                    null
                )
            );
        }

        // 通用可重试（5xx 或显式标记为可重试）
        return new Retryable("Server error, will retry", null);
    }

    /**
     * 以重试方式执行操作。
     *
     * @param operation 待执行操作
     * @param maxRetries 最大重试次数
     * @param provider 提供者名称
     * @param <T> 返回类型
     * @return 操作结果
     * @throws Exception 全部重试耗尽时抛出
     */
    public static <T> T executeWithRetry(RetryableOperation<T> operation, int maxRetries, String provider) throws Exception {
        Exception lastError = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return operation.execute();
            } catch (Exception e) {
                lastError = e;

                if (attempt >= maxRetries) break;

                ApiError apiError = extractApiError(e);
                Retryable retryable = retryable(apiError, provider);

                if (retryable == null) {
                    throw e;
                }

                long delayMs = delay(attempt + 1, apiError);
                log.warn("Attempt {} failed: {}. Retrying in {}ms (reason: {})",
                    attempt + 1, e.getMessage(), delayMs,
                    retryable.action() != null ? retryable.action().reason() : "retryable");

                Thread.sleep(delayMs);
            }
        }

        throw lastError;
    }

    /**
     * 从异常中提取 API 错误信息。
     */
    private static ApiError extractApiError(Exception e) {
        if (e instanceof ApiErrorException aee) {
            return aee.apiError();
        }
        return new ApiError(e.getMessage(), null, false, null, null);
    }

    /**
     * 封装 {@link ApiError} 的异常。
     */
    public static class ApiErrorException extends Exception {
        private final ApiError apiError;

        /**
         * 使用 API 错误创建异常。
         *
         * @param apiError API 错误
         */
        public ApiErrorException(ApiError apiError) {
            super(apiError.message());
            this.apiError = apiError;
        }

        /**
         * 返回封装的 API 错误。
         *
         * @return API 错误
         */
        public ApiError apiError() {
            return apiError;
        }
    }

    /**
     * 可重试操作的函数式接口。
     *
     * @param <T> 返回类型
     */
    @FunctionalInterface
    public interface RetryableOperation<T> {
        /**
         * 执行操作。
         *
         * @return 操作结果
         * @throws Exception 失败时抛出
         */
        T execute() throws Exception;
    }
}
