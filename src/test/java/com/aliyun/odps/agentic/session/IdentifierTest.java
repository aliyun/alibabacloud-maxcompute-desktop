package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;
import com.aliyun.odps.agentic.model.Identifier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Identifier accessibility from session package.
 */
class IdentifierAccessTest {

    @Test
    void identifierIsAccessibleFromSessionPackage() {
        String id = Identifier.messageId();
        assertNotNull(id);
        assertTrue(id.startsWith("msg"));
    }
}
