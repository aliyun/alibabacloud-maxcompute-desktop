package com.aliyun.odps.agentic.memory;

import java.util.List;

/**
 * 记忆存储接口 -- 管理代理记忆的持久化与检索。
 * 支持搜索、追加内容以及按类别压缩记忆。
 */
public interface MemoryStore {

    /**
     * 搜索与查询相关的记忆内容。
     *
     * @param query 搜索查询
     * @param limit 最大返回结果数
     * @return 按相关性从高到低排序的记忆片段列表
     */
    List<MemoryEntry> search(String query, int limit);

    /**
     * 向指定记忆类别追加内容。
     *
     * @param category 记忆类别，如 {@code "daily"}、{@code "long-term"}
     * @param content  追加内容
     */
    void append(String category, String content);

    /**
     * 对指定记忆类别执行压缩或摘要。
     *
     * @param category 记忆类别
     * @param summary  用于替换原内容的摘要
     */
    void compact(String category, String summary);
}
