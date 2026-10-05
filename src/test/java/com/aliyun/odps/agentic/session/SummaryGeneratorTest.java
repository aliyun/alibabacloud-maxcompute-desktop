package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.ToolCallState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SummaryGeneratorTest {

    private final SummaryGenerator gen = new SummaryGenerator();

    // ── generateTitle ──

    @Test
    void generateTitle_shortMessage() {
        Message msg = new Message("1", "s1", Role.USER, List.of(new MessagePart.TextPart("Hello world")));
        assertEquals("Hello world", gen.generateTitle(msg));
    }

    @Test
    void generateTitle_longMessage_truncated() {
        String longText = "a".repeat(100);
        Message msg = new Message("1", "s1", Role.USER, List.of(new MessagePart.TextPart(longText)));
        String title = gen.generateTitle(msg);
        assertTrue(title.endsWith("..."));
        assertTrue(title.length() <= 80);
    }

    @Test
    void generateTitle_nullMessage() {
        assertEquals("New Session", gen.generateTitle(null));
    }

    @Test
    void generateTitle_emptyContent() {
        Message msg = new Message("1", "s1", Role.USER, List.of(new MessagePart.TextPart("")));
        assertEquals("New Session", gen.generateTitle(msg));
    }

    // ── calculateDiffSummary ──

    @Test
    void calculateDiffSummary_emptyMessages() {
        assertEquals("No changes.", gen.calculateDiffSummary(List.of()));
    }

    @Test
    void calculateDiffSummary_nullMessages() {
        assertEquals("No changes.", gen.calculateDiffSummary(null));
    }

    @Test
    void calculateDiffSummary_noToolUsage() {
        Message msg = new Message("1", "s1", Role.USER, List.of(new MessagePart.TextPart("Just chatting")));
        assertEquals("No tool usage recorded.", gen.calculateDiffSummary(List.of(msg)));
    }

    @Test
    void calculateDiffSummary_mixedToolUsage() {
        Message readMsg = toolMessage("read");
        Message writeMsg = toolMessage("write");
        Message shellMsg = toolMessage("shell");
        Message editMsg = toolMessage("edit");

        String summary = gen.calculateDiffSummary(List.of(readMsg, writeMsg, shellMsg, editMsg));
        assertTrue(summary.contains("1 file(s) read"));
        assertTrue(summary.contains("1 file(s) written"));
        assertTrue(summary.contains("1 shell command(s)"));
        assertTrue(summary.contains("1 file(s) edited"));
    }

    @Test
    void calculateDiffSummary_multipleReads() {
        Message msg = new Message("1", "s1", Role.ASSISTANT, List.of(
            toolPart("read"), toolPart("read"), toolPart("read")
        ));
        String summary = gen.calculateDiffSummary(List.of(msg));
        assertTrue(summary.contains("3 file(s) read"));
    }

    @Test
    void calculateDiffSummary_searchTools() {
        Message msg = new Message("1", "s1", Role.ASSISTANT, List.of(
            toolPart("glob"), toolPart("grep")
        ));
        String summary = gen.calculateDiffSummary(List.of(msg));
        assertTrue(summary.contains("2 search(es)"));
    }

    @Test
    void calculateDiffSummary_unknownTool() {
        Message msg = new Message("1", "s1", Role.ASSISTANT, List.of(
            toolPart("custom_tool")
        ));
        String summary = gen.calculateDiffSummary(List.of(msg));
        assertTrue(summary.contains("1 other tool call(s)"));
    }

    @Test
    void calculateDiffSummary_patchAndApply() {
        Message msg = new Message("1", "s1", Role.ASSISTANT, List.of(
            toolPart("apply_patch")
        ));
        String summary = gen.calculateDiffSummary(List.of(msg));
        assertTrue(summary.contains("1 patch(es) applied"));
    }

    // ── helpers ──

    private Message toolMessage(String toolName) {
        return new Message("1", "s1", Role.ASSISTANT, List.of(toolPart(toolName)));
    }

    private MessagePart.ToolPart toolPart(String toolName) {
        return new MessagePart.ToolPart(toolName, "call-" + toolName, ToolCallState.pending());
    }
}
