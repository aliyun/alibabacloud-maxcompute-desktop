package com.aliyun.odps.agentic.memory.context;

import java.util.List;
import java.util.Map;

/**
 * Memory Tiering 顶层 facade 的 WorkingMemory 数据载体（Phase 1，2026-05-23）。
 *
 * <p><b>注意：</b>本 POJO 是 {@link MemoryService#recallWorking(String)} 的 <b>返回视图</b>，
 * 而非 session 内部的可变状态。session 内部的可变 working memory 仍在
 * {@code com.aliyun.odps.agentic.memory.index.WorkingMemory}（已 Phase 1 scaffold）。
 *
 * <p>调用关系（Step C wire-up 时落实）：
 * <pre>
 *   PromptManager
 *      └─ MemoryService.recallWorking(sessionId)
 *            └─ DefaultMemoryService 读 AgentContext.getWorkingMemory()
 *                  └─ 转换为本 POJO（不可变快照）
 * </pre>
 *
 * <p>本视图设计为<b>不可变</b>，便于 prompt 渲染线程安全 + 后续 LLM cache 命中。
 * 字段语义由本 record 的字段注释与调用方共同定义。
 */
public record WorkingMemory(
    /** 已查表 schema 摘要：FQN → "col1:type, col2:type, ..."（截断版，≤ 200 chars/张表） */
    Map<String, String> knownTableSchemaSummaries,

    /** 已知分区表的分区列：FQN → ["dt", "hour"] */
    Map<String, List<String>> knownPartitionedTables,

    /** 最近 SQL 摘要（最近优先，cap 默认 8） */
    List<RecentSqlSummary> recentSqls,

    /** 最近图表摘要（最近优先，cap 默认 4） */
    List<RecentChartSummary> recentCharts,

    /** 最近 N 轮 message 摘要（user/assistant，最近优先，cap 默认 10） */
    List<RecentMessageSummary> recentMessages,

    /** 当前 user goal 浓缩（≤ 400 chars，用于 episodic FTS5 召回的 query） */
    String userGoalSummary,

    /** 整体 token 预估（≤ DEFAULT_BUDGET_CHARS，由实现层裁剪） */
    int estimatedChars,

    /** 快照时间 */
    long snapshotAt
) {

    /** WorkingMemory 注入 system prompt 的字符预算（与 MemoryManager.MAX_MEMORY_TOKENS 对齐）。 */
    public static final int DEFAULT_BUDGET_CHARS = 1500;

    public static final int DEFAULT_RECENT_MESSAGES_CAP = 10;
    public static final int DEFAULT_RECENT_SQL_CAP = 8;
    public static final int DEFAULT_RECENT_CHART_CAP = 4;

    /**
     * 单条 SQL 摘要。
     *
     * @param sql        原 SQL（可能已截断）
     * @param outcome    success | failed | timeout
     * @param rowCount   返回行数；null 表示 DDL/DML
     * @param instanceId ODPS instance id（trace 用）
     * @param ts         执行时间
     */
    public record RecentSqlSummary(
        String sql,
        String outcome,
        Integer rowCount,
        String instanceId,
        long ts
    ) {}

    /**
     * 单个图表摘要。
     *
     * @param chartType  bar | line | pie | ...
     * @param summary    图表标题或 1 行说明
     * @param artifactId 工件 id（前端按 id 召回详情）
     * @param ts         生成时间
     */
    public record RecentChartSummary(
        String chartType,
        String summary,
        String artifactId,
        long ts
    ) {}

    /**
     * 单条 message 摘要（不含完整 content_blocks，仅文本浓缩）。
     *
     * @param role       user | assistant
     * @param summary    内容浓缩（≤ 200 chars）
     * @param messageId  原 message id（可选）
     * @param ts         message 时间
     */
    public record RecentMessageSummary(
        String role,
        String summary,
        String messageId,
        long ts
    ) {}
}
