/**
 * MCP（Model Context Protocol）集成包 -- 用于从 MCP 服务器发现并调用工具。
 *
 * <p>用户可通过 {@link com.aliyun.odps.agentic.mcp.McpServerConfig} 配置 MCP 服务器，
 * SDK 会在运行时发现工具并与内置工具一并注册。
 *
 * <p>MCP 工具使用 {@code mcp__{serverName}__{toolName}} 作为前缀以避免名称冲突。
 *
 * @see com.aliyun.odps.agentic.mcp.McpClient
 * @see com.aliyun.odps.agentic.mcp.McpServerConfig
 * @see com.aliyun.odps.agentic.mcp.McpToolDef
 */
package com.aliyun.odps.agentic.mcp;
