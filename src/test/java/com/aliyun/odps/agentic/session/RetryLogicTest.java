package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RetryLogicTest {

    // ── delay() — faithfully distilled from opencode session/retry.ts ──

    @Test
    void delay_noHeaders_exponentialBackoff() {
        // Faithful to opencode: RETRY_INITIAL_DELAY * RETRY_BACKOFF_FACTOR^(attempt-1), capped at RETRY_MAX_DELAY_NO_HEADERS
        assertEquals(2000, RetryLogic.delay(1, null));
        assertEquals(4000, RetryLogic.delay(2, null));
        assertEquals(8000, RetryLogic.delay(3, null));
        assertEquals(16000, RetryLogic.delay(4, null));
        // Capped at 30_000
        assertEquals(30_000, RetryLogic.delay(5, null));
    }

    @Test
    void delay_retryAfterMs_header() {
        RetryLogic.ApiError error = new RetryLogic.ApiError("rate limited", 429, true,
            Map.of("retry-after-ms", "5000"), null);
        assertEquals(5000, RetryLogic.delay(1, error));
    }

    @Test
    void delay_retryAfter_seconds() {
        RetryLogic.ApiError error = new RetryLogic.ApiError("rate limited", 429, true,
            Map.of("retry-after", "3"), null);
        assertEquals(3000, RetryLogic.delay(1, error));
    }

    @Test
    void delay_retryAfter_fractionalSeconds() {
        RetryLogic.ApiError error = new RetryLogic.ApiError("rate limited", 429, true,
            Map.of("retry-after", "2.5"), null);
        assertEquals(2500, RetryLogic.delay(1, error));
    }

    @Test
    void delay_noRetryAfterHeader_exponentialBackoff() {
        RetryLogic.ApiError error = new RetryLogic.ApiError("server error", 500, true,
            Map.of(), null);
        // Faithful to opencode: exponential backoff when headers exist but no retry-after
        assertEquals(2000, RetryLogic.delay(1, error));
        assertEquals(4000, RetryLogic.delay(2, error));
    }

    // ── retryable() — faithfully distilled from opencode session/retry.ts ──

    @Test
    void retryable_contextOverflow_notRetryable() {
        // Faithful to opencode: context overflow errors should not be retried
        RetryLogic.ApiError error = new RetryLogic.ApiError("context_length_exceeded", 400, false, null, null);
        assertNull(RetryLogic.retryable(error, "anthropic"));
    }

    @Test
    void retryable_5xx_alwaysRetryable() {
        // Faithful to opencode: 5xx errors are always retryable
        RetryLogic.ApiError error = new RetryLogic.ApiError("internal error", 500, false, null, null);
        RetryLogic.Retryable result = RetryLogic.retryable(error, "anthropic");
        assertNotNull(result);
    }

    @Test
    void retryable_429_rateLimit() {
        RetryLogic.ApiError error = new RetryLogic.ApiError("rate limited", 429, true, null, null);
        RetryLogic.Retryable result = RetryLogic.retryable(error, "anthropic");
        assertNotNull(result);
        assertNotNull(result.action());
        assertEquals(RetryLogic.RetryReason.ACCOUNT_RATE_LIMIT, result.action().reason());
    }

    @Test
    void retryable_freeUsageLimit() {
        RetryLogic.ApiError error = new RetryLogic.ApiError("limit exceeded", 429, true,
            null, "FreeUsageLimitError: you have exceeded your free usage");
        RetryLogic.Retryable result = RetryLogic.retryable(error, "anthropic");
        assertNotNull(result);
        assertEquals(RetryLogic.RetryReason.FREE_TIER_LIMIT, result.action().reason());
    }

    @Test
    void retryable_4xx_notRetryable() {
        // Faithful to opencode: non-5xx non-retryable errors are not retried
        RetryLogic.ApiError error = new RetryLogic.ApiError("bad request", 400, false, null, null);
        assertNull(RetryLogic.retryable(error, "anthropic"));
    }

    @Test
    void retryable_nullError() {
        assertNull(RetryLogic.retryable(null, "anthropic"));
    }

    // ── Constants — faithfully copied from opencode ──

    @Test
    void constants_matchOpencode() {
        assertEquals(2000, RetryLogic.RETRY_INITIAL_DELAY);
        assertEquals(2, RetryLogic.RETRY_BACKOFF_FACTOR);
        assertEquals(30_000, RetryLogic.RETRY_MAX_DELAY_NO_HEADERS);
        assertEquals(Integer.MAX_VALUE, RetryLogic.RETRY_MAX_DELAY);
    }

    // ── executeWithRetry ──

    @Test
    void executeWithRetry_succeedsOnFirstAttempt() throws Exception {
        String result = RetryLogic.executeWithRetry(() -> "success", 3, "test");
        assertEquals("success", result);
    }

    @Test
    void executeWithRetry_retriesOn5xx() throws Exception {
        int[] attempts = {0};
        String result = RetryLogic.executeWithRetry(() -> {
            attempts[0]++;
            if (attempts[0] < 3) {
                throw new RetryLogic.ApiErrorException(
                    new RetryLogic.ApiError("server error", 500, true, null, null));
            }
            return "success";
        }, 3, "test");
        assertEquals("success", result);
        assertEquals(3, attempts[0]);
    }

    @Test
    void executeWithRetry_nonRetryable_failsImmediately() {
        assertThrows(Exception.class, () ->
            RetryLogic.executeWithRetry(() -> {
                throw new RuntimeException("not retryable");
            }, 3, "test"));
    }

    // ── ApiErrorException ──

    @Test
    void apiErrorException_wrapsApiError() {
        RetryLogic.ApiError error = new RetryLogic.ApiError("test", 500, true, null, null);
        RetryLogic.ApiErrorException ex = new RetryLogic.ApiErrorException(error);
        assertEquals(error, ex.apiError());
        assertEquals("test", ex.getMessage());
    }
}
