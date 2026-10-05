package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for builtin tools: ReadTool, WriteTool, ShellTool, GlobTool, ApplyPatchTool.
 */
class BuiltinToolsTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolContext defaultContext;

    @BeforeEach
    void setUp() {
        defaultContext = ToolContext.of(
            "test-session", "test-msg", "test-agent", "test-call",
            List.of(), Map.of(), m -> {}, (p, t, d) -> true
        );
    }

    // ── WriteTool ──

    @Test
    void writeToolCreatesNewFile() throws IOException {
        WriteTool tool = new WriteTool();
        var args = mapper.createObjectNode();
        args.put("filePath", tempDir.resolve("hello.txt").toString());
        args.put("content", "Hello, World!");

        ToolResult result = tool.execute(args, defaultContext);
        assertEquals("Wrote file successfully.", result.output());

        String content = Files.readString(tempDir.resolve("hello.txt"));
        assertEquals("Hello, World!", content);
    }

    @Test
    void writeToolOverwritesExistingFile() throws IOException {
        Path file = tempDir.resolve("existing.txt");
        Files.writeString(file, "old content");

        WriteTool tool = new WriteTool();
        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("content", "new content");

        ToolResult result = tool.execute(args, defaultContext);
        assertEquals("Wrote file successfully.", result.output());

        String content = Files.readString(file);
        assertEquals("new content", content);
    }

    @Test
    void writeToolCreatesParentDirectories() throws IOException {
        WriteTool tool = new WriteTool();
        var args = mapper.createObjectNode();
        args.put("filePath", tempDir.resolve("a/b/c/deep.txt").toString());
        args.put("content", "deep file");

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(Files.exists(tempDir.resolve("a/b/c/deep.txt")));
    }

    // ── ReadTool ──

    @Test
    void readToolReadsFile() throws IOException {
        Path file = tempDir.resolve("readme.txt");
        Files.writeString(file, "line1\nline2\nline3\n");

        ReadTool tool = new ReadTool();
        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(result.output().contains("line1"));
        assertTrue(result.output().contains("line2"));
        assertTrue(result.output().contains("line3"));
    }

    @Test
    void readToolWithOffset() throws IOException {
        Path file = tempDir.resolve("offset.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5\n");

        ReadTool tool = new ReadTool();
        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("offset", 3);

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(result.output().contains("line3"));
        // With <content> format, line1 may appear in the path but not in the content lines
        assertTrue(result.output().contains("3: line3"));
    }

    @Test
    void readToolFileNotFound() throws IOException {
        ReadTool tool = new ReadTool();
        var args = mapper.createObjectNode();
        args.put("filePath", "/nonexistent/file.txt");

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(result.output().toLowerCase().contains("not found") || result.title().equals("Error"));
    }

    @Test
    void readToolDirectory() throws IOException {
        ReadTool tool = new ReadTool();
        var args = mapper.createObjectNode();
        args.put("filePath", tempDir.toString());

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(result.output().contains("<type>directory</type>"));
    }

    // ── ShellTool ──

    @Test
    void shellToolEchoCommand() throws IOException {
        ShellTool tool = new ShellTool();
        var args = mapper.createObjectNode();
        args.put("command", "echo hello world");
        args.put("description", "Echo hello world");

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(result.output().contains("hello world"));
    }

    @Test
    void shellToolExitCode() throws IOException {
        ShellTool tool = new ShellTool();
        var args = mapper.createObjectNode();
        args.put("command", "exit 1");
        args.put("description", "Exit with code 1");

        ToolResult result = tool.execute(args, defaultContext);
        // exit 1 produces no output, just the (no output) marker
        assertNotNull(result.output());
    }

    // ── GlobTool ──

    @Test
    void globToolFindsFiles() throws IOException {
        Files.writeString(tempDir.resolve("Test.java"), "class Test {}");
        Files.writeString(tempDir.resolve("Util.java"), "class Util {}");
        Files.writeString(tempDir.resolve("readme.md"), "# readme");

        GlobTool tool = new GlobTool();
        var args = mapper.createObjectNode();
        args.put("pattern", "*.java");
        args.put("path", tempDir.toString());

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(result.output().contains("Test.java"));
        assertTrue(result.output().contains("Util.java"));
        assertFalse(result.output().contains("readme.md"));
    }

    // ── ApplyPatchTool ──

    @Test
    void applyPatchAddFile() throws IOException {
        ApplyPatchTool tool = new ApplyPatchTool();
        String patch = "*** Begin Patch\n" +
            "*** Add File: " + tempDir.resolve("new.txt") + "\n" +
            "+new file content\n" +
            "*** End Patch\n";

        var args = mapper.createObjectNode();
        args.put("patchText", patch);

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(Files.exists(tempDir.resolve("new.txt")));
        String content = Files.readString(tempDir.resolve("new.txt"));
        assertTrue(content.contains("new file content"));
    }

    @Test
    void applyPatchEmptyPatch() throws IOException {
        ApplyPatchTool tool = new ApplyPatchTool();
        var args = mapper.createObjectNode();
        args.put("patchText", "");

        ToolResult result = tool.execute(args, defaultContext);
        assertTrue(result.title().equals("Error") || result.output().toLowerCase().contains("required"));
    }
}
