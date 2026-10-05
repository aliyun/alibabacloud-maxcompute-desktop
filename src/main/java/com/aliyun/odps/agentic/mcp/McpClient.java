package com.aliyun.odps.agentic.mcp;

import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端接口 -- 用于连接 MCP 服务器、发现工具并调用工具。
 *
 * @see <a href="https://modelcontextprotocol.io/">Model Context Protocol</a>
 */
public interface McpClient {

    /**
     * 客户端发起握手时声明的首选 MCP 协议版本（0.4.0 起从 2024-11-05 升级）。
     *
     * <p>握手时客户端上报本版本；服务端在 {@code initialize} 响应里回它实际使用的版本
     * （可能更低）。本 SDK 用到的 {@code tools/list} / {@code tools/call} 在这些修订间稳定，
     * 因此接受服务端协商出的版本即可。
     */
    String PREFERRED_PROTOCOL_VERSION = "2025-06-18";

    /**
     * 连接到 MCP 服务器并发现其工具定义。
     *
     * @param config 服务器配置
     * @return 发现到的工具列表
     */
    List<McpToolDef> connect(McpServerConfig config);

    /**
     * 断开与指定 MCP 服务器的连接。
     *
     * @param serverName 服务器名称
     */
    void disconnect(String serverName);

    /**
     * 列出当前已连接的所有服务器。
     *
     * @return 服务器名称列表
     */
    List<String> listServers();

    /**
     * 检查是否已连接到指定服务器。
     *
     * @param serverName 服务器名称
     * @return 是否已连接
     */
    boolean isConnected(String serverName);

    /**
     * 调用 MCP 服务器上的工具。
     *
     * @param serverName 服务器标识
     * @param toolName   工具名称，不包含前缀
     * @param arguments  工具输入参数
     * @return 工具执行结果
     */
    ToolResult callTool(String serverName, String toolName, Map<String, Object> arguments);

    /**
     * 调用 MCP 服务器上的工具，并支持中止信号。
     *
     * @param serverName  服务器标识
     * @param toolName    工具名称，不包含前缀
     * @param arguments   工具输入参数
     * @param abortSignal 用于取消调用的信号，可为空
     * @return 工具执行结果
     */
    default ToolResult callTool(String serverName, String toolName, Map<String, Object> arguments,
                                com.aliyun.odps.agentic.session.AbortSignal abortSignal) {
        return callTool(serverName, toolName, arguments);
    }
}
