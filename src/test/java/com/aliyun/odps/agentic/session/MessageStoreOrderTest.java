package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageStoreOrderTest {
    @TempDir Path tempDir;

    @Test
    void inMemoryUpdateKeepsAssistantBeforeItsToolResult() {
        assertStableOrder(new InMemoryMessageStore());
    }

    @Test
    void sqliteColdReloadKeepsAssistantBeforeItsToolResult() {
        String path = tempDir.resolve("messages.db").toString();
        SQLiteMessageStore writer = new SQLiteMessageStore(path);
        assertStableOrder(writer);
        SQLiteMessageStore reader = new SQLiteMessageStore(path);
        assertEquals(List.of("assistant", "tool-result"),
            reader.getMessages("session").stream().map(Message::id).toList());
    }

    @Test
    void sqliteOwnershipSurvivesRemovingLastMessageAndClearingHistory() {
        String path = tempDir.resolve("owned.db").toString();
        SQLiteMessageStore writer = new SQLiteMessageStore(path);
        assertFalse(writer.isSessionOwned("session"));
        writer.updateMessage("session", message("first", "session", Role.USER,
            "text", Instant.parse("2026-01-01T00:00:00Z")));
        writer.removeMessage("session", "first");
        assertTrue(writer.getMessages("session").isEmpty());
        assertTrue(new SQLiteMessageStore(path).isSessionOwned("session"));

        writer.updateMessage("session", message("second", "session", Role.USER,
            "text", Instant.parse("2026-01-01T00:00:01Z")));
        writer.clear("session");
        SQLiteMessageStore reader = new SQLiteMessageStore(path);
        assertTrue(reader.isSessionOwned("session"));
        assertTrue(reader.getMessages("session").isEmpty());
    }

    private static void assertStableOrder(MessageStore store) {
        String session = "session";
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Message assistant = message("assistant", session, Role.ASSISTANT, "first", created);
        Message result = message("tool-result", session, Role.USER, "rows", created);
        store.updateMessage(session, assistant);
        store.updateMessage(session, result);
        store.updateMessage(session, message("assistant", session, Role.ASSISTANT,
            "updated", created));
        assertEquals(List.of("assistant", "tool-result"),
            store.getMessages(session).stream().map(Message::id).toList());
        assertEquals("updated", store.getMessages(session).getFirst().getTextContent());
    }

    private static Message message(String id, String session, Role role, String text,
                                   Instant created) {
        return new Message(id, session, role, null, null,
            List.of(new MessagePart.TextPart(text)), null, null, null, null,
            null, null, null, created);
    }
}
