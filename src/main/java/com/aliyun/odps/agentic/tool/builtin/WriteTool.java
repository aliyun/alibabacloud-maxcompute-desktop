package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.FileReadTracker;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内置写入工具，用于创建或覆盖文件。
 * 对已存在文件会执行先读后写检查，避免在未查看内容时直接覆盖。
 */
public class WriteTool implements ToolDef {

    private static final String ID = "write";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = ResourceLoader.load("tools/write.txt");

    @Override
    public String getId() { return ID; }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 返回 write 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "filePath": "/abs/path/output.txt",
     *   "content": "hello world"
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "string");
        content.put("description", "The content to write to the file");
        properties.set("content", content);

        ObjectNode filePath = MAPPER.createObjectNode();
        filePath.put("type", "string");
        filePath.put("description", "The absolute path to the file to write (must be absolute, not relative)");
        properties.set("filePath", filePath);

        schema.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("content");
        required.add("filePath");
        schema.set("required", required);

        return schema;
    }

    /**
     * 执行文件写入。
     *
     * <p>执行流程：
     * <ol>
     *   <li>提取参数 {@code filePath}（绝对路径）和 {@code content}（文件内容）</li>
     *   <li>若文件已存在，检查是否已通过 ReadTool 读取过——未读取过则拒绝覆盖</li>
     *   <li>自动创建不存在的父目录</li>
     *   <li>将 {@code content} 写入目标路径（创建或覆盖）</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String filePath = ToolDef.extractString(args, "filePath", "file_path");
        String content = args.has("content") ? args.get("content").asText() : null;

        if (filePath == null || filePath.isEmpty()) {
            return ToolResult.error("filePath is required");
        }
        if (content == null) {
            return ToolResult.error("content is required");
        }

        try {
            Path path = Path.of(filePath);

            // Read-before-write guard: existing files must be read first
            if (Files.exists(path)) {
                FileReadTracker tracker = FileReadTracker.from(context);
                if (tracker != null && !tracker.hasBeenRead(path.toAbsolutePath().toString())) {
                    return ToolResult.error(
                        "You must read this file before overwriting it. Use the Read tool first to see its current contents.");
                }
            }

            // Create parent directories if needed
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            Files.writeString(path, content);

            return ToolResult.of(filePath, "Wrote file successfully.");

        } catch (IOException e) {
            return ToolResult.error("Failed to write file: " + e.getMessage());
        }
    }
}
