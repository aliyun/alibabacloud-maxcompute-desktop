package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.patch.ApplyResult;
import com.aliyun.odps.agentic.patch.ChangeType;
import com.aliyun.odps.agentic.patch.FileChange;
import com.aliyun.odps.agentic.patch.Hunk;
import com.aliyun.odps.agentic.patch.PatchEngine;
import com.aliyun.odps.agentic.patch.PatchParser;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内置补丁工具，用于将文本补丁应用到文件。
 * 支持新增、更新、删除和移动文件，并可在执行前完成补丁解析与校验。
 *
 * <p>补丁格式使用 {@code *** Begin Patch} / {@code *** End Patch} 包裹，
 * 可在一次调用中处理多个文件变更。
 */
public class ApplyPatchTool implements ToolDef {

    private static final Logger log = LoggerFactory.getLogger(ApplyPatchTool.class);

    private static final String ID = "apply_patch";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DESCRIPTION = ResourceLoader.load("tools/apply_patch.txt");

    @Override
    public String getId() { return ID; }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    /**
     * 返回 apply_patch 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "patchText": "*** Begin Patch\n*** Update File: src/App.java\n  context line\n- old line\n+ new line\n  context line\n*** End Patch"
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        // 参数 Schema 定义
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();
        ObjectNode patchText = MAPPER.createObjectNode();
        patchText.put("type", "string");
        patchText.put("description",
            "The full patch text that describes all changes to be made");
        properties.set("patchText", patchText);
        schema.set("properties", properties);

        var required = MAPPER.createArrayNode();
        required.add("patchText");
        schema.set("required", required);

        return schema;
    }

    /**
     * 解析并应用文本补丁。
     *
     * <p>执行流程：
     * <ol>
     *   <li>提取 {@code patchText} 参数（完整补丁文本）</li>
     *   <li>使用 {@link PatchParser} 解析补丁为 {@link Hunk} 列表（AddHunk / DeleteHunk / UpdateHunk）</li>
     *   <li>对涉及的文件路径请求编辑权限</li>
     *   <li>使用 {@link PatchEngine} 将所有 hunk 应用到文件系统</li>
     *   <li>返回带有变更类型前缀（A=新增、D=删除、M=修改）的文件清单</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String patchContent = args.has("patchText") ? args.get("patchText").asText() : null;

        // 补丁文本不能为空
        if (patchContent == null || patchContent.isBlank()) {
            return ToolResult.error("patchText is required");
        }

        try {
            // 解析补丁文本获取 hunk 列表
            PatchParser parser = new PatchParser();
            List<Hunk> hunks;
            try {
                hunks = parser.parse(patchContent);
            } catch (Exception error) {
                // 补丁解析失败
                return ToolResult.error("apply_patch verification failed: " + error.getMessage());
            }

            // 检查空补丁
            if (hunks.isEmpty()) {
                String normalized = patchContent.replace("\r\n", "\n")
                    .replace("\r", "\n").trim();
                if (normalized.equals("*** Begin Patch\n*** End Patch")) {
                    return ToolResult.error("patch rejected: empty patch");
                }
                return ToolResult.error("apply_patch verification failed: no hunks found");
            }

            // 权限检查
            if (context.permissionAsker() != null) {
                List<String> filePaths = new ArrayList<>();
                for (Hunk hunk : hunks) {
                    String path = switch (hunk) {
                        case Hunk.AddHunk a -> a.path();
                        case Hunk.DeleteHunk d -> d.path();
                        case Hunk.UpdateHunk u -> u.movePath() != null ? u.movePath() : u.path();
                    };
                    filePaths.add(path);
                }
                String allPaths = String.join(", ", filePaths);
                boolean allowed = context.permissionAsker().ask(
                    "edit", allPaths, "Apply patch to: " + allPaths);
                if (!allowed) {
                    return ToolResult.error("Permission denied for patch application");
                }
            }

            // 应用补丁
            PatchEngine engine = new PatchEngine();
            ApplyResult result = engine.apply(patchContent, Path.of("."));

            // 生成输出摘要
            StringBuilder sb = new StringBuilder();
            if (result.success()) {
                sb.append("Success. Updated the following files:\n");
                for (FileChange change : result.changes()) {
                    // 用前缀标记文件变更类型
                    String prefix = switch (change.type()) {
                        case ADD -> "A";
                        case DELETE -> "D";
                        case UPDATE, MOVE -> "M";
                    };
                    sb.append(prefix).append(" ").append(change.path()).append("\n");
                }
            } else {
                sb.append("Patch application failed.\n");
                for (FileChange change : result.changes()) {
                    String prefix = switch (change.type()) {
                        case ADD -> "A";
                        case DELETE -> "D";
                        case UPDATE, MOVE -> "M";
                    };
                    sb.append(prefix).append(" ").append(change.path()).append("\n");
                }
            }

            String output = sb.toString().stripTrailing();

            return ToolResult.of(output, output);

        } catch (Exception e) {
            return ToolResult.error("Failed to apply patch: " + e.getMessage());
        }
    }
}
