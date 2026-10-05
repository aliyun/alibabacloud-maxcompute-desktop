package com.aliyun.odps.agentic.session;

import org.junit.jupiter.api.Test;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CompactionSelectorTest {

    @Test
    void selectWithTooFewMessagesReturnsNoHead() {
        var msgs = List.of(msg("1"), msg("2"), msg("3"));
        CompactionSelector.Selection sel = CompactionSelector.select(msgs);
        assertFalse(sel.shouldCompact());
        assertEquals(3, sel.tail().size());
        assertTrue(sel.head().isEmpty());
    }

    @Test
    void selectSplitsHeadAndTail() {
        var msgs = List.of(msg("1"), msg("2"), msg("3"), msg("4"), msg("5"), msg("6"));
        CompactionSelector.Selection sel = CompactionSelector.select(msgs, 2);
        assertTrue(sel.shouldCompact());
        assertEquals(4, sel.head().size());
        assertEquals(2, sel.tail().size());
    }

    @Test
    void selectDefaultTailSize() {
        var msgs = new java.util.ArrayList<Message>();
        for (int i = 0; i < 10; i++) msgs.add(msg(String.valueOf(i)));
        CompactionSelector.Selection sel = CompactionSelector.select(msgs);
        assertTrue(sel.shouldCompact());
        assertEquals(4, sel.tail().size());
        assertEquals(6, sel.head().size());
    }

    @Test
    void pruneLargeToolResultsTruncates() {
        String bigOutput = "A".repeat(5000);
        var trp = new MessagePart.ToolResultPart("call1", "read", bigOutput, false);
        var msg = new Message("id1", "s1", Role.USER, List.of(trp));

        List<Message> result = CompactionSelector.pruneLargeToolResults(List.of(msg), 100);
        var prunedPart = (MessagePart.ToolResultPart) result.get(0).parts().get(0);
        assertTrue(prunedPart.output().length() < bigOutput.length());
        assertTrue(prunedPart.output().contains("truncated"));
    }

    @Test
    void pruneSmallToolResultsUnchanged() {
        String smallOutput = "hello";
        var trp = new MessagePart.ToolResultPart("call1", "read", smallOutput, false);
        var msg = new Message("id1", "s1", Role.USER, List.of(trp));

        List<Message> result = CompactionSelector.pruneLargeToolResults(List.of(msg), 10000);
        var samePart = (MessagePart.ToolResultPart) result.get(0).parts().get(0);
        assertEquals(smallOutput, samePart.output());
    }

    private Message msg(String id) {
        return new Message(id, "sess", Role.USER, List.of(new MessagePart.TextPart("hi")));
    }
}
