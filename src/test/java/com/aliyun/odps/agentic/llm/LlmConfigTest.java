package com.aliyun.odps.agentic.llm;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Route + Model + ModelLimit + Usage — LLM configuration.
 */
class LlmConfigTest {

    // ── ModelLimit ──

    @Test
    void modelLimitConstruction() {
        ModelLimit limit = new ModelLimit(200000, 5000, 16384);
        assertEquals(200000, limit.context());
        assertEquals(5000, limit.input());
        assertEquals(16384, limit.output());
    }

    @Test
    void modelLimitEffectiveInput() {
        ModelLimit withInput = new ModelLimit(200000, 5000, 16384);
        assertEquals(5000, withInput.effectiveInputLimit());

        ModelLimit withoutInput = new ModelLimit(200000, null, 16384);
        assertEquals(200000, withoutInput.effectiveInputLimit()); // falls back to context
    }

    @Test
    void modelLimitWithNulls() {
        ModelLimit limit = new ModelLimit(100000, null, null);
        assertEquals(100000, limit.context());
        assertNull(limit.input());
        assertNull(limit.output());
    }

    // ── Usage ──

    @Test
    void usageConstruction() {
        Usage u = new Usage(100, 50, 30, 10, 5);
        assertEquals(100, u.inputTokens());
        assertEquals(50, u.outputTokens());
        assertEquals(30, u.cacheReadInputTokens());
        assertEquals(10, u.cacheCreationInputTokens());
        assertEquals(5, u.reasoningTokens());
    }

    @Test
    void usageTotal() {
        Usage u = new Usage(100, 50, 0, 0, 0);
        assertEquals(150, u.totalTokens());
    }

    @Test
    void usageBackwardCompat() {
        Usage u = new Usage(200, 100, 40, 20);
        assertEquals(200, u.inputTokens());
        assertEquals(100, u.outputTokens());
        assertEquals(40, u.cacheReadInputTokens());
        assertEquals(20, u.cacheCreationInputTokens());
        assertEquals(0, u.reasoningTokens());
    }

    // ── Model ──

    @Test
    void modelConstruction() {
        Route route = new Route("test", null, "http://localhost:8080", Auth.none, MessageFormat.OPENAI);
        ModelLimit limit = new ModelLimit(200000, 5000, 16384);
        Model model = new Model("gpt-4", route, limit);
        assertEquals("gpt-4", model.id());
        assertSame(route, model.route());
        assertSame(limit, model.limit());
    }

    @Test
    void modelFromRoute() {
        Route route = new Route("test", null, "http://localhost:8080", Auth.none, MessageFormat.OPENAI);
        ModelLimit limit = new ModelLimit(200000, 5000, 16384);
        Model model = route.model("gpt-4", limit);
        assertEquals("gpt-4", model.id());
        assertSame(route, model.route());
    }

    @Test
    void modelOf() {
        Model model = Model.of("anthropic", "claude-sonnet-4-20250514", new ModelLimit(200000, null, 16384));
        assertEquals("claude-sonnet-4-20250514", model.id());
        assertEquals("anthropic", model.providerId());
        assertEquals("claude-sonnet-4-20250514", model.apiId());
    }

    // ── Route ──

    @Test
    void routeConstruction() {
        Route route = new Route("test-provider", null, "http://api.test.com/v1", Auth.none, MessageFormat.OPENAI);
        assertEquals("test-provider", route.id());
        assertEquals("http://api.test.com/v1", route.baseUrl());
        assertNotNull(route.auth());
        assertNotNull(route.defaults());
    }

    @Test
    void routeWith() {
        Route original = new Route("p1", null, "http://old.com", Auth.none, MessageFormat.OPENAI);
        Route modified = original.with("http://new.com", Auth.bearer("tok"));
        assertEquals("http://new.com", modified.baseUrl());
        assertNotSame(original, modified); // immutable derivation
    }

    @Test
    void routeWithNullKeepsOriginal() {
        Route original = new Route("p1", null, "http://old.com", Auth.bearer("old"), MessageFormat.OPENAI);
        Route modified = original.with(null, null);
        assertEquals("http://old.com", modified.baseUrl());
    }

    // ── MessageFormat ──

    @Test
    void messageFormatValues() {
        assertEquals(2, MessageFormat.values().length);
        assertNotNull(MessageFormat.ANTHROPIC);
        assertNotNull(MessageFormat.OPENAI);
    }
}
