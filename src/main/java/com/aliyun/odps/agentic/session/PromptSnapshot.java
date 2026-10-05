package com.aliyun.odps.agentic.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 表示一次 LLM 调用前组装完成的提示词快照。
 *
 * <p>该快照仅用于审计、回放和调试，不作为跨轮次会话记忆的存储来源。
 *
 * @param sessionId 会话 ID
 * @param agentName 代理名称
 * @param modelId 模型 ID
 * @param systemPrompt 系统提示词全文
 * @param modelMessages 已转换为提供者格式的消息列表
 * @param enabledTools 当前启用的工具集合
 * @param createdAt 快照创建时间
 */
public record PromptSnapshot(
    String sessionId,
    String agentName,
    String modelId,
    String systemPrompt,
    List<Map<String, Object>> modelMessages,
    Map<String, Boolean> enabledTools,
    Instant createdAt
) {}
