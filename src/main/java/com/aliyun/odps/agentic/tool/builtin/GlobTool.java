package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 内置 glob 工具，用于查找匹配模式的文件。
 * 支持在指定目录下按 glob 规则搜索，并自动过滤忽略路径。
 */
public class GlobTool implements ToolDef {

    private static final String ID = "glob";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = ResourceLoader.load("tools/glob.txt");
    private static final int RESULT_LIMIT = 100;

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
     * 返回 glob 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "pattern": "*.java",
     *   "path": "/repo/src"
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
        pattern.put("description", "The glob pattern to match files against");
        properties.set("pattern", pattern);

        ObjectNode path = MAPPER.createObjectNode();
        path.put("type", "string");
        path.put("description", "The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for the default behavior. Must be a valid directory path if provided.");
        properties.set("path", path);

        schema.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("pattern");
        schema.set("required", required);

        return schema;
    }

    /**
     * 按 glob 模式搜索文件。
     *
     * <p>执行流程：
     * <ol>
     *   <li>提取 {@code pattern}（glob 表达式）和 {@code path}（搜索根目录，默认当前目录）</li>
     *   <li>使用 {@link java.nio.file.FileSystem#getPathMatcher} 编译 glob 规则</li>
     *   <li>递归遍历目录，过滤 {@code .gitignore} 中的忽略路径</li>
     *   <li>按文件修改时间倒序排列，最多返回 100 条结果</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String pattern = ToolDef.extractString(args, "pattern", "glob_pattern");
        if (pattern == null || pattern.isEmpty()) {
            pattern = ToolDef.extractString(args, "glob", "filePattern");
        }
        if (pattern == null || pattern.isEmpty()) {
            return ToolResult.error("pattern is required");
        }
        String searchDir = args.has("path") && !args.get("path").isNull() ? args.get("path").asText() : ".";

        try {
            Path basePath = Path.of(searchDir);
            if (!Files.exists(basePath)) {
                return ToolResult.error("Directory not found: " + searchDir);
            }
            if (!Files.isDirectory(basePath)) {
                return ToolResult.error("glob path must be a directory: " + searchDir);
            }

            var pathMatcher = basePath.getFileSystem().getPathMatcher("glob:" + pattern);

            GitignoreFilter filter = GitignoreFilter.forRoot(basePath);

            List<Path> matched = new ArrayList<>();
            boolean truncated = false;

            try (Stream<Path> paths = Files.walk(basePath)) {
                var iter = paths
                    .filter(p -> !filter.shouldSkip(p, basePath))
                    .filter(Files::isRegularFile)
                    .filter(p -> pathMatcher.matches(basePath.relativize(p)))
                    .iterator();

                while (iter.hasNext() && matched.size() <= RESULT_LIMIT) {
                    matched.add(iter.next());
                }

                if (matched.size() > RESULT_LIMIT) {
                    truncated = true;
                    matched = matched.subList(0, RESULT_LIMIT);
                }
            }

            // Sort by modification time, newest first
            GitignoreFilter.sortByModifiedTimeDesc(matched);

            if (matched.isEmpty()) {
                return ToolResult.of(pattern, "No files found");
            }

            List<String> output = new ArrayList<>();
            for (Path p : matched) {
                output.add(p.toAbsolutePath().toString());
            }

            if (truncated) {
                output.add("");
                output.add("(Results are truncated: showing first " + RESULT_LIMIT + " results. Consider using a more specific path or pattern.)");
            }

            return ToolResult.of(pattern, String.join("\n", output));

        } catch (IOException e) {
            return ToolResult.error("Glob search failed: " + e.getMessage());
        }
    }
}
