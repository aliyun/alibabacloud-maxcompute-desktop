package com.aliyun.odps.agentic.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SmallModelFallbackTest {

    @Test
    void configuredSmallModelTakesPriority() {
        String result = SmallModelFallback.findSmallModel("claude-4-sonnet", "my-custom-small");
        assertEquals("my-custom-small", result);
    }

    @Test
    void exactMatchFallback() {
        assertEquals("claude-4-haiku", SmallModelFallback.findSmallModel("claude-4-sonnet", null));
        assertEquals("gpt-4o-mini", SmallModelFallback.findSmallModel("gpt-4o", null));
        assertEquals("gemini-2.5-flash", SmallModelFallback.findSmallModel("gemini-2.5-pro", null));
    }

    @Test
    void prefixMatchFallback() {
        String result = SmallModelFallback.findSmallModel("claude-4-sonnet-20250514", null);
        assertEquals("claude-4-haiku", result);
    }

    @Test
    void noFallbackReturnsSameModel() {
        String result = SmallModelFallback.findSmallModel("unknown-model", null);
        assertEquals("unknown-model", result);
    }

    @Test
    void isSmallModelDetection() {
        assertTrue(SmallModelFallback.isSmallModel("claude-3.5-haiku"));
        assertTrue(SmallModelFallback.isSmallModel("gpt-4o-mini"));
        assertTrue(SmallModelFallback.isSmallModel("gemini-2.5-flash"));
        assertFalse(SmallModelFallback.isSmallModel("claude-4-sonnet"));
        assertFalse(SmallModelFallback.isSmallModel("gpt-4o"));
    }
}
