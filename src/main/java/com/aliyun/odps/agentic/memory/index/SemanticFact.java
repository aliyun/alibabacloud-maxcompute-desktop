package com.aliyun.odps.agentic.memory.index;

import java.util.Set;

/**
 * Phase 10 — Semantic Fact（2026-05-22）。
 *
 * <p>结构化 KV 记忆条目。2026-10-08 用户裁定:user/team 分类在单机桌面形态无意义,
 * owner_type 维度整体移除(存储/接口/迁移 V20261008_005 同步删除)。
 *
 * @param key      fact 唯一标识（user-defined or auto-generated UUID）
 * @param value    fact 内容（自由文本 / 结构化 JSON）
 * @param ownerId  归属用户（单机默认 local-user）
 * @param tags     标签（用于按主题召回）
 * @param updatedAt 最后更新时间
 */
public record SemanticFact(
    String key,
    Object value,
    String ownerId,
    Set<String> tags,
    long updatedAt,
    long createdAt
) {
    /** 兼容旧调用点：无 createdAt 时退化为 updatedAt。 */
    public SemanticFact(String key, Object value, String ownerId, Set<String> tags, long updatedAt) {
        this(key, value, ownerId, tags, updatedAt, updatedAt);
    }

    public SemanticFact {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key required");
        }
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId required");
        }
        if (tags == null) tags = Set.of();
        if (updatedAt <= 0) updatedAt = System.currentTimeMillis();
    }
}
