package com.aliyun.odps.agentic.skill;

/**
 * MCP 服务器配置兼容类型。
 * 已迁移至 {@link com.aliyun.odps.agentic.mcp.McpServerConfig}，此类型仅用于兼容旧代码。
 *
 * @deprecated 请改用 {@link com.aliyun.odps.agentic.mcp.McpServerConfig}
 */
@Deprecated(forRemoval = true)
public record McpServerConfig(
    String name,
    String command,
    java.util.List<String> args,
    java.util.Map<String, String> env
) {
    /**
     * 创建一个兼容的 MCP 服务器配置。
     *
     * @param name    服务器名称
     * @param command 启动命令
     * @param args    命令参数
     */
    public McpServerConfig(String name, String command, java.util.List<String> args) {
        this(name, command, args, java.util.Map.of());
    }
}
