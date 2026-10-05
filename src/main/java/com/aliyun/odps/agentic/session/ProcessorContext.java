package com.aliyun.odps.agentic.session;

import java.util.HashMap;
import java.util.Map;

/**
 * 流式处理器上下文。
 *
 * <p>封装单次流式响应处理过程中的可变状态，包括文本缓冲、推理缓冲、工具调用状态和控制标记。
 */
public class ProcessorContext {

    /** 正在处理中的工具调用，键为调用 ID。 */
    public final Map<String, PendingToolCall> toolcalls = new HashMap<>();

    /** 当前步骤结束后是否应跳出处理流程。 */
    public boolean shouldBreak = false;

    /** 与步骤相关联的快照 ID。 */
    public String snapshot;

    /** 当前操作是否因权限而被阻塞。 */
    public boolean blocked = false;

    /** 当前步骤结束后是否需要上下文压缩。 */
    public boolean needsCompaction = false;

    /** 当前文本输出缓冲区。 */
    public TextBuffer currentText;

    /** 正在构建的推理内容，键为推理块 ID。 */
    public final Map<String, ReasoningBuffer> reasoningMap = new HashMap<>();

    /** 工具调用历史计数，用于检测重复循环。 */
    public final Map<String, Integer> toolCallCounts = new HashMap<>();

    /**
     * 表示一个尚未完成的工具调用。
     *
     * @param tool 工具名称
     * @param callId 工具调用 ID
     * @param input 已累计的输入内容
     * @param inputEnded 输入流是否结束
     * @param deferred 是否为延迟执行的工具调用
     */
    public record PendingToolCall(
        String tool,
        String callId,
        StringBuilder input,
        boolean inputEnded,
        boolean deferred
    ) {}

    /**
     * 文本缓冲区。
     */
    public static class TextBuffer {
        public final StringBuilder content = new StringBuilder();

        /**
         * 追加文本增量。
         *
         * @param delta 文本增量
         */
        public void append(String delta) {
            content.append(delta);
        }

        /**
         * 获取当前缓冲文本。
         *
         * @return 完整文本
         */
        public String getText() {
            return content.toString();
        }
    }

    /**
     * 推理缓冲区。
     */
    public static class ReasoningBuffer {
        public final StringBuilder content = new StringBuilder();
        public String signature;

        /**
         * 追加推理文本增量。
         *
         * @param delta 推理增量
         */
        public void append(String delta) {
            content.append(delta);
        }

        /**
         * 获取当前推理文本。
         *
         * @return 推理文本
         */
        public String getText() {
            return content.toString();
        }
    }
}
