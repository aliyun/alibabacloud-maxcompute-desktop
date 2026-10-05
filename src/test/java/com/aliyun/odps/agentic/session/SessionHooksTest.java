package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.Tokens;
import com.aliyun.odps.agentic.tool.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for SessionHooks — default no-op implementations and custom overrides.
 */
class SessionHooksTest {

    @Test
    void defaultBeforeToolCallDoesNotThrow() {
        SessionHooks hooks = new SessionHooks() {};
        assertDoesNotThrow(() -> hooks.beforeToolCall("s1", "read", "c1", "{\"path\":\"/tmp\"}"));
    }

    @Test
    void defaultAfterToolCallDoesNotThrow() {
        SessionHooks hooks = new SessionHooks() {};
        assertDoesNotThrow(() -> hooks.afterToolCall("s1", "read", "c1", ToolResult.of("ok")));
    }

    @Test
    void defaultBeforeLLMCallDoesNotThrow() {
        SessionHooks hooks = new SessionHooks() {};
        assertDoesNotThrow(() -> hooks.beforeLLMCall("s1", 1));
    }

    @Test
    void defaultAfterLLMCallDoesNotThrow() {
        SessionHooks hooks = new SessionHooks() {};
        Message msg = new Message("m1", "s1", Role.ASSISTANT, List.of());
        assertDoesNotThrow(() -> hooks.afterLLMCall("s1", 1, msg));
    }

    @Test
    void defaultOnMessagePersistedDoesNotThrow() {
        SessionHooks hooks = new SessionHooks() {};
        Message msg = new Message("m1", "s1", Role.USER, List.of());
        assertDoesNotThrow(() -> hooks.onMessagePersisted("s1", msg));
    }

    @Test
    void defaultOnAbortDoesNotThrow() {
        SessionHooks hooks = new SessionHooks() {};
        assertDoesNotThrow(() -> hooks.onAbort("s1"));
    }

    @Test
    void defaultOnCostUpdateDoesNotThrow() {
        SessionHooks hooks = new SessionHooks() {};
        assertDoesNotThrow(() -> hooks.onCostUpdate("s1", 0.01, 0.05, new Tokens(100, 200, 0, 0, 0, 300)));
    }

    @Test
    void customHookIsInvoked() {
        boolean[] called = {false};
        SessionHooks hooks = new SessionHooks() {
            @Override
            public void beforeToolCall(String sessionId, String tool, String callId, String input) {
                called[0] = true;
            }
        };
        hooks.beforeToolCall("s1", "read", "c1", "{}");
        assertTrue(called[0]);
    }
}
