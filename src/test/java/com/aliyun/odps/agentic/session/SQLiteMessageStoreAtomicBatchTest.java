package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SQLiteMessageStoreAtomicBatchTest {
    @TempDir Path tempDir;

    @Test
    void failedInitialBatchLeavesNoPartialHistoryOrOwnershipAndCanRetry() throws Exception {
        String path = tempDir.resolve("messages.db").toString();
        SQLiteMessageStore store = new SQLiteMessageStore(path);
        rejectSecondMessage(path);

        assertThrows(RuntimeException.class, () -> store.updateMessages("s", List.of(
            message("first", "first"), message("second", "second"))));

        assertTrue(store.getMessages("s").isEmpty());
        assertFalse(store.isSessionOwned("s"));
        SQLiteMessageStore cold = new SQLiteMessageStore(path);
        assertTrue(cold.getMessages("s").isEmpty());
        assertFalse(cold.isSessionOwned("s"));

        execute(path, "DROP TRIGGER reject_second");
        store.updateMessages("s", List.of(message("first", "first"), message("second", "second")));
        assertEquals(List.of("first", "second"), cold.getMessages("s").stream()
            .map(Message::getTextContent).toList());
        assertTrue(cold.isSessionOwned("s"));
    }

    @Test
    void failedBatchRestoresPreviousMessageVersion() throws Exception {
        String path = tempDir.resolve("messages.db").toString();
        SQLiteMessageStore store = new SQLiteMessageStore(path);
        Message original = message("first", "original");
        store.updateMessage("s", original);
        rejectSecondMessage(path);

        assertThrows(RuntimeException.class, () -> store.updateMessages("s", List.of(
            new Message(original.id(), original.sessionId(), original.role(), null, null,
                List.of(new MessagePart.TextPart("changed")), null, null, null, null,
                null, null, null, original.createdAt()), message("second", "second"))));

        assertEquals(List.of(original), new SQLiteMessageStore(path).getMessages("s"));
        assertTrue(store.isSessionOwned("s"));
    }

    @Test
    void failedReplacementRestoresFullHistoryAndEmptyReplacementRetainsOwnership() throws Exception {
        String path = tempDir.resolve("messages.db").toString();
        SQLiteMessageStore store = new SQLiteMessageStore(path);
        List<Message> original = List.of(message("first", "original first"),
            message("second", "original second"));
        store.updateMessages("s", original);
        rejectSecondMessage(path);

        assertThrows(RuntimeException.class, () -> store.replaceMessages("s", List.of(
            message("first", "changed first"), message("second", "changed second"))));
        assertEquals(original, new SQLiteMessageStore(path).getMessages("s"));

        store.replaceMessages("s", List.of());
        SQLiteMessageStore cold = new SQLiteMessageStore(path);
        assertTrue(cold.getMessages("s").isEmpty());
        assertTrue(cold.isSessionOwned("s"));
    }

    private static Message message(String id, String text) {
        return new Message(id, "s", Role.USER, List.of(new MessagePart.TextPart(text)));
    }

    private static void rejectSecondMessage(String path) throws Exception {
        execute(path, """
            CREATE TRIGGER reject_second BEFORE INSERT ON message
            WHEN NEW.id = 'second'
            BEGIN SELECT RAISE(ABORT, 'simulated import failure'); END
            """);
    }

    private static void execute(String path, String sql) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
