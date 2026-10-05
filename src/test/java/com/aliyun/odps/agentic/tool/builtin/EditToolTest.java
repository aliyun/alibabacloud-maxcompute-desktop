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
 * Tests for EditTool — validates fuzzy matching and file editing.
 */
class EditToolTest {

    @TempDir
    Path tempDir;

    private final EditTool editTool = new EditTool();
    private final ObjectMapper mapper = new ObjectMapper();
    private ToolContext defaultContext;

    @BeforeEach
    void setUp() {
        defaultContext = ToolContext.of(
            "test-session", "test-msg", "test-agent", "test-call",
            List.of(), Map.of(), m -> {}, (p, t, d) -> true
        );
    }

    @Test
    void exactMatch() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World\nSecond line\n");

        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("oldString", "Hello World");
        args.put("newString", "Hello Universe");

        ToolResult result = editTool.execute(args, defaultContext);
        assertEquals("Edit applied successfully.", result.output());

        String content = Files.readString(file);
        assertTrue(content.contains("Hello Universe"));
        assertFalse(content.contains("Hello World"));
    }

    @Test
    void multiLineReplacement() throws IOException {
        Path file = tempDir.resolve("code.js");
        Files.writeString(file, "function old() {\n  return 1;\n}\n");

        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("oldString", "function old() {\n  return 1;\n}");
        args.put("newString", "function new() {\n  return 2;\n}");

        ToolResult result = editTool.execute(args, defaultContext);
        assertEquals("Edit applied successfully.", result.output());

        String content = Files.readString(file);
        assertTrue(content.contains("function new()"));
    }

    @Test
    void fuzzyMatchTrimWhitespace() throws IOException {
        Path file = tempDir.resolve("fuzzy.txt");
        Files.writeString(file, "  hello world  \nother line\n");

        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("oldString", "hello world");
        args.put("newString", "hello universe");

        ToolResult result = editTool.execute(args, defaultContext);
        // Should find via fuzzy match (trim)
        String content = Files.readString(file);
        assertTrue(content.contains("hello universe") || result.output().contains("replaced"));
    }

    @Test
    void noMatchReturnsError() throws IOException {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello World\n");

        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("oldString", "nonexistent text");
        args.put("newString", "replacement");

        ToolResult result = editTool.execute(args, defaultContext);
        assertTrue(result.output().contains("Could not find oldString"));
    }

    @Test
    void multipleMatchesWithoutReplaceAll() throws IOException {
        Path file = tempDir.resolve("multi.txt");
        Files.writeString(file, "foo bar foo\n");

        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("oldString", "foo");
        args.put("newString", "baz");

        ToolResult result = editTool.execute(args, defaultContext);
        // Should error about multiple matches
        assertTrue(result.output().contains("Found multiple matches for oldString"));
    }

    @Test
    void multipleMatchesWithReplaceAll() throws IOException {
        Path file = tempDir.resolve("multi.txt");
        Files.writeString(file, "foo bar foo\n");

        var args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("oldString", "foo");
        args.put("newString", "baz");
        args.put("replaceAll", true);

        ToolResult result = editTool.execute(args, defaultContext);
        String content = Files.readString(file);
        assertEquals("baz bar baz\n", content);
    }

    @Test
    void fileNotFound() throws IOException {
        var args = mapper.createObjectNode();
        args.put("filePath", "/nonexistent/file.txt");
        args.put("oldString", "text");
        args.put("newString", "replacement");

        ToolResult result = editTool.execute(args, defaultContext);
        assertTrue(result.output().contains("not found") || result.title().equals("Error"));
    }
}
