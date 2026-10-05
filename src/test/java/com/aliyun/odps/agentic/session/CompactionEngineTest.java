package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.model.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactionEngineTest {

    private static final Model MODEL = Model.of("test", "test",
        new ModelLimit(128000, null, 4096));

    @Test
    void checkNeeded_noCompactionForShortHistory() {
        CompactionEngine engine = new CompactionEngine();
        List<Message> messages = List.of(
            createMessage(Role.USER, "Hello"),
            createMessage(Role.ASSISTANT, "Hi there!")
        );

        CompactionEngine.CompactionRoute route = engine.checkNeeded(messages, MODEL);
        assertEquals(CompactionEngine.CompactionRoute.NONE, route);
    }

    @Test
    void process_returnsCompactionResult() {
        CompactionEngine engine = new CompactionEngine();
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(createMessage(Role.USER, "Message " + i));
            messages.add(createMessage(Role.ASSISTANT, "Response " + i));
        }

        CompactionEngine.CompactionResult result = engine.process(messages, false, false);
        assertNotNull(result);
        assertNotNull(result.head());
    }

    @Test
    void process_preservesRecentMessages() {
        CompactionEngine engine = new CompactionEngine();
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(createMessage(Role.USER, "Message " + i));
        }

        CompactionEngine.CompactionResult result = engine.process(messages, false, false);
        // Tail should contain recent messages
        if (!result.tail().isEmpty()) {
            String lastContent = result.tail().getLast().getTextContent();
            assertTrue(lastContent.contains("Message 29"),
                "Last message should be preserved, got: " + lastContent);
        }
    }

    @Test
    void process_withOverflow() {
        CompactionEngine engine = new CompactionEngine();
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(createMessage(Role.USER, "Message " + i));
        }

        CompactionEngine.CompactionResult result = engine.process(messages, true, true);
        assertNotNull(result);
        assertTrue(result.overflow());
    }

    @Test
    void process_withPreviousSummary() {
        CompactionEngine engine = new CompactionEngine();
        List<Message> messages = new ArrayList<>();
        // Add a previous compaction summary
        messages.add(createSummaryMessage("## Goal\n- Previous task"));
        for (int i = 0; i < 10; i++) {
            messages.add(createMessage(Role.USER, "Message " + i));
            messages.add(createMessage(Role.ASSISTANT, "Response " + i));
        }

        CompactionEngine.CompactionResult result = engine.process(messages, false, false);
        // Should detect previousSummary
        assertNotNull(result.previousSummary());
    }

    @Test
    void pruneToolResults_skipsRecentTurns() {
        CompactionEngine engine = new CompactionEngine();
        List<Message> messages = new ArrayList<>();
        // 2 recent turns should be protected
        messages.add(createMessage(Role.USER, "Recent 1"));
        messages.add(createMessage(Role.ASSISTANT, "Response 1"));
        messages.add(createMessage(Role.USER, "Recent 2"));
        messages.add(createMessage(Role.ASSISTANT, "Response 2"));

        List<Message> result = engine.pruneToolResults(messages);
        // Recent messages should not be pruned
        assertEquals(messages.size(), result.size());
    }

    @Test
    void summaryTemplate_isOpencodeFormat() {
        // Verify the SUMMARY_TEMPLATE matches opencode's 7-section format
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("## Goal"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("## Constraints & Preferences"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("## Progress"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("### Done"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("### In Progress"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("### Blocked"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("## Key Decisions"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("## Next Steps"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("## Critical Context"));
        assertTrue(CompactionEngine.SUMMARY_TEMPLATE.contains("## Relevant Files"));
    }

    @Test
    void compactionRoute_values() {
        assertEquals("NONE", CompactionEngine.CompactionRoute.NONE.name());
        assertEquals("COMPACT", CompactionEngine.CompactionRoute.COMPACT.name());
        assertEquals("TRUNCATE_TOOL_RESULTS_ONLY",
            CompactionEngine.CompactionRoute.TRUNCATE_TOOL_RESULTS_ONLY.name());
    }

    @Test
    void constants_matchOpencode() {
        assertEquals(20_000, CompactionEngine.PRUNE_MINIMUM);
        assertEquals(40_000, CompactionEngine.PRUNE_PROTECT);
        assertEquals(2_000, CompactionEngine.TOOL_OUTPUT_MAX_CHARS);
        assertTrue(CompactionEngine.PRUNE_PROTECTED_TOOLS.contains("skill"));
    }

    // ── Helpers ──

    private Message createMessage(Role role, String text) {
        return new Message(
            java.util.UUID.randomUUID().toString(),
            "test-session",
            role,
            null, null,
            List.of(new MessagePart.TextPart(text)),
            "test-agent",
            null, null, null, null, null, null,
            Instant.now()
        );
    }

    private Message createSummaryMessage(String summaryText) {
        return new Message(
            java.util.UUID.randomUUID().toString(),
            "test-session",
            Role.ASSISTANT,
            null, null,
            List.of(new MessagePart.TextPart(summaryText, true)),
            "compaction",
            null, null, null, summaryText, null, null,
            Instant.now()
        );
    }
}
