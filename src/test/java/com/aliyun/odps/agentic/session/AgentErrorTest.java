package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentErrorTest {

    @Test
    void contextOverflowErrorCreation() {
        AgentError.ContextOverflowError err = new AgentError.ContextOverflowError("context_length_exceeded");
        assertEquals("context_length_exceeded", err.message());
    }

    @Test
    void contextOverflowErrorWithProvider() {
        AgentError.ContextOverflowError err = new AgentError.ContextOverflowError("overflow", "anthropic");
        assertEquals("overflow", err.message());
        assertEquals("anthropic", err.providerId());
    }

    @Test
    void contextOverflowIsInstance() {
        assertTrue(AgentError.ContextOverflowError.isInstance(
            new AgentErrorException(new AgentError.ContextOverflowError("overflow"))));
        assertTrue(AgentError.ContextOverflowError.isInstance(
            new RuntimeException("context_length_exceeded")));
    }

    @Test
    void abortedErrorCreation() {
        AgentError.AbortedError err = new AgentError.AbortedError("user cancelled");
        assertEquals("user cancelled", err.message());
    }

    @Test
    void abortedErrorDefault() {
        AgentError.AbortedError err = new AgentError.AbortedError();
        assertEquals("Request was aborted", err.message());
    }

    @Test
    void apiErrorCreation() {
        AgentError.ApiError err = new AgentError.ApiError("rate limit", 429);
        assertEquals("rate limit", err.message());
        assertEquals(429, err.statusCode());
        assertTrue(err.retryable()); // 429 is retryable
    }

    @Test
    void apiError500IsRetryable() {
        AgentError.ApiError err = new AgentError.ApiError("server error", 500);
        assertTrue(err.retryable());
    }

    @Test
    void apiError400IsNotRetryable() {
        AgentError.ApiError err = new AgentError.ApiError("bad request", 400);
        assertFalse(err.retryable());
    }

    @Test
    void outputLengthErrorCreation() {
        AgentError.OutputLengthError err = new AgentError.OutputLengthError("max tokens");
        assertEquals("max tokens", err.message());
    }

    @Test
    void unknownErrorCreation() {
        AgentError.UnknownError err = new AgentError.UnknownError("something went wrong");
        assertEquals("something went wrong", err.message());
    }

    @Test
    void unknownErrorFromThrowable() {
        AgentError.UnknownError err = new AgentError.UnknownError(new RuntimeException("test error"));
        assertEquals("test error", err.message());
    }

    @Test
    void fromClassifiesCorrectly() {
        AgentError overflow = AgentError.from(new RuntimeException("context_length_exceeded"));
        assertInstanceOf(AgentError.ContextOverflowError.class, overflow);

        AgentError aborted = AgentError.from(new RuntimeException("Request aborted"));
        assertInstanceOf(AgentError.AbortedError.class, aborted);

        AgentError unknown = AgentError.from(new RuntimeException("random error"));
        assertInstanceOf(AgentError.UnknownError.class, unknown);
    }

    @Test
    void fromPreservesAgentErrorException() {
        AgentError original = new AgentError.ApiError("test", 500);
        AgentError extracted = AgentError.from(new AgentErrorException(original));
        assertSame(original, extracted);
    }

    @Test
    void agentErrorExceptionWraps() {
        AgentError error = new AgentError.ContextOverflowError("overflow");
        AgentErrorException ex = new AgentErrorException(error);
        assertEquals(error, ex.error());
    }
}
