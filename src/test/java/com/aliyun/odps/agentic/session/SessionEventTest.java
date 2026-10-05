package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for SessionEvent — all 4 sealed record variants.
 */
class SessionEventTest {

    // -- MessageUpdated --

    @Test
    void messageUpdatedConstruction() {
        Message msg = new Message("m1", "s1", Role.ASSISTANT, List.of());
        SessionEvent.MessageUpdated event = new SessionEvent.MessageUpdated("s1", msg);
        assertEquals("s1", event.sessionId());
        assertEquals("m1", event.message().id());
    }

    @Test
    void messageUpdatedEquality() {
        Message msg = new Message("m1", "s1", Role.ASSISTANT, List.of());
        SessionEvent.MessageUpdated e1 = new SessionEvent.MessageUpdated("s1", msg);
        SessionEvent.MessageUpdated e2 = new SessionEvent.MessageUpdated("s1", msg);
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    // -- PartUpdated --

    @Test
    void partUpdatedConstruction() {
        MessagePart.TextPart part = new MessagePart.TextPart("Hello");
        SessionEvent.PartUpdated event = new SessionEvent.PartUpdated("s1", "m1", part);
        assertEquals("s1", event.sessionId());
        assertEquals("m1", event.messageId());
        assertInstanceOf(MessagePart.TextPart.class, event.part());
    }

    // -- MessageRemoved --

    @Test
    void messageRemovedConstruction() {
        SessionEvent.MessageRemoved event = new SessionEvent.MessageRemoved("s1", "m1");
        assertEquals("s1", event.sessionId());
        assertEquals("m1", event.messageId());
    }

    @Test
    void messageRemovedEquality() {
        SessionEvent.MessageRemoved e1 = new SessionEvent.MessageRemoved("s1", "m1");
        SessionEvent.MessageRemoved e2 = new SessionEvent.MessageRemoved("s1", "m1");
        assertEquals(e1, e2);
    }

    // -- PartRemoved --

    @Test
    void partRemovedConstruction() {
        SessionEvent.PartRemoved event = new SessionEvent.PartRemoved("s1", "m1", "p1");
        assertEquals("s1", event.sessionId());
        assertEquals("m1", event.messageId());
        assertEquals("p1", event.partId());
    }

    // -- Sealed interface exhaustiveness --

    @Test
    void sealedInterfacePatternMatching() {
        Message msg = new Message("m1", "s1", Role.USER, List.of());
        MessagePart.TextPart part = new MessagePart.TextPart("text");

        List<SessionEvent> events = List.of(
            new SessionEvent.MessageUpdated("s1", msg),
            new SessionEvent.PartUpdated("s1", "m1", part),
            new SessionEvent.MessageRemoved("s1", "m1"),
            new SessionEvent.PartRemoved("s1", "m1", "p1")
        );

        for (SessionEvent event : events) {
            String type = switch (event) {
                case SessionEvent.MessageUpdated mu -> "msg-updated:" + mu.sessionId();
                case SessionEvent.PartUpdated pu -> "part-updated:" + pu.messageId();
                case SessionEvent.MessageRemoved mr -> "msg-removed:" + mr.messageId();
                case SessionEvent.PartRemoved pr -> "part-removed:" + pr.partId();
            };
            assertNotNull(type);
        }
    }
}
