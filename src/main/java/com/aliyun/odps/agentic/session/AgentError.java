package com.aliyun.odps.agentic.session;

/**
 * 代理运行过程中的结构化错误分类。
 *
 * <p>运行循环会根据不同错误类型决定是否重试、是否触发上下文压缩，以及如何向上层报告。
 */
public sealed interface AgentError permits
        AgentError.ContextOverflowError,
        AgentError.AbortedError,
        AgentError.ApiError,
        AgentError.OutputLengthError,
        AgentError.UnknownError {

    /**
     * 返回适合展示给调用方的错误消息。
     *
     * @return 错误消息
     */
    String message();

    /**
     * 表示上下文窗口已超限的错误。
     * 该错误通常会触发上下文压缩流程。
     *
     * @param message 错误消息
     * @param providerId 提供者 ID
     */
    record ContextOverflowError(String message, String providerId) implements AgentError {
        /**
         * 使用默认提供者信息创建错误。
         *
         * @param message 错误消息
         */
        public ContextOverflowError(String message) {
            this(message, null);
        }

        /**
         * 判断给定异常是否可视为上下文溢出。
         *
         * @param t 待判断异常
         * @return 匹配则返回 {@code true}
         */
        public static boolean isInstance(Throwable t) {
            if (t instanceof AgentErrorException ae) return ae.error() instanceof ContextOverflowError;
            String msg = t.getMessage();
            return msg != null && (msg.contains("context_length_exceeded")
                    || msg.contains("context window")
                    || msg.contains("max context")
                    || msg.contains("too many tokens")
                    || msg.contains("request too large"));
        }
    }

    /**
     * 表示请求已被用户或上层逻辑中止。
     * 此类错误不应进入重试流程。
     *
     * @param message 错误消息
     */
    record AbortedError(String message) implements AgentError {
        /**
         * 创建默认中止错误。
         */
        public AbortedError() {
            this("Request was aborted");
        }

        /**
         * 判断给定异常是否可视为中止错误。
         *
         * @param t 待判断异常
         * @return 匹配则返回 {@code true}
         */
        public static boolean isInstance(Throwable t) {
            if (t instanceof AgentErrorException ae) return ae.error() instanceof AbortedError;
            String msg = t != null ? t.getMessage() : null;
            return msg != null && (msg.contains("AbortError") || msg.contains("aborted"));
        }
    }

    /**
     * 表示来自提供者 API 的错误。
     * 可根据状态码判断是否适合重试。
     *
     * @param message 错误消息
     * @param statusCode HTTP 状态码
     * @param retryable 是否可重试
     * @param providerId 提供者 ID
     */
    record ApiError(String message, int statusCode, boolean retryable, String providerId) implements AgentError {
        /**
         * 根据状态码创建 API 错误。
         *
         * @param message 错误消息
         * @param statusCode HTTP 状态码
         */
        public ApiError(String message, int statusCode) {
            this(message, statusCode, statusCode >= 500 || statusCode == 429, null);
        }

        /**
         * 判断给定异常是否为 API 错误包装。
         *
         * @param t 待判断异常
         * @return 匹配则返回 {@code true}
         */
        public static boolean isInstance(Throwable t) {
            return t instanceof AgentErrorException ae && ae.error() instanceof ApiError;
        }
    }

    /**
     * 表示模型输出长度达到上限。
     *
     * @param message 错误消息
     */
    record OutputLengthError(String message) implements AgentError {
        /**
         * 创建默认输出长度错误。
         */
        public OutputLengthError() {
            this("Output length exceeded");
        }

        /**
         * 判断给定异常是否可视为输出长度错误。
         *
         * @param t 待判断异常
         * @return 匹配则返回 {@code true}
         */
        public static boolean isInstance(Throwable t) {
            if (t instanceof AgentErrorException ae) return ae.error() instanceof OutputLengthError;
            String msg = t != null ? t.getMessage() : null;
            return msg != null && (msg.contains("output_length") || msg.contains("max_tokens"));
        }
    }

    /**
     * 表示无法进一步分类的未知错误。
     *
     * @param message 错误消息
     */
    record UnknownError(String message) implements AgentError {
        /**
         * 从原始异常创建未知错误。
         *
         * @param t 原始异常
         */
        public UnknownError(Throwable t) {
            this(t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        }
    }

    /**
     * 将 {@link Throwable} 转换为结构化代理错误。
     *
     * @param t 原始异常
     * @return 分类后的代理错误
     */
    static AgentError from(Throwable t) {
        if (t instanceof AgentErrorException ae) return ae.error();
        if (ContextOverflowError.isInstance(t)) return new ContextOverflowError(t.getMessage());
        if (AbortedError.isInstance(t)) return new AbortedError(t.getMessage());
        if (OutputLengthError.isInstance(t)) return new OutputLengthError(t.getMessage());
        return new UnknownError(t);
    }
}
