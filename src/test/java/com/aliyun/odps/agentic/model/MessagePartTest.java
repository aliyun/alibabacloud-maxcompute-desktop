package com.aliyun.odps.agentic.model;

import org.junit.jupiter.api.*;
import com.aliyun.odps.agentic.model.MessagePart.*;
import com.aliyun.odps.agentic.model.ToolCallState.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for MessagePart — all sealed variants.
 * Ensures construction, accessor, and type-preserving behavior.
 */
class MessagePartTest {

    // ── TextPart ──
    @Test
    void textPartConstruction() {
        TextPart tp = new TextPart("hello world");
        assertEquals("hello world", tp.text());
        assertFalse(tp.synthetic());
    }

    @Test
    void textPartWithSynthetic() {
        TextPart tp = new TextPart("thinking...", true);
        assertTrue(tp.synthetic());
    }

    @Test
    void textPartFull() {
        TextPart tp = new TextPart("hello", true, false);
        assertTrue(tp.synthetic());
        assertFalse(tp.ignored());
    }

    // ── SubtaskPart ──
    @Test
    void subtaskPartConstruction() {
        SubtaskPart sp = new SubtaskPart("Do the task", "Research phase");
        assertEquals("Do the task", sp.prompt());
        assertEquals("Research phase", sp.description());
        assertNull(sp.sessionID());
    }

    @Test
    void subtaskPartWithSessionId() {
        SubtaskPart sp = new SubtaskPart("Do the task", "Research", "sess-123");
        assertEquals("sess-123", sp.sessionID());
    }

    // ── ReasoningPart ──
    @Test
    void reasoningPartConstruction() {
        ReasoningPart rp = new ReasoningPart("thinking...");
        assertEquals("thinking...", rp.text());
        assertNull(rp.time());
    }

    @Test
    void reasoningPartWithTime() {
        ReasoningTime time = new ReasoningTime(1000L, 2000L);
        ReasoningPart rp = new ReasoningPart("thinking...", time);
        assertEquals(1000L, rp.time().start());
        assertEquals(2000L, rp.time().end());
    }

    // ── FilePart ──
    @Test
    void filePartConstruction() {
        FilePart fp = new FilePart("http://example.com/f.java", "f.java", "text/java");
        assertEquals("http://example.com/f.java", fp.url());
        assertEquals("f.java", fp.filename());
        assertEquals("text/java", fp.mime());
        assertNull(fp.source());
    }

    // ── ImagePart ──
    @Test
    void imagePartFromBase64() {
        ImagePart ip = new ImagePart("base64data==", "image/png");
        assertEquals("base64data==", ip.data());
        assertEquals("image/png", ip.mimeType());
        assertTrue(ip.isBase64());
    }

    @Test
    void imagePartFromUrl() {
        ImagePart ip = ImagePart.fromUrl("http://img.com/a.png", "image/png");
        assertNull(ip.data());
        assertEquals("http://img.com/a.png", ip.url());
        assertFalse(ip.isBase64());
    }

    // ── DocumentPart ──
    @Test
    void documentPartConstruction() {
        DocumentPart dp = new DocumentPart("pdfdata==", "application/pdf");
        assertEquals("pdfdata==", dp.data());
        assertEquals("application/pdf", dp.mimeType());
        assertNull(dp.name());
    }

    // ── ToolPart ──
    @Test
    void toolPartConstruction() {
        ToolCallState state = new Pending(Map.of("cmd", "ls"), "raw");
        ToolPart tp = new ToolPart("shell", "call-1", state);
        assertEquals("shell", tp.tool());
        assertEquals("call-1", tp.callID());
        assertInstanceOf(Pending.class, tp.state());
        assertNull(tp.metadata());
    }

    @Test
    void toolPartWithMetadata() {
        ToolCallState state = new Pending();
        ToolPart tp = new ToolPart("read", "call-2", state, Map.of("key", "val"));
        assertEquals("val", tp.metadata().get("key"));
    }

    // ── ToolCallPart ──
    @Test
    void toolCallPartConstruction() {
        ToolCallPart tcp = new ToolCallPart("call-1", "shell", "{\"cmd\":\"ls\"}");
        assertEquals("call-1", tcp.callID());
        assertEquals("shell", tcp.name());
    }

    // ── ToolResultPart ──
    @Test
    void toolResultPartConstruction() {
        ToolResultPart trp = new ToolResultPart("call-1", "shell", "file1.txt\nfile2.txt", false);
        assertEquals("call-1", trp.callID());
        assertEquals("shell", trp.name());
        assertFalse(trp.isError());
    }

    @Test
    void toolResultPartError() {
        ToolResultPart trp = new ToolResultPart("call-1", "shell", "command failed", true);
        assertTrue(trp.isError());
    }

    @Test
    void toolResultMetadataRoundTripsAndOldSnapshotsStillLoad() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        MessagePart current = new ToolResultPart("call-1", "query", "2 rows", false,
            Map.of("rowCount", 2));
        MessagePart roundTripped = mapper.readValue(
            mapper.writeValueAsString(current), MessagePart.class);
        assertEquals(2, ((ToolResultPart) roundTripped).metadata().get("rowCount"));

        String oldJson = """
            {"type":"tool-result","callID":"call-1","name":"query",
             "output":"2 rows","isError":false}
            """;
        ToolResultPart old = (ToolResultPart) mapper.readValue(oldJson, MessagePart.class);
        assertEquals("2 rows", old.output());
        assertTrue(old.metadata().isEmpty());
    }

    @Test
    void imageReferenceRoundTripsWithoutEmbeddingImageBytes() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        MessagePart reference = new MessagePart.ImageReferencePart("file-1", "/tmp/image.png",
            "image/png", "abc123", 100, 80);
        String json = mapper.writeValueAsString(reference);
        assertFalse(json.contains("base64"));
        var restored = (MessagePart.ImageReferencePart) mapper.readValue(json, MessagePart.class);
        assertEquals("abc123", restored.sha256());
        assertEquals("/tmp/image.png", restored.normalizedPath());
    }

    // ── StepStartPart ──
    @Test
    void stepStartPartConstruction() {
        StepStartPart ssp = new StepStartPart("snap-1");
        assertEquals("snap-1", ssp.snapshot());
    }

    // ── StepFinishPart ──
    @Test
    void stepFinishPartConstruction() {
        StepFinishPart sfp = new StepFinishPart("stop", null, "snap-1");
        assertEquals("stop", sfp.finishReason());
        assertNull(sfp.usage());
        assertEquals("snap-1", sfp.snapshot());
    }

    // ── SnapshotPart ──
    @Test
    void snapshotPartConstruction() {
        SnapshotPart sp = new SnapshotPart("snap-1", List.of("a.txt", "b.txt"));
        assertEquals("snap-1", sp.id());
        assertEquals(2, sp.files().size());
    }

    // ── PatchPart ──
    @Test
    void patchPartConstruction() {
        PatchPart pp = new PatchPart("@@ -1,3 +1,3 @@");
        assertEquals("@@ -1,3 +1,3 @@", pp.diff());
    }

    // ── AgentPart ──
    @Test
    void agentPartWithNameOnly() {
        AgentPart ap = new AgentPart("code-agent");
        assertEquals("code-agent", ap.name());
        assertNull(ap.source());
    }

    @Test
    void agentPartWithSource() {
        AgentSource src = new AgentSource("claude", 0, 6);
        AgentPart ap = new AgentPart("code-agent", src);
        assertEquals("claude", ap.source().value());
        assertEquals(0, ap.source().start());
        assertEquals(6, ap.source().end());
    }

    // ── RetryPart ──
    @Test
    void retryPartConstruction() {
        RetryPart rp = new RetryPart(2, "rate_limit");
        assertEquals(2, rp.attempt());
        assertEquals("rate_limit", rp.reason());
    }

    // ── CompactionPart ──
    @Test
    void compactionPartConstruction() {
        CompactionPart cp = new CompactionPart(true, "Summary of conversation");
        assertTrue(cp.auto());
        assertFalse(cp.overflow());
        assertNull(cp.tailStartId());
        assertEquals("Summary of conversation", cp.summary());
    }

    @Test
    void compactionPartFull() {
        CompactionPart cp = new CompactionPart(false, true, "msg-5", "Summary");
        assertFalse(cp.auto());
        assertTrue(cp.overflow());
        assertEquals("msg-5", cp.tailStartId());
    }

    // ── Sealed interface instance checks ──
    @Test
    void sealedInterfaceInstanceChecks() {
        MessagePart text = new TextPart("hi");
        MessagePart tool = new ToolPart("shell", "c1", new Pending());
        MessagePart retry = new RetryPart(1, "timeout");
        MessagePart compaction = new CompactionPart(true, "summary");

        assertInstanceOf(TextPart.class, text);
        assertInstanceOf(ToolPart.class, tool);
        assertInstanceOf(RetryPart.class, retry);
        assertInstanceOf(CompactionPart.class, compaction);
    }
}
