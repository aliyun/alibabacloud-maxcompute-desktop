package com.aliyun.odps.agentic.memory;

/**
 * 单条记忆记录。
 *
 * @param category 记忆类别
 * @param content  记忆内容
 * @param score    搜索结果的相关性分数，范围通常为 0.0 到 1.0
 */
public record MemoryEntry(
    String category,
    String content,
    double score
) {}
