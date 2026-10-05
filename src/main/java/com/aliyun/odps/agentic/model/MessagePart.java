package com.aliyun.odps.agentic.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息片段密封接口，用于表示 {@link Message} 的多态内容。
 * 不同变体分别承载文本、推理、文件、工具调用、快照和补丁等内容。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MessagePart.TextPart.class, name = "text"),
    @JsonSubTypes.Type(value = MessagePart.SubtaskPart.class, name = "subtask"),
    @JsonSubTypes.Type(value = MessagePart.ReasoningPart.class, name = "reasoning"),
    @JsonSubTypes.Type(value = MessagePart.FilePart.class, name = "file"),
    @JsonSubTypes.Type(value = MessagePart.ImagePart.class, name = "image"),
    @JsonSubTypes.Type(value = MessagePart.ImageReferencePart.class, name = "image-reference"),
    @JsonSubTypes.Type(value = MessagePart.DocumentPart.class, name = "document"),
    @JsonSubTypes.Type(value = MessagePart.ToolPart.class, name = "tool"),
    @JsonSubTypes.Type(value = MessagePart.ToolCallPart.class, name = "tool-call"),
    @JsonSubTypes.Type(value = MessagePart.ToolResultPart.class, name = "tool-result"),
    @JsonSubTypes.Type(value = MessagePart.StepStartPart.class, name = "step-start"),
    @JsonSubTypes.Type(value = MessagePart.StepFinishPart.class, name = "step-finish"),
    @JsonSubTypes.Type(value = MessagePart.SnapshotPart.class, name = "snapshot"),
    @JsonSubTypes.Type(value = MessagePart.PatchPart.class, name = "patch"),
    @JsonSubTypes.Type(value = MessagePart.AgentPart.class, name = "agent"),
    @JsonSubTypes.Type(value = MessagePart.RetryPart.class, name = "retry"),
    @JsonSubTypes.Type(value = MessagePart.CompactionPart.class, name = "compaction")
})
public sealed interface MessagePart
    permits MessagePart.TextPart, MessagePart.SubtaskPart, MessagePart.ReasoningPart,
            MessagePart.FilePart, MessagePart.ImagePart, MessagePart.ImageReferencePart,
            MessagePart.DocumentPart,
            MessagePart.ToolPart,
            MessagePart.ToolCallPart, MessagePart.ToolResultPart,
            MessagePart.StepStartPart, MessagePart.StepFinishPart,
            MessagePart.SnapshotPart, MessagePart.PatchPart,
            MessagePart.AgentPart, MessagePart.RetryPart,
            MessagePart.CompactionPart {

    /**
     * 文本片段。
     */
    record TextPart(
        String text,
        boolean synthetic,
        boolean ignored
    ) implements MessagePart {
        public TextPart(String text) {
            this(text, false, false);
        }
        public TextPart(String text, boolean synthetic) {
            this(text, synthetic, false);
        }
    }

    /**
     * 子任务片段，表示代理派生的子任务。
     */
    record SubtaskPart(
        String prompt,
        String description,
        String sessionID
    ) implements MessagePart {
        public SubtaskPart(String prompt, String description) {
            this(prompt, description, null);
        }
    }

    /**
     * 推理内容片段。
     */
    record ReasoningPart(
        String text,
        ReasoningTime time,
        String signature
    ) implements MessagePart {
        public ReasoningPart(String text) {
            this(text, null, null);
        }
        public ReasoningPart(String text, ReasoningTime time) {
            this(text, time, null);
        }
    }

    /**
     * 文件附件片段。
     */
    record FilePart(
        String url,
        String filename,
        String mime,
        FileSource source
    ) implements MessagePart {
        public FilePart(String url, String filename, String mime) {
            this(url, filename, mime, null);
        }
    }

    /**
     * 图片片段，可表示 Base64 图片或 URL 图片。
     */
    record ImagePart(
        String data,
        String url,
        String mimeType
    ) implements MessagePart {
        /**
         * 使用 Base64 数据创建图片片段。
         *
         * @param data     Base64 图片数据
         * @param mimeType 图片 MIME 类型
         */
        public ImagePart(String data, String mimeType) {
            this(data, null, mimeType);
        }

        /**
         * 根据 URL 创建图片片段。
         *
         * @param url      图片地址
         * @param mimeType 图片 MIME 类型
         * @return 图片片段
         */
        public static ImagePart fromUrl(String url, String mimeType) {
            return new ImagePart(null, url, mimeType);
        }

        /**
         * 判断当前图片是否为 Base64 形式。
         *
         * @return 为 Base64 时返回 true
         */
        public boolean isBase64() {
            return data != null;
        }
    }

    /** Host-managed image identity; bytes remain in the host's normalized artifact store. */
    record ImageReferencePart(
        String fileId,
        String normalizedPath,
        String mediaType,
        String sha256,
        int width,
        int height
    ) implements MessagePart {}

    /**
     * 文档片段，通常用于 PDF 等 Base64 文档附件。
     *
     * @param data     Base64 文档数据
     * @param mimeType 文档 MIME 类型
     * @param name     可选文件名
     */
    record DocumentPart(
        String data,
        String mimeType,
        String name
    ) implements MessagePart {
        /**
         * 创建不带名称的文档片段。
         *
         * @param data     Base64 文档数据
         * @param mimeType 文档 MIME 类型
         */
        public DocumentPart(String data, String mimeType) {
            this(data, mimeType, null);
        }
    }

    /**
     * 工具片段，表示一次内部工具调用及其状态。
     */
    record ToolPart(
        String tool,
        String callID,
        ToolCallState state,
        Map<String, Object> metadata
    ) implements MessagePart {
        public ToolPart(String tool, String callID, ToolCallState state) {
            this(tool, callID, state, null);
        }
    }

    /**
     * 工具调用片段，表示提供者层的工具调用内容块。
     */
    record ToolCallPart(
        String callID,
        String name,
        String input,
        Map<String, Object> metadata
    ) implements MessagePart {
        public ToolCallPart {
            metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }
        public ToolCallPart(String callID, String name, String input) {
            this(callID, name, input, Map.of());
        }
    }

    /**
     * 工具结果片段，表示工具执行后的返回内容块。
     */
    record ToolResultPart(
        String callID,
        String name,
        String output,
        boolean isError,
        Map<String, Object> metadata
    ) implements MessagePart {
        public ToolResultPart {
            metadata = metadata == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
        }

        public ToolResultPart(String callID, String name, String output, boolean isError) {
            this(callID, name, output, isError, Map.of());
        }
    }

    /**
     * 步骤开始标记。
     */
    record StepStartPart(String snapshot) implements MessagePart {}

    /**
     * 步骤结束标记，附带使用量统计。
     */
    record StepFinishPart(
        String finishReason,
        com.aliyun.odps.agentic.llm.Usage usage,
        String snapshot
    ) implements MessagePart {}

    /**
     * 快照片段，用于记录文件系统状态检查点。
     */
    record SnapshotPart(
        String id,
        List<String> files
    ) implements MessagePart {}

    /**
     * 补丁片段，表示已应用的补丁内容。
     */
    record PatchPart(String diff) implements MessagePart {}

    /**
     * 代理片段，表示代理切换或委派信息。
     */
    record AgentPart(
        String name,
        AgentSource source
    ) implements MessagePart {
        public AgentPart(String name) {
            this(name, null);
        }
    }

    /**
     * 重试片段，表示一次模型调用重试。
     */
    record RetryPart(
        int attempt,
        String reason
    ) implements MessagePart {}

    /**
     * 上下文压缩标记片段。
     */
    record CompactionPart(
        boolean auto,
        boolean overflow,
        String tailStartId,
        String summary
    ) implements MessagePart {
        public CompactionPart(boolean auto, String summary) {
            this(auto, false, null, summary);
        }
    }

    // ── 嵌套值类型 ───────────────────────────────

    /**
     * 推理时间信息。
     *
     * @param start 开始时间
     * @param end   结束时间
     */
    record ReasoningTime(long start, Long end) {}

    /**
     * 文件来源信息。
     *
     * @param type      来源类型
     * @param path      文件路径
     * @param text      关联文本
     * @param startLine 起始行号
     * @param endLine   结束行号
     */
    record FileSource(
        String type,
        String path,
        String text,
        Integer startLine,
        Integer endLine
    ) {}

    /**
     * 代理来源信息。
     *
     * @param value 来源值
     * @param start 起始位置
     * @param end   结束位置
     */
    record AgentSource(String value, int start, int end) {}
}
