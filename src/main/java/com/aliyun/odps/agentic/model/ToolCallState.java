package com.aliyun.odps.agentic.model;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonSubTypes;

/**
 * 工具调用状态，表示一次工具调用在生命周期中的阶段。
 * 不同变体分别描述等待、运行、完成和出错四种状态。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "status")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ToolCallState.Pending.class, name = "pending"),
    @JsonSubTypes.Type(value = ToolCallState.Running.class, name = "running"),
    @JsonSubTypes.Type(value = ToolCallState.Completed.class, name = "completed"),
    @JsonSubTypes.Type(value = ToolCallState.Error.class, name = "error")
})
public sealed interface ToolCallState
    permits ToolCallState.Pending, ToolCallState.Running,
            ToolCallState.Completed, ToolCallState.Error {

    /**
     * 工具等待执行的状态。
     */
    record Pending(Object input, String raw) implements ToolCallState {
        public Pending() {
            this(null, "");
        }
    }

    /**
     * 工具正在执行中的状态。
     */
    record Running(Object input, String raw) implements ToolCallState {}

    /**
     * 工具成功执行完成后的状态。
     */
    record Completed(
        Object input,
        String raw,
        ToolOutput output,
        ToolTime time
    ) implements ToolCallState {}

    /**
     * 工具执行失败后的状态。
     */
    record Error(
        Object input,
        String raw,
        String error,
        ToolTime time,
        boolean interrupted
    ) implements ToolCallState {
        public Error(Object input, String raw, String error, ToolTime time) {
            this(input, raw, error, time, false);
        }

        /**
         * 判断工具是否因中断而终止。
         *
         * @return 被中断时返回 true
         */
        public boolean isInterrupted() {
            return interrupted;
        }
    }

    // ── 嵌套值类型 ───────────────────────────────

    /**
     * 工具输出结果。
     *
     * @param title    输出标题
     * @param output   输出内容
     * @param metadata 附加元数据
     */
    record ToolOutput(String title, String output, java.util.Map<String, Object> metadata) {
        public ToolOutput(String title, String output) {
            this(title, output, null);
        }
    }

    /**
     * 工具执行时间信息。
     *
     * @param start     开始时间
     * @param end       结束时间
     * @param compacted 被压缩时间
     */
    record ToolTime(long start, Long end, Long compacted) {
        public ToolTime(long start) {
            this(start, null, null);
        }
        public ToolTime(long start, Long end) {
            this(start, end, null);
        }

        /**
         * 判断工具结果是否已经被上下文压缩。
         *
         * @return 已压缩时返回 true
         */
        public boolean isCompacted() {
            return compacted != null;
        }
    }

    // ── 便捷工厂方法 ─────────────────────────────

    /**
     * 创建空输入的等待状态。
     *
     * @return 等待状态
     */
    static Pending pending() { return new Pending(); }

    /**
     * 创建带输入的等待状态。
     *
     * @param input 工具输入
     * @param raw   原始输入字符串
     * @return 等待状态
     */
    static Pending pending(Object input, String raw) { return new Pending(input, raw); }

    /**
     * 创建运行中状态。
     *
     * @param input 工具输入
     * @param raw   原始输入字符串
     * @return 运行中状态
     */
    static Running running(Object input, String raw) { return new Running(input, raw); }

    /**
     * 创建完成状态。
     *
     * @param input  工具输入
     * @param raw    原始输入字符串
     * @param output 工具输出
     * @param start  开始时间
     * @param end    结束时间
     * @return 完成状态
     */
    static Completed completed(Object input, String raw, ToolOutput output, long start, Long end) {
        return new Completed(input, raw, output, new ToolTime(start, end));
    }

    /**
     * 创建带标题和文本输出的完成状态。
     *
     * @param input  工具输入
     * @param raw    原始输入字符串
     * @param title  输出标题
     * @param output 输出内容
     * @param start  开始时间
     * @return 完成状态
     */
    static Completed completed(Object input, String raw, String title, String output, long start) {
        return new Completed(input, raw, new ToolOutput(title, output), new ToolTime(start, null));
    }

    /**
     * 创建错误状态。
     *
     * @param input        工具输入
     * @param raw          原始输入字符串
     * @param errorMessage 错误信息
     * @param start        开始时间
     * @return 错误状态
     */
    static Error error(Object input, String raw, String errorMessage, long start) {
        return new Error(input, raw, errorMessage, new ToolTime(start, null));
    }
}
