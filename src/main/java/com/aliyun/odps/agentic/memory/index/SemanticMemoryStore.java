package com.aliyun.odps.agentic.memory.index;

import com.aliyun.odps.agentic.memory.index.dto.RankedFact;

import java.util.List;
import java.util.Set;

/**
 * Semantic fact 存储。2026-10-08 起单机单 owner:不再区分 user/team(owner_type 已随
 * migration V20261008_005 删除);`memoryScope` 参数是记忆类目(custom 等),勿与旧 user/team
 * 分类混淆。
 */
public interface SemanticMemoryStore {
    void put(SemanticFact fact);
    default void put(SemanticFact fact, String memoryScope, String source) { put(fact); }
    SemanticFact get(String ownerId, String key);
    boolean remove(String ownerId, String key);
    /** @param memoryScope 记忆类目过滤(null=全部类目) */
    void delete(String ownerId, String memoryScope, String key);
    List<SemanticFact> findByTags(String ownerId, Set<String> anyTags);
    List<RankedFact> findByQuery(String query, String memoryScope, String ownerId, int topK);
    /** 分页列举某 owner 的存活 fact（manageMemory list 动作的数据源，2026-09-30）。
     * 按 updated_at 倒序；limit 由实现层封顶（防无界倾倒进 LLM 上下文）。 */
    List<SemanticFact> listFor(String ownerId, int offset, int limit);
    int size();
    int sizeFor(String ownerId);
    void clear();
    default boolean hasKey(String ownerId, String key) {
        return get(ownerId, key) != null;
    }
}
