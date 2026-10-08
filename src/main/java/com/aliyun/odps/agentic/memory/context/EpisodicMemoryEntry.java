package com.aliyun.odps.agentic.memory.context;

import java.util.Map;

/**
 * Memory Tiering 顶层 facade 的 Episodic 视图 POJO。
 *
 * <p>本类是 {@link MemoryService#recallEpisodic(String, String, int)} 的<b>返回视图</b>。
 * 持久化层只返回会话索引 metadata，完整内容由 caller 通过 sessionId 读取 workspace JSON。
 *
 * <p>对应 SQLite FTS5 表：
 * <pre>
 *   session_episode(session_id, user_id, workspace_id, created_at, last_message_at,
 *                   goal_text, summary_text, tool_names_csv, status, indexed_at)
 * </pre>
 *
 * <p>红线 #12：
 * <ul>
 *   <li>{@code userId} 强隔离键，召回时 SQL 强制过滤；不允许跨 user 召回</li>
 * </ul>
 *
 * @param id                  UUID
 * @param userId              强隔离键
 * @param teamId              可选；团队会话写空字符串
 * @param sessionId           来源 sessionId（trace 用，不参与召回排序）
 * @param goalSummary         自然语言摘要（≤ 600 chars，由 LLM session-end 时生成）
 * @param finalAnswer         最终答案截断（≤ 800 chars）
 * @param outcome             success | partial | failed | aborted
 * @param schemaFingerprint   涉及表的 hash（跨 project 召回时用）
 * @param toolCallsSummary    ndjson 字符串，每行一个 tool name + 关键参数
 * @param relevanceScore      FTS5 BM25/LIKE 混合相关度；recall 时填
 * @param createdAt           session 创建时间
 * @param indexedAt           索引更新时间或最近消息时间
 * @param metadata            自由扩展
 */
public record EpisodicMemoryEntry(
    String id,
    String userId,
    String teamId,
    String sessionId,
    String goalSummary,
    String finalAnswer,
    String outcome,
    String schemaFingerprint,
    String toolCallsSummary,
    double relevanceScore,
    long createdAt,
    Long indexedAt,
    Map<String, Object> metadata
) {
    public EpisodicMemoryEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id required");
        }
        if (userId == null || userId.isBlank()) {
            // 红线 #12 — userId 强制非空
            throw new IllegalArgumentException("userId required (memory privacy invariant)");
        }
        if (sessionId == null) sessionId = "";
        if (goalSummary == null) goalSummary = "";
        if (finalAnswer == null) finalAnswer = "";
        if (outcome == null) outcome = "unknown";
        if (metadata == null) metadata = Map.of();
    }
}
