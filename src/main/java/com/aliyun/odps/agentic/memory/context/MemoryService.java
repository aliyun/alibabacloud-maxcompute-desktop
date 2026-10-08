package com.aliyun.odps.agentic.memory.context;

import java.util.List;
import java.util.Set;

/**
 * Memory Tiering 顶层 facade（Phase 1 / 7 / 10，2026-05-23）。
 *
 * <p>统一三层 memory 的读写入口，对调用方屏蔽内部存储实现：
 * <ul>
 *   <li><b>WorkingMemory</b>（Phase 1）— session 内进程驻留，不落盘</li>
 *   <li><b>EpisodicMemory</b> — SQLite FTS5 session index + workspace JSON content</li>
 *   <li><b>SemanticMemory</b> — 用户 / 团队 fact，结构化 KV + SQLite FTS5</li>
 * </ul>
 *
 * <p>调用方（Step C wire-up 时落实）：
 * <ul>
 *   <li>{@code PromptManager} 在每轮 LLM 调用前调三个 {@code recall*}</li>
 *   <li>{@code SessionManager.onSessionEnd} 调 {@link #persistEpisodic}</li>
 *   <li>{@code MemoryWriteController}（{@code POST /api/memory/save}）调 {@link #persistSemantic} —
 *       §23.12c 用户主动路径：UI hover 💾 / saveMemory skill / admin 面板</li>
 *   <li>知识库 REST controller 调 {@link #persistSemantic} / {@link #deleteSemantic}</li>
 *   <li>用户撤销授权 → {@link #revokeEpisodicAuth}</li>
 * </ul>
 *
 * <p>红线（路线图 §15.3 #12）：
 * <ul>
 *   <li>EpisodicMemory 不跨用户共享；read 强制 user_id 隔离</li>
 *   <li>SemanticMemory 团队 fact 写入需鉴权；read 强制 owner_id 隔离</li>
 *   <li>所有失败必须降级（recall 返回空列表，persist 返回 false），不卡 LLM 主调用</li>
 * </ul>
 *
 * <p>线程安全：实现层必须线程安全（多 session 并发调用）。
 *
 * <p>本接口<b>不携带任何 Spring 注解</b>（{@code @Service} 等），由 Step C wire-up 时
 * 在实现类（如 {@code DefaultMemoryService}）上加。这样可避免 Phase 7/10 PR 之前
 * 误装配触发循环依赖或冲突 Bean。
 */
public interface MemoryService {

    // ════════════════════════════════════════════════════════════
    // WorkingMemory — Phase 1
    // ════════════════════════════════════════════════════════════

    /**
     * 取 session 当前 working memory 的不可变快照。
     *
     * <p>实现层从 {@code AgentContext.getWorkingMemory()}（核心层）读，转为顶层视图。
     * 不存在 / session 已结束时返回空快照（不抛异常）。
     *
     * @param sessionId session id
     * @return 不可变快照；从不为 null
     */
    WorkingMemory recallWorking(String sessionId);

    // ════════════════════════════════════════════════════════════
    // EpisodicMemory — Phase 7
    // ════════════════════════════════════════════════════════════

    /**
     * 召回与 query 相关的历史会话 top-K。
     *
     * @deprecated 2026-09-30 改造删除裁定：episodic 检索链零产品消费方（指标恒 0、
     * 面板已移除、SessionManager 写入 hook 已拆除）。保留方法仅为存量表清理期兼容，
     * 下个大版本连同 session_episode 表一起删除。跨会话连续性的正解是语义记忆
     * （{@link #recallSemantic}）+ 会话历史 UI。
     *
     * @param userId 当前用户 id（非空）
     * @param query  自然语言 query
     * @param topK   召回数量（建议 3）
     * @return 不可变列表，按 relevance 倒序；从不为 null
     */
    @Deprecated
    List<EpisodicMemoryEntry> recallEpisodic(String userId, String query, int topK);

    /**
     * 将 session 的 workspace JSON 内容抽取为 SQLite FTS5 索引。
     *
     * <p>主触发：{@code SessionIndexEventBroker} 的 create/update/delete 事件。
     *
     * <p>行为：
     * <ol>
     *   <li>只写索引所需 metadata；完整内容仍以 workspace JSON 为 source-of-truth</li>
     *   <li>失败（DB / JSON 读取） → log warn，返回 false；不抛</li>
     * </ol>
     *
     * @param sessionId session id
     * @return 是否成功提交索引请求
     */
    boolean persistEpisodic(String sessionId);

    /**
     * 用户要求清除 episodic 索引时调用。
     *
     * <p>行为：
     * <ul>
     *   <li>当前只有 SQLite FTS5 索引；无论 {@code keepMetadata} 取值如何，均删除该用户索引行</li>
     * </ul>
     *
     * @param userId       目标 userId
     * @param keepMetadata 是否保留元数据
     */
    void revokeEpisodicAuth(String userId, boolean keepMetadata);

    // ════════════════════════════════════════════════════════════
    // SemanticMemory — Phase 10
    // ════════════════════════════════════════════════════════════

    /**
     * 召回与 query 相关的 user / team fact。
     *
     * <p>规则（红线 #12）：
     * <ol>
     *   <li>SQL 强制 {@code (scope=USER AND owner_id=:userId) OR (scope=TEAM AND owner_id IN :teamIds)}</li>
     *   <li>{@code teamIds} 由实现层从 {@code AgentContext.getTeamId()} 解析（桌面单机 = userId）</li>
     *   <li>精确 key → FTS5 MATCH → LIKE → 结构化加权</li>
     *   <li>失败 → 返回空列表，不抛</li>
     * </ol>
     *
     * @param userId 当前用户 id
     * @param query  自然语言 query
     * @param topK   召回数量（建议 5；token 预算 ≤ 1500 chars）
     * @return 不可变列表，按 relevance 倒序；从不为 null
     */
    List<SemanticMemoryEntry> recallSemantic(String userId, String query, int topK);

    /**
     * 分页列举当前用户的语义记忆（manageMemory list 动作，2026-09-30）。
     *
     * <p>规则:单机单 owner 全集(2026-10-08 移除 user/team 分类);
     * 按 updatedAt 倒序;limit 实现层封顶(≤50);失败返回空列表,不抛。</p>
     *
     * @param userId 当前用户 id
     * @param offset 分页偏移（≥0）
     * @param limit  每页数量（≤50）
     * @return 不可变列表；从不为 null
     */
    List<SemanticMemoryEntry> listSemantic(String userId, int offset, int limit);

    /**
     * 写入一条 fact。
     *
     * <p>规则：
     * <ul>
     *   <li>{@code ownerId+key} UNIQUE（叠加记忆类目 scope），存在则 UPDATE</li>
     *   <li>{@code source=llm-extracted} 时 confidence 默认 0.4，{@code source=manual} 时 1.0；
     *       两者均为下层 entry 的初始值</li>
     * </ul>
     *
     * @param ownerId     userId（单机默认 local-user）
     * @param key         fact key
     * @param value       fact value（≤ 2KB；超 → 拆分或丢 EpisodicMemory）
     * @param tags        标签（可空）
     * @param source      "manual" / "llm-extracted"
     */
    void persistSemantic(String ownerId, String key,
                         String value, Set<String> tags, String source);

    /**
     * 删除一条 fact。
     *
     * @return 是否真实删除（未命中返回 false）
     */
    boolean deleteSemantic(String ownerId, String key);
}
