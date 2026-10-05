package com.aliyun.odps.agentic.mcp;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置 -- 定义如何连接一个 MCP 服务器。
 * 支持通过本地 stdio 进程或远程 URL 两种方式建立连接。
 *
 * @param name    服务器名称，用作工具前缀的一部分
 * @param command 启动服务器的命令，适用于 stdio 传输
 * @param args    命令参数列表，适用于 stdio 传输
 * @param env     服务器进程的环境变量
 * @param url     远程服务器 URL，适用于 SSE 或 StreamableHTTP 传输
 * @param headers 远程连接使用的 HTTP 头
 * @param timeout 连接超时时间，单位毫秒
 * @param oauth   OAuth 配置，可为空
 */
public record McpServerConfig(
    String name,
    String command,
    List<String> args,
    Map<String, String> env,
    String url,
    Map<String, String> headers,
    Integer timeout,
    Object oauth
) {
    /**
     * 创建使用 stdio 传输的服务器配置。
     *
     * @param name    服务器名称
     * @param command 启动命令
     * @param args    命令参数
     */
    public McpServerConfig(String name, String command, List<String> args) {
        this(name, command, args, Map.of(), null, null, null, null);
    }

    /**
     * 创建带环境变量的 stdio 服务器配置。
     *
     * @param name    服务器名称
     * @param command 启动命令
     * @param args    命令参数
     * @param env     环境变量
     */
    public McpServerConfig(String name, String command, List<String> args, Map<String, String> env) {
        this(name, command, args, env, null, null, null, null);
    }

    /**
     * 创建远程服务器配置。
     *
     * @param name 服务器名称
     * @param url  远程 URL
     * @return 远程服务器配置
     */
    public static McpServerConfig remote(String name, String url) {
        return new McpServerConfig(name, null, null, Map.of(), url, null, null, null);
    }

    /**
     * 创建带请求头的远程服务器配置。
     *
     * @param name    服务器名称
     * @param url     远程 URL
     * @param headers HTTP 请求头
     * @return 远程服务器配置
     */
    public static McpServerConfig remote(String name, String url, Map<String, String> headers) {
        return new McpServerConfig(name, null, null, Map.of(), url, headers, null, null);
    }

    /**
     * 检查当前配置是否表示远程服务器。
     *
     * @return 是否为远程服务器
     */
    public boolean isRemote() {
        return url != null && !url.isBlank();
    }

    /**
     * 获取生效的超时时间。
     *
     * @return 超时时间，单位毫秒
     */
    public int getTimeoutMs() {
        return timeout != null ? timeout : 30_000;
    }
}
