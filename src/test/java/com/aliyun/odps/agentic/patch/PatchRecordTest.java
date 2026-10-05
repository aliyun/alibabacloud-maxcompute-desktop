package com.aliyun.odps.agentic.patch;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for ChangeType, FileChange, ApplyResult, and Hunk records.
 */
class PatchRecordTest {

    // -- ChangeType --

    @Test
    void changeTypeValues() {
        assertEquals(4, ChangeType.values().length);
        assertNotNull(ChangeType.ADD);
        assertNotNull(ChangeType.UPDATE);
        assertNotNull(ChangeType.DELETE);
        assertNotNull(ChangeType.MOVE);
    }

    @Test
    void changeTypeSymbols() {
        assertEquals("+", ChangeType.ADD.symbol());
        assertEquals("~", ChangeType.UPDATE.symbol());
        assertEquals("-", ChangeType.DELETE.symbol());
        assertEquals(">", ChangeType.MOVE.symbol());
    }

    @Test
    void changeTypeValueOf() {
        assertEquals(ChangeType.ADD, ChangeType.valueOf("ADD"));
        assertEquals(ChangeType.DELETE, ChangeType.valueOf("DELETE"));
    }

    // -- FileChange --

    @Test
    void fileChangeConstruction() {
        FileChange change = new FileChange(
            Path.of("/tmp/test.java"), "old content", "new content", ChangeType.UPDATE, "@@ -1 +1 @@"
        );
        assertEquals(Path.of("/tmp/test.java"), change.path());
        assertEquals("old content", change.oldContent());
        assertEquals("new content", change.newContent());
        assertEquals(ChangeType.UPDATE, change.type());
        assertEquals("@@ -1 +1 @@", change.diff());
    }

    @Test
    void fileChangeAddType() {
        FileChange change = new FileChange(Path.of("new.txt"), null, "content", ChangeType.ADD, null);
        assertEquals(ChangeType.ADD, change.type());
        assertNull(change.oldContent());
        assertNull(change.diff());
    }

    @Test
    void fileChangeDeleteType() {
        FileChange change = new FileChange(Path.of("old.txt"), "old", null, ChangeType.DELETE, null);
        assertEquals(ChangeType.DELETE, change.type());
        assertNull(change.newContent());
    }

    @Test
    void fileChangeEquality() {
        FileChange c1 = new FileChange(Path.of("a.txt"), "old", "new", ChangeType.UPDATE, null);
        FileChange c2 = new FileChange(Path.of("a.txt"), "old", "new", ChangeType.UPDATE, null);
        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
    }

    // -- ApplyResult --

    @Test
    void applyResultSuccess() {
        FileChange change = new FileChange(Path.of("f.txt"), null, "hello", ChangeType.ADD, null);
        ApplyResult result = ApplyResult.success(List.of(change));
        assertTrue(result.success());
        assertEquals(1, result.changes().size());
    }

    @Test
    void applyResultFailure() {
        ApplyResult result = ApplyResult.failure("patch conflict");
        assertFalse(result.success());
        assertTrue(result.changes().isEmpty());
    }

    @Test
    void applyResultAccessors() {
        ApplyResult result = new ApplyResult(List.of(), true);
        assertTrue(result.success());
        assertEquals(0, result.changes().size());
    }

    // -- Hunk sealed interface --

    @Test
    void addHunkConstruction() {
        Hunk.AddHunk hunk = new Hunk.AddHunk("src/Main.java", List.of("line1", "line2"));
        assertEquals("src/Main.java", hunk.path());
        assertEquals(2, hunk.contents().size());
    }

    @Test
    void deleteHunkConstruction() {
        Hunk.DeleteHunk hunk = new Hunk.DeleteHunk("old/File.java");
        assertEquals("old/File.java", hunk.path());
    }

    @Test
    void updateHunkConstruction() {
        UpdateFileChunk chunk = new UpdateFileChunk(List.of("old"), List.of("new"), null, false);
        Hunk.UpdateHunk hunk = new Hunk.UpdateHunk("src/File.java", null, List.of(chunk));
        assertEquals("src/File.java", hunk.path());
        assertNull(hunk.movePath());
        assertEquals(1, hunk.chunks().size());
    }

    @Test
    void updateHunkWithMove() {
        Hunk.UpdateHunk hunk = new Hunk.UpdateHunk("old/path.java", "new/path.java", List.of());
        assertEquals("new/path.java", hunk.movePath());
    }

    @Test
    void hunkSealedPatternMatching() {
        List<Hunk> hunks = List.of(
            new Hunk.AddHunk("a.txt", List.of("content")),
            new Hunk.DeleteHunk("b.txt"),
            new Hunk.UpdateHunk("c.txt", null, List.of())
        );

        for (Hunk hunk : hunks) {
            String type = switch (hunk) {
                case Hunk.AddHunk a -> "add:" + a.path();
                case Hunk.DeleteHunk d -> "delete:" + d.path();
                case Hunk.UpdateHunk u -> "update:" + u.path();
            };
            assertNotNull(type);
        }
    }
}
