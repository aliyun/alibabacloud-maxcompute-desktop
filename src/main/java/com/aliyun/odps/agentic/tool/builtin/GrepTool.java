package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 内置 grep 工具，用于按模式搜索文件内容。
 * 优先使用 {@code rg}，不可用时回退到 Java 实现。
 */
public class GrepTool implements ToolDef {

    private static final String ID = "grep";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = ResourceLoader.load("tools/grep.txt");
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MATCH_LIMIT = 100;

    @Override
    public String getId() { return ID; }

    // 只读工具：可与其他只读工具并行执行（0.4.0）。
    @Override
    public boolean isReadOnly() { return true; }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 返回 grep 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "pattern": "ProviderOptions",
     *   "path": "/repo/src/main/java",
     *   "include": "*.java"
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode pattern = MAPPER.createObjectNode();
        pattern.put("type", "string");
        pattern.put("description", "The regex pattern to search for in file contents");
        properties.set("pattern", pattern);

        ObjectNode path = MAPPER.createObjectNode();
        path.put("type", "string");
        path.put("description", "The directory to search in. Defaults to the current working directory.");
        properties.set("path", path);

        ObjectNode include = MAPPER.createObjectNode();
        include.put("type", "string");
        include.put("description", "File pattern to include in the search (e.g. \"*.js\", \"*.{ts,tsx}\")");
        properties.set("include", include);

        schema.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("pattern");
        schema.set("required", required);

        return schema;
    }

    /**
     * 按正则表达式搜索文件内容。
     *
     * <p>执行流程：
     * <ol>
     *   <li>提取 {@code pattern}（正则表达式）、{@code path}（搜索目录）和 {@code include}（文件名过滤 glob）</li>
     *   <li>优先使用 {@code rg}（ripgrep）执行搜索——速度更快且自动忽略 {@code .gitignore}</li>
     *   <li>若 {@code rg} 不可用，回退到 Java 实现：递归遍历文件，逐行正则匹配</li>
     *   <li>结果按「文件:行号:内容」分组输出，最多返回 100 条匹配</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        if (!args.has("pattern") || args.get("pattern").isNull()) {
            return ToolResult.error("pattern is required");
        }
        String pattern = args.get("pattern").asText();
        String searchPath = args.has("path") && !args.get("path").isNull() ? args.get("path").asText() : ".";
        String include = args.has("include") && !args.get("include").isNull() ? args.get("include").asText() : null;

        if (pattern == null || pattern.isEmpty()) {
            return ToolResult.error("pattern is required");
        }

        // Try ripgrep first
        if (isRipgrepAvailable()) {
            return executeWithRipgrep(pattern, searchPath, include);
        }

        // Fallback to Java-based search
        return executeWithJava(pattern, searchPath, include);
    }

    private boolean isRipgrepAvailable() {
        try {
            Process process = new ProcessBuilder("rg", "--version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private ToolResult executeWithRipgrep(String pattern, String path, String include) {
        try {
            var command = new ArrayList<String>();
            command.add("rg");
            command.add("--line-number");
            command.add("--no-heading");
            command.add("--with-filename");
            command.add("--sort=modified");
            if (include != null) {
                command.add("--glob=" + include);
            }
            command.add(pattern);
            command.add(path);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Parse ripgrep output into structured format
            List<GrepMatch> matches = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // rg output format: path:line:text
                    int firstColon = line.indexOf(':');
                    if (firstColon == -1) continue;
                    int secondColon = line.indexOf(':', firstColon + 1);
                    if (secondColon == -1) continue;

                    String filePath = line.substring(0, firstColon);
                    String lineNumStr = line.substring(firstColon + 1, secondColon);
                    String text = line.substring(secondColon + 1);

                    try {
                        int lineNum = Integer.parseInt(lineNumStr);
                        matches.add(new GrepMatch(filePath, lineNum, text));
                    } catch (NumberFormatException ignored) {}
                }
            }

            process.waitFor(30, TimeUnit.SECONDS);

            if (matches.isEmpty()) {
                return ToolResult.of(pattern, "No files found");
            }

            return formatMatches(pattern, matches);

        } catch (Exception e) {
            return ToolResult.error("Grep with ripgrep failed: " + e.getMessage());
        }
    }

    private ToolResult executeWithJava(String pattern, String searchPath, String include) {
        try {
            Path basePath = Path.of(searchPath);
            if (!Files.exists(basePath)) {
                return ToolResult.error("Path not found: " + searchPath);
            }

            java.util.regex.Pattern regex = java.util.regex.Pattern.compile(pattern);

            var pathMatcher = include != null
                ? basePath.getFileSystem().getPathMatcher("glob:" + include)
                : null;

            GitignoreFilter filter = GitignoreFilter.forRoot(basePath);

            // Collect candidate files, apply gitignore filter, then sort by modification time
            List<Path> candidateFiles;
            try (var walkStream = Files.walk(basePath)) {
                candidateFiles = walkStream
                    .filter(p -> !filter.shouldSkip(p, basePath))
                    .filter(Files::isRegularFile)
                    .filter(p -> pathMatcher == null || pathMatcher.matches(basePath.relativize(p)))
                    .limit(500)
                    .collect(java.util.stream.Collectors.toList());
            }
            GitignoreFilter.sortByModifiedTimeDesc(candidateFiles);

            List<GrepMatch> matches = new ArrayList<>();
            for (Path p : candidateFiles) {
                try {
                    var lines = Files.readAllLines(p);
                    for (int i = 0; i < lines.size(); i++) {
                        if (regex.matcher(lines.get(i)).find()) {
                            matches.add(new GrepMatch(p.toString(), i + 1, lines.get(i)));
                        }
                    }
                } catch (IOException ignored) {}
            }

            if (matches.isEmpty()) {
                return ToolResult.of(pattern, "No files found");
            }

            return formatMatches(pattern, matches);

        } catch (IOException e) {
            return ToolResult.error("Grep search failed: " + e.getMessage());
        }
    }

    private ToolResult formatMatches(String pattern, List<GrepMatch> matches) {
        int total = matches.size();
        boolean truncated = total > MATCH_LIMIT;
        List<GrepMatch> displayed = truncated ? matches.subList(0, MATCH_LIMIT) : matches;

        List<String> output = new ArrayList<>();
        output.add("Found " + total + " matches" + (truncated ? " (showing first " + MATCH_LIMIT + ")" : ""));

        String currentFile = "";
        for (GrepMatch match : displayed) {
            if (!currentFile.equals(match.path)) {
                if (!currentFile.isEmpty()) output.add("");
                currentFile = match.path;
                output.add(match.path + ":");
            }
            String text = match.text;
            if (text.length() > MAX_LINE_LENGTH) {
                text = text.substring(0, MAX_LINE_LENGTH) + "...";
            }
            output.add("  Line " + match.line + ": " + text);
        }

        if (truncated) {
            output.add("");
            output.add("(Results truncated: showing " + MATCH_LIMIT + " of " + total
                + " matches (" + (total - MATCH_LIMIT) + " hidden). Consider using a more specific path or pattern.)");
        }

        return ToolResult.of(pattern, String.join("\n", output));
    }

    private record GrepMatch(String path, int line, String text) {}
}
