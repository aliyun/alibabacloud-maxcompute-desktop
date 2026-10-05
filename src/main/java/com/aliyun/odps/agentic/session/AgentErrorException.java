package com.aliyun.odps.agentic.session;

/**
 * 对 {@link AgentError} 的异常封装。
 *
 * <p>用于在 Java 异常机制中携带结构化代理错误信息。
 */
public class AgentErrorException extends Exception {

    private final AgentError error;

    /**
     * 使用代理错误创建异常。
     *
     * @param error 结构化代理错误
     */
    public AgentErrorException(AgentError error) {
        super(error.message());
        this.error = error;
    }

    /**
     * 使用代理错误和根因创建异常。
     *
     * @param error 结构化代理错误
     * @param cause 原始异常
     */
    public AgentErrorException(AgentError error, Throwable cause) {
        super(error.message(), cause);
        this.error = error;
    }

    /**
     * 返回封装的代理错误。
     *
     * @return 代理错误对象
     */
    public AgentError error() {
        return error;
    }
}
