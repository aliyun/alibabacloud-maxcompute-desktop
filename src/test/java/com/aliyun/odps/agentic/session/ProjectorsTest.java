package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectorsTest {

    @Test
    void projectForAnthropicFiltersReasoningParts() {
        var reasoning = new MessagePart.ReasoningPart("thinking...");
        var text = new MessagePart.TextPart("hello");
        var msg = new Message("id1", "s1", Role.ASSISTANT, List.of(reasoning, text));

        List<Message> projected = Projectors.projectForAnthropic(List.of(msg));
        assertEquals(1, projected.size());
        assertEquals(1, projected.get(0).parts().size());
        assertInstanceOf(MessagePart.TextPart.class, projected.get(0).parts().get(0));
    }

    @Test
    void projectForOpenAIFiltersReasoningParts() {
        var reasoning = new MessagePart.ReasoningPart("thinking...");
        var text = new MessagePart.TextPart("hello");
        var msg = new Message("id1", "s1", Role.ASSISTANT, List.of(reasoning, text));

        List<Message> projected = Projectors.projectForOpenAI(List.of(msg));
        assertEquals(1, projected.size());
        assertEquals(1, projected.get(0).parts().size());
    }

    @Test
    void emptyNonSystemMessageGetsPlaceholder() {
        var msg = new Message("id1", "s1", Role.ASSISTANT, List.of());

        List<Message> projected = Projectors.projectForAnthropic(List.of(msg));
        assertEquals(1, projected.get(0).parts().size());
        assertEquals("", ((MessagePart.TextPart) projected.get(0).parts().get(0)).text());
    }

    @Test
    void systemMessagesPreservedEmpty() {
        var msg = new Message("id1", "s1", Role.SYSTEM, List.of());

        List<Message> projected = Projectors.projectForAnthropic(List.of(msg));
        assertTrue(projected.get(0).parts().isEmpty());
    }

    @Test
    void textOnlyMessageUnchanged() {
        var text = new MessagePart.TextPart("hello world");
        var msg = new Message("id1", "s1", Role.USER, List.of(text));

        List<Message> projected = Projectors.projectForAnthropic(List.of(msg));
        assertEquals("hello world",
            ((MessagePart.TextPart) projected.get(0).parts().get(0)).text());
    }

    @Test
    void preservesOtherFields() {
        var text = new MessagePart.TextPart("hello");
        var msg = new Message("id1", "s1", Role.ASSISTANT, List.of(text));

        List<Message> projected = Projectors.projectForAnthropic(List.of(msg));
        assertEquals("id1", projected.get(0).id());
        assertEquals("s1", projected.get(0).sessionId());
        assertEquals(Role.ASSISTANT, projected.get(0).role());
    }
}
