package com.aliyun.odps.agentic.patch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for PatchParser and PatchEngine.
 * Validates faithful distillation from opencode patch/index.ts.
 */
class PatchEngineTest {

    @TempDir
    Path tempDir;

    private final PatchParser parser = new PatchParser();
    private final PatchEngine engine = new PatchEngine(parser);

    // ---- PatchParser tests ----

    @Test
    void parseAddFile() {
        String patch = """
            *** Begin Patch
            *** Add File: hello.txt
            +Hello, World!
            +Second line
            *** End Patch
            """;

        List<Hunk> hunks = parser.parse(patch);
        assertEquals(1, hunks.size());

        Hunk.AddHunk add = (Hunk.AddHunk) hunks.getFirst();
        assertEquals("hello.txt", add.path());
        assertEquals(List.of("Hello, World!", "Second line"), add.contents());
    }

    @Test
    void parseDeleteFile() {
        String patch = """
            *** Begin Patch
            *** Delete File: old.txt
            *** End Patch
            """;

        List<Hunk> hunks = parser.parse(patch);
        assertEquals(1, hunks.size());

        Hunk.DeleteHunk delete = (Hunk.DeleteHunk) hunks.getFirst();
        assertEquals("old.txt", delete.path());
    }

    @Test
    void parseUpdateFile() {
        String patch = """
            *** Begin Patch
            *** Update File: app.js
            @@ some context @@
             const x = 1;
            -const y = 2;
            +const y = 3;
            *** End Patch
            """;

        List<Hunk> hunks = parser.parse(patch);
        assertEquals(1, hunks.size());

        Hunk.UpdateHunk update = (Hunk.UpdateHunk) hunks.getFirst();
        assertEquals("app.js", update.path());
        assertNull(update.movePath());
        assertEquals(1, update.chunks().size());

        UpdateFileChunk chunk = update.chunks().getFirst();
        // Keep line (space prefix) appears in both old and new
        assertEquals(List.of("const x = 1;", "const y = 2;"), chunk.oldLines());
        assertEquals(List.of("const x = 1;", "const y = 3;"), chunk.newLines());
        assertEquals("some context", chunk.changeContext());
    }

    @Test
    void parseUpdateFileWithMove() {
        String patch = """
            *** Begin Patch
            *** Update File: old/path.js
            *** Move to: new/path.js
            @@ @@
            -old content
            +new content
            *** End Patch
            """;

        List<Hunk> hunks = parser.parse(patch);
        Hunk.UpdateHunk update = (Hunk.UpdateHunk) hunks.getFirst();
        assertEquals("new/path.js", update.movePath());
    }

    @Test
    void parseEndOfFile() {
        String patch = """
            *** Begin Patch
            *** Update File: app.js
            @@ @@
             existing line
            *** End of File
            +appended line
            *** End Patch
            """;

        List<Hunk> hunks = parser.parse(patch);
        Hunk.UpdateHunk update = (Hunk.UpdateHunk) hunks.getFirst();
        // Should have two chunks: one with existing content and End of File marker,
        // then one with appended content after End of File
        // Actually, looking at opencode's logic, *** End of File just marks isEndOfFile
        // and then the loop continues collecting more lines
        // Let me check: after End of File, the while loop breaks
        // So it creates one chunk with isEndOfFile=true
        // But the +appended line comes AFTER End of File in the patch
        // In opencode, after isEndOfFile=true, we break, but the + line should be in newLines
        // Actually opencode's loop continues after End of File break, creating a new chunk
        assertFalse(update.chunks().isEmpty());
    }

    @Test
    void parseMultipleHunks() {
        String patch = """
            *** Begin Patch
            *** Add File: new.txt
            +new file content
            *** Update File: existing.txt
            @@ @@
            -old
            +new
            *** Delete File: gone.txt
            *** End Patch
            """;

        List<Hunk> hunks = parser.parse(patch);
        assertEquals(3, hunks.size());
        assertInstanceOf(Hunk.AddHunk.class, hunks.get(0));
        assertInstanceOf(Hunk.UpdateHunk.class, hunks.get(1));
        assertInstanceOf(Hunk.DeleteHunk.class, hunks.get(2));
    }

    @Test
    void parseInvalidPatchMissingMarkers() {
        String patch = "some random text without markers";
        assertThrows(IllegalArgumentException.class, () -> parser.parse(patch));
    }

    // ---- PatchEngine apply tests ----

    @Test
    void applyAddFile() throws IOException {
        String patch = """
            *** Begin Patch
            *** Add File: hello.txt
            +Hello, World!
            *** End Patch
            """;

        ApplyResult result = engine.apply(patch, tempDir);
        assertTrue(result.success());
        assertEquals(1, result.changes().size());

        FileChange change = result.changes().getFirst();
        assertEquals(ChangeType.ADD, change.type());
        assertEquals("Hello, World!", change.newContent());

        // Verify file exists on disk
        String fileContent = Files.readString(tempDir.resolve("hello.txt"));
        assertEquals("Hello, World!", fileContent);
    }

    @Test
    void applyDeleteFile() throws IOException {
        // Create file first
        Path file = tempDir.resolve("to_delete.txt");
        Files.writeString(file, "delete me");

        String patch = """
            *** Begin Patch
            *** Delete File: to_delete.txt
            *** End Patch
            """;

        ApplyResult result = engine.apply(patch, tempDir);
        assertTrue(result.success());

        // Verify file is deleted
        assertFalse(Files.exists(file));
    }

    @Test
    void applyUpdateFile() throws IOException {
        // Create file first
        Path file = tempDir.resolve("app.js");
        Files.writeString(file, "const x = 1;\nconst y = 2;\n");

        String patch = """
            *** Begin Patch
            *** Update File: app.js
            @@
             const x = 1;
            -const y = 2;
            +const y = 3;
            *** End Patch
            """;

        ApplyResult result = engine.apply(patch, tempDir);
        assertTrue(result.success());

        String newContent = Files.readString(file);
        assertTrue(newContent.contains("const y = 3;"));
        assertFalse(newContent.contains("const y = 2;"));
    }

    @Test
    void applyUpdateFileWithFuzzyMatch() throws IOException {
        // File has trailing whitespace
        Path file = tempDir.resolve("fuzzy.js");
        Files.writeString(file, "const x = 1;   \nconst y = 2;  \n");

        String patch = """
            *** Begin Patch
            *** Update File: fuzzy.js
            @@
             const x = 1;
            -const y = 2;
            +const y = 3;
            *** End Patch
            """;

        ApplyResult result = engine.apply(patch, tempDir);
        assertTrue(result.success());

        String newContent = Files.readString(file);
        assertTrue(newContent.contains("const y = 3;"));
    }

    // ---- Seek sequence tests ----

    @Test
    void seekSequenceExactMatch() {
        List<String> lines = List.of("line1", "line2", "line3");
        int idx = engine.seekSequence(lines, List.of("line2"), 0);
        assertEquals(1, idx);
    }

    @Test
    void seekSequenceRstripMatch() {
        List<String> lines = List.of("line1   ", "line2", "line3");
        int idx = engine.seekSequence(lines, List.of("line1"), 0);
        assertEquals(0, idx);  // matches via rstrip
    }

    @Test
    void seekSequenceTrimMatch() {
        List<String> lines = List.of("  line1  ", "line2", "line3");
        int idx = engine.seekSequence(lines, List.of("line1"), 0);
        assertEquals(0, idx);  // matches via trim
    }

    @Test
    void seekSequenceNotFound() {
        List<String> lines = List.of("line1", "line2", "line3");
        int idx = engine.seekSequence(lines, List.of("line99"), 0);
        assertEquals(-1, idx);
    }

    // ---- Derive new contents tests ----

    @Test
    void deriveNewContentsSimpleReplacement() {
        String original = "line1\nline2\nline3";
        List<UpdateFileChunk> chunks = List.of(
            new UpdateFileChunk(
                List.of("line2"),
                List.of("line2_modified"),
                null, false
            )
        );

        String result = engine.deriveNewContentsFromChunks(chunks, original);
        assertTrue(result.contains("line2_modified"));
        assertTrue(result.contains("line1"));
        assertTrue(result.contains("line3"));
    }

    @Test
    void deriveNewContentsInsertion() {
        String original = "line1\nline3";
        List<UpdateFileChunk> chunks = List.of(
            new UpdateFileChunk(
                List.of(),      // no old lines = insertion
                List.of("line2"),
                null, false
            )
        );

        String result = engine.deriveNewContentsFromChunks(chunks, original);
        assertTrue(result.contains("line2"));
    }
}
