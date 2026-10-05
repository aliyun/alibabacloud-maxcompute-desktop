package com.aliyun.odps.agentic.tool;

import com.aliyun.odps.agentic.model.MessagePart;

import java.util.List;
import java.util.Map;

/**
 * 工具执行结果，封装工具调用的返回值。
 *
 * <p>约定：{@code title} 为简短摘要（如 "3 files matched"），
 * {@code output} 为完整文本内容（文件内容、搜索结果等）。
 *
 * @param title       结果标题/摘要
 * @param metadata    结果元数据
 * @param output      完整文本输出
 * @param attachments 文件附件（可为空）
 */
public record ToolResult(
    String title,
    Map<String, Object> metadata,
    String output,
    List<MessagePart.FilePart> attachments
) {
    /**
     * 创建仅含输出文本的简单结果。
     */
    public static ToolResult of(String output) {
        return new ToolResult(null, Map.of(), output, List.of());
    }

    /**
     * 创建带标题和输出文本的结果。
     */
    public static ToolResult of(String title, String output) {
        return new ToolResult(title, Map.of(), output, List.of());
    }

    /**
     * 判断此结果是否表示错误。
     */
    public boolean isError() {
        return metadata != null && Boolean.TRUE.equals(metadata.get("error"));
    }

    /**
     * 创建错误结果。
     */
    public static ToolResult error(String errorMessage) {
        return new ToolResult("Error", Map.of("error", true), errorMessage, List.of());
    }

    /**
     * 创建成功结果（仅含输出文本）。
     */
    public static ToolResult success(String output) {
        return new ToolResult(null, Map.of(), output, List.of());
    }

    /**
     * 创建带标题的成功结果。
     */
    public static ToolResult success(String title, String output) {
        return new ToolResult(title, Map.of(), output, List.of());
    }
}
