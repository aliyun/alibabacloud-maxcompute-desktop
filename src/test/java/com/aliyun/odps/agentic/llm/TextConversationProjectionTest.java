package com.aliyun.odps.agentic.llm;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TextConversationProjectionTest {
    @Test
    void keepsCurrentUserAndDoesNotOrphanOldAssistantWhenHistoryIsTooLarge() {
        List<Message> source = List.of(
            new Message("u1", "s", Role.USER, List.of(new MessagePart.TextPart("x".repeat(17_000)))),
            new Message("a1", "s", Role.ASSISTANT, List.of(new MessagePart.TextPart("old answer"))),
            new Message("u2", "s", Role.USER, List.of(new MessagePart.TextPart("follow up"))));
        LlmRequest request = new LlmRequest(Model.of("studio", "text-task",
            new ModelLimit(100_000, null, 4_096)), List.of(), List.of(), Map.of(),
            "none", null, null, Map.of(), "s", source, false);

        assertEquals(List.of(Map.of("role", "user", "content", "follow up")),
            TextConversationProjection.recent(request));
    }
}
