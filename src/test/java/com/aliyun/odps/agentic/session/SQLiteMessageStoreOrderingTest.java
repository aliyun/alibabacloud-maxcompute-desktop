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

class SQLiteMessageStoreOrderingTest {
    @TempDir Path tempDir;

    @Test
    void coldReadOrdersMixedFractionalPrecisionAndRetainsEqualTimeInsertionOrder() {
        String path = tempDir.resolve("messages.db").toString();
        SQLiteMessageStore store = new SQLiteMessageStore(path);
        Message first = message("first", "2026-10-05T04:14:14.950Z", "first");
        Message equal = message("equal", "2026-10-05T04:14:14.950Z", "equal");
        Message micro = message("micro", "2026-10-05T04:14:14.950001Z", "micro");
        Message nano = message("nano", "2026-10-05T04:14:14.950001001Z", "nano");
        Message earlier = message("earlier", "2026-10-05T04:14:14Z", "earlier");
        store.updateMessages("s", List.of(nano, first, equal, micro, earlier));
        store.updateMessage("s", message("first", first.createdAt().toString(), "updated first"));
        List<String> expected = List.of("earlier", "first", "equal", "micro", "nano");
        assertEquals(expected, store.getMessages("s").stream().map(Message::id).toList());
        assertEquals(expected, new SQLiteMessageStore(path).getMessages("s").stream()
            .map(Message::id).toList());
    }

    private static Message message(String id, String time, String text) {
        return new Message(id, "s", Role.USER, null, null,
            List.of(new MessagePart.TextPart(text)), null, null, null, null,
            null, null, null, Instant.parse(time));
    }
}
