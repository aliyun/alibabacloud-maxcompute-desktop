package com.aliyun.odps.agentic.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MessageTest {

    @Test
    void message_creation() {
        Message msg = new Message(
            "msg-1", "session-1", Role.USER, null, null,
            List.of(new MessagePart.TextPart("Hello")),
            "test-agent", null, null, null, null, null, null,
            Instant.now()
        );

        assertEquals("msg-1", msg.id());
        assertEquals(Role.USER, msg.role());
        assertEquals("Hello", msg.getTextContent());
    }

    @Test
    void message_withToolCall() {
        MessagePart.ToolCallPart toolCall = new MessagePart.ToolCallPart(
            "call-1", "read", "{\"path\":\"/tmp/test.txt\"}"
        );
        Message msg = new Message(
            "msg-1", "session-1", Role.ASSISTANT, null, null,
            List.of(new MessagePart.TextPart("Reading file"), toolCall),
            "test-agent", null, null, null, null, null, null,
            Instant.now()
        );

        assertEquals("Reading file", msg.getTextContent());
        List<MessagePart> toolParts = msg.parts().stream()
            .filter(p -> p instanceof MessagePart.ToolCallPart)
            .toList();
        assertEquals(1, toolParts.size());
        assertEquals("read", ((MessagePart.ToolCallPart) toolParts.getFirst()).name());
    }

    @Test
    void message_withToolResult() {
        MessagePart.ToolResultPart result = new MessagePart.ToolResultPart(
            "call-1", "read", "File contents here", false
        );
        Message msg = new Message(
            "msg-1", "session-1", Role.USER, null, null,
            List.of(result),
            "test-agent", null, null, null, null, null, null,
            Instant.now()
        );

        List<MessagePart> results = msg.parts().stream()
            .filter(p -> p instanceof MessagePart.ToolResultPart)
            .toList();
        assertEquals(1, results.size());
        assertFalse(((MessagePart.ToolResultPart) results.getFirst()).isError());
    }

    @Test
    void role_values() {
        assertEquals(3, Role.values().length);
        assertNotNull(Role.USER);
        assertNotNull(Role.ASSISTANT);
        assertNotNull(Role.SYSTEM);
    }

    @Test
    void sessionStatus_values() {
        assertEquals(4, SessionStatus.values().length);
        assertNotNull(SessionStatus.IDLE);
        assertNotNull(SessionStatus.BUSY);
        assertNotNull(SessionStatus.SUSPENDED);
        assertNotNull(SessionStatus.ERROR);
    }

    @Test
    void messagePart_textPart() {
        MessagePart.TextPart part = new MessagePart.TextPart("hello");
        assertEquals("hello", part.text());
        assertFalse(part.synthetic());
        assertFalse(part.ignored());
    }

    @Test
    void messagePart_thinkingPart() {
        MessagePart.ReasoningPart part = new MessagePart.ReasoningPart("thinking...");
        assertEquals("thinking...", part.text());
    }

    @Test
    void messagePart_imagePart() {
        MessagePart.ImagePart part = new MessagePart.ImagePart("base64data", "image/png");
        assertEquals("base64data", part.data());
        assertEquals("image/png", part.mimeType());
    }

    @Test
    void messagePart_compactionPart() {
        MessagePart.CompactionPart part = new MessagePart.CompactionPart(true, "Summary of old messages");
        assertTrue(part.auto());
        assertEquals("Summary of old messages", part.summary());
    }

    @Test
    void messagePart_toolPart() {
        ToolCallState state = ToolCallState.completed(null, "",
            "Result", "File contents", System.currentTimeMillis());
        MessagePart.ToolPart part = new MessagePart.ToolPart(
            "read", "call-1", state
        );
        assertEquals("read", part.tool());
        assertEquals("call-1", part.callID());
        assertInstanceOf(ToolCallState.Completed.class, part.state());
    }
}
