package com.aliyun.odps.agentic.llm;

import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for Auth pipeline — Credential, AuthFn, composition.
 * Faithful to opencode route/auth.ts.
 */
class AuthTest {

    // ── Credential ──

    @Test
    void credentialValue() {
        Auth.Credential c = Auth.value("my-secret");
        assertEquals("my-secret", c.load());
    }

    @Test
    void credentialValueEmptyThrows() {
        Auth.Credential c = Auth.value("");
        assertThrows(Auth.MissingCredentialError.class, c::load);
    }

    @Test
    void credentialOptional() {
        Auth.Credential c = Auth.optional("secret");
        assertEquals("secret", c.load());
    }

    @Test
    void credentialOptionalNullThrows() {
        Auth.Credential c = Auth.optional(null);
        assertThrows(Auth.MissingCredentialError.class, c::load);
    }

    @Test
    void credentialOrElse() {
        Auth.Credential primary = Auth.optional(null, "primary");
        Auth.Credential fallback = Auth.value("fallback");
        Auth.Credential composed = primary.orElse(fallback);
        assertEquals("fallback", composed.load());
    }

    @Test
    void credentialOrElsePrimarySucceeds() {
        Auth.Credential primary = Auth.value("primary");
        Auth.Credential fallback = Auth.value("fallback");
        assertEquals("primary", primary.orElse(fallback).load());
    }

    // ── AuthFn ──

    @Test
    void authNone() {
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of("content-type", "application/json"));
        Map<String, String> result = Auth.none.apply(input);
        assertEquals("application/json", result.get("content-type"));
    }

    @Test
    void authBearer() {
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of());
        Map<String, String> result = Auth.bearer("tok123").apply(input);
        assertEquals("Bearer tok123", result.get("authorization"));
    }

    @Test
    void authApiKey() {
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of());
        Map<String, String> result = Auth.apiKey("key456").apply(input);
        assertEquals("Bearer key456", result.get("authorization"));
    }

    @Test
    void authHeader() {
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of());
        Map<String, String> result = Auth.header("x-api-key", "abc").apply(input);
        assertEquals("abc", result.get("x-api-key"));
    }

    @Test
    void authBearerHeader() {
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of());
        Map<String, String> result = Auth.bearerHeader("x-api-key", "tok").apply(input);
        assertEquals("Bearer tok", result.get("x-api-key"));
    }

    @Test
    void authHeadersMerge() {
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of("existing", "val"));
        Map<String, String> result = Auth.headers(Map.of("extra", "data")).apply(input);
        assertEquals("val", result.get("existing"));
        assertEquals("data", result.get("extra"));
    }

    @Test
    void authAndThen() {
        Auth.AuthFn first = Auth.bearer("tok1");
        Auth.AuthFn second = Auth.headers(Map.of("x-custom", "val"));
        Auth.AuthFn composed = first.andThen(second);
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of());
        Map<String, String> result = composed.apply(input);
        assertEquals("Bearer tok1", result.get("authorization"));
        assertEquals("val", result.get("x-custom"));
    }

    @Test
    void authOrElse() {
        Auth.AuthFn primary = Auth.bearer(Auth.optional(null));
        Auth.AuthFn fallback = Auth.bearer("fallback-tok");
        Auth.AuthFn composed = primary.orElse(fallback);
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of());
        Map<String, String> result = composed.apply(input);
        assertEquals("Bearer fallback-tok", result.get("authorization"));
    }

    @Test
    void authRemove() {
        Auth.AuthInput input = new Auth.AuthInput("POST", "http://x", "", Map.of("authorization", "old"));
        Map<String, String> result = Auth.remove("authorization").apply(input);
        assertFalse(result.containsKey("authorization"));
    }

    @Test
    void isAuth() {
        assertTrue(Auth.isAuth(Auth.none));
        assertTrue(Auth.isAuth(Auth.bearer("x")));
        assertFalse(Auth.isAuth("not an auth"));
    }

    @Test
    void missingCredentialError() {
        Auth.MissingCredentialError e = new Auth.MissingCredentialError("API_KEY");
        assertTrue(e.getMessage().contains("API_KEY"));
        assertEquals("API_KEY", e.source());
    }

    @Test
    void authPassthrough() {
        Auth.AuthInput input = new Auth.AuthInput("GET", "http://x", "", Map.of("a", "b"));
        Map<String, String> result = Auth.passthrough.apply(input);
        assertEquals("b", result.get("a"));
    }
}
