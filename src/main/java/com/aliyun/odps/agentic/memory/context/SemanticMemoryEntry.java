package com.aliyun.odps.agentic.memory.context;

import java.util.Set;

/**
 * Memory Tiering 顶层 facade 的 Semantic Fact 视图 POJO（Phase 10，2026-05-23）。
 *
 * <p>本类是 {@link MemoryService#recallSemantic(String, String, int)} 的<b>返回视图</b>。
 * 持久化层用 {@code agent.core.memory.SemanticFact}（record，含 Object value）；
 * 本视图把 value 限定为 String 以避免上层处理任意 Object 的负担（结构化 JSON
 * 由调用方按 contentType 字段自行解析）。
 *
 * <p>2026-10-08 用户裁定:user/team 分类在单机桌面形态无意义,scope 字段整体移除
 * (存储/接口/迁移 V20261008_005 同步删除)。
 *
 * @param id            UUID
 * @param ownerId       归属用户（单机默认 local-user）
 * @param key           fact 唯一标识（同 owner UNIQUE）
 * @param value         fact 内容（自由文本或 JSON 字符串）
 * @param contentType   text/plain | application/json
 * @param tags          标签（OR 语义召回）
 * @param source        manual | llm-extracted
 * @param confidence    0.0-1.0；manual=1.0，llm-extracted 默认 0.4
 * @param relevanceScore 与 query 的相关性（关键词命中数或 cosine）；recall 时填
 * @param createdAt     入库时间
 * @param updatedAt     最后更新时间
 */
public record SemanticMemoryEntry(
    String id,
    String ownerId,
    String key,
    String value,
    String contentType,
    Set<String> tags,
    String source,
    double confidence,
    double relevanceScore,
    long createdAt,
    long updatedAt
) {
    public SemanticMemoryEntry {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id required");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId required (memory isolation invariant)");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key required");
        }
        if (value == null) value = "";
        if (contentType == null || contentType.isBlank()) contentType = "text/plain";
        if (tags == null) tags = Set.of();
        if (source == null || source.isBlank()) source = "manual";
        if (createdAt <= 0) createdAt = System.currentTimeMillis();
        if (updatedAt <= 0) updatedAt = createdAt;
    }
}
