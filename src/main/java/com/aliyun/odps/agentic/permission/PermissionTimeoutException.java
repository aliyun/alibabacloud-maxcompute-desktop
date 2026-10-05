package com.aliyun.odps.agentic.permission;

/**
 * 权限询问超时异常。
 *
 * <p>当 SDK 侧配置的 {@code askTimeoutMs} 到期而宿主 asker 仍未回复时抛出。
 * 此异常表示"超时终止"而非"用户拒绝"——RunLoop 应将其视为不可重试的致命错误并终止当前 run。
 *
 * <p>不标记为 retryable：模型不应在超时后重试同一操作。
 */
public class PermissionTimeoutException extends RuntimeException {

    private final String toolName;
    private final long timeoutMs;

    /**
     * @param toolName  触发权限询问的工具名称
     * @param timeoutMs 超时毫秒数
     */
    public PermissionTimeoutException(String toolName, long timeoutMs) {
        super(String.format("Permission ask for tool '%s' timed out after %d ms", toolName, timeoutMs));
        this.toolName = toolName;
        this.timeoutMs = timeoutMs;
    }

    /** 触发权限询问的工具名称。 */
    public String getToolName() {
        return toolName;
    }

    /** 超时毫秒数。 */
    public long getTimeoutMs() {
        return timeoutMs;
    }
}
