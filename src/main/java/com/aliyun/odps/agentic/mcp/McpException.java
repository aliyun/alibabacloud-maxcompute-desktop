package com.aliyun.odps.agentic.mcp;

/**
 * MCP 相关异常。
 * 用于表示连接、通信或协议处理过程中的运行时错误。
 */
public class McpException extends RuntimeException {
    /**
     * 使用错误消息创建异常。
     *
     * @param message 错误消息
     */
    public McpException(String message) { super(message); }

    /**
     * 使用错误消息和原因创建异常。
     *
     * @param message 错误消息
     * @param cause   原始异常
     */
    public McpException(String message, Throwable cause) { super(message, cause); }
}
