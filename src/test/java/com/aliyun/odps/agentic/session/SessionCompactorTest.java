package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;
import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.Identifier;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SessionCompactorTest {

    @Test
    void compact_replacesHeadWithSummaryAndPreservesTail() {
        List<AgentEvent> events = new ArrayList<>();
        InMemoryMessageStore messageStore = new InMemoryMessageStore();
        SessionCompactor compactor = new SessionCompactor(new SummaryLlmClient(), new CompactionEngine(), messageStore, events::add);
        List<Message> messages = createLongHistory("test-session", 12);

        ManualCompactionResult result = compactor.compact(
            "test-session",
            messages,
            Model.of("test", "test-model", new ModelLimit(128000, null, 4096)),
            CompactionReason.MANUAL,
            false
        );

        assertTrue(result.changed());
        assertNotNull(result.summaryMessage());
        assertEquals(Role.ASSISTANT, result.summaryMessage().role());
        assertTrue(result.messages().size() < messages.size());
        assertEquals(result.messages(), messageStore.getMessages("test-session"));
        assertTrue(events.get(0) instanceof AgentEvent.CompactionStart start && start.reason() == CompactionReason.MANUAL);
        assertTrue(events.get(1) instanceof AgentEvent.CompactionEnd end && end.reason() == CompactionReason.MANUAL);
    }

    @Test
    void compact_noOpStillEmitsClosedLifecycle() {
        List<AgentEvent> events = new ArrayList<>();
        InMemoryMessageStore messageStore = new InMemoryMessageStore();
        SessionCompactor compactor = new SessionCompactor(new SummaryLlmClient(), new CompactionEngine(), messageStore, events::add);
        List<Message> messages = List.of(
            createMessage("short", Role.USER, "hello"),
            createMessage("short", Role.ASSISTANT, "world")
        );

        ManualCompactionResult result = compactor.compact(
            "short",
            messages,
            Model.of("test", "test-model", new ModelLimit(128000, null, 4096)),
            CompactionReason.MANUAL,
            false
        );

        assertFalse(result.changed());
        assertNull(result.summary());
        assertEquals(2, events.size());
        assertInstanceOf(AgentEvent.CompactionStart.class, events.get(0));
        assertInstanceOf(AgentEvent.CompactionEnd.class, events.get(1));
    }

    private static List<Message> createLongHistory(String sessionId, int turns) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            messages.add(createMessage(sessionId, Role.USER, "User message " + i + " " + "x".repeat(200)));
            messages.add(createMessage(sessionId, Role.ASSISTANT, "Assistant response " + i + " " + "y".repeat(200)));
        }
        return messages;
    }

    private static Message createMessage(String sessionId, Role role, String text) {
        return new Message(
            Identifier.messageId(),
            sessionId,
            role,
            null,
            null,
            List.of(new MessagePart.TextPart(text)),
            "test-agent",
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.now()
        );
    }

    static class SummaryLlmClient implements LLMClient {
        @Override
        public boolean supports(String providerId) {
            return true;
        }

        @Override
        public void stream(LlmRequest request, Consumer<LLMEvent> eventConsumer) {
            eventConsumer.accept(new LLMEvent.TextDelta("## Goal\n- Summary\n\n## Constraints & Preferences\n- None\n\n## Progress\n### Done\n- Compacted\n\n### In Progress\n- None\n\n### Blocked\n- None\n\n## Key Decisions\n- Keep recent tail\n\n## Next Steps\n- Continue\n\n## Critical Context\n- Preserved\n\n## Relevant Files\n- None"));
            eventConsumer.accept(new LLMEvent.Finish("end-turn"));
        }
    }
}
