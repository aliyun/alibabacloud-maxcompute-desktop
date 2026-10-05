package com.aliyun.odps.agentic.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdentifierTest {

    @Test
    void messageIdHasPrefix() {
        String id = Identifier.messageId();
        assertTrue(id.startsWith("msg"), "Expected msg prefix, got: " + id);
    }

    @Test
    void sessionIdHasPrefix() {
        String id = Identifier.sessionId();
        assertTrue(id.startsWith("ses"), "Expected ses prefix, got: " + id);
    }

    @Test
    void partIdHasPrefix() {
        String id = Identifier.partId();
        assertTrue(id.startsWith("prt"), "Expected prt prefix, got: " + id);
    }

    @Test
    void idsAreUnique() {
        String id1 = Identifier.messageId();
        String id2 = Identifier.messageId();
        assertNotEquals(id1, id2);
    }

    @Test
    void customPrefixViaAscending() {
        String id = Identifier.ascending("test");
        assertTrue(id.startsWith("test"), "Expected test prefix, got: " + id);
    }

    @Test
    void idsHaveSufficientLength() {
        String id = Identifier.messageId();
        // Should be prefix + timestamp + random = at least 15 chars
        assertTrue(id.length() >= 15, "ID too short: " + id);
    }

    @Test
    void timestampExtraction() {
        String id = Identifier.messageId();
        long ts = Identifier.timestamp(id);
        assertTrue(ts > 0, "Timestamp should be positive");
        assertTrue(ts <= System.currentTimeMillis() + 1000, "Timestamp should be near now");
    }
}
