package com.aliyun.odps.agentic.config;


/**
 * Agent 行为规则集中配置
 * <p>
 * 所有可调行为常量在此集中声明，通过 application.properties 可覆盖。
 * 各模块（AgentRuntime / AgentEngine / AgentToolService / RoutingOrchestrator）
 * 从此 Config 读取，不再使用 static final 硬编码。
 */
public class AgentRules {

    // ═══════════════════════════════════════════════
    // 主循环迭代控制
    // ═══════════════════════════════════════════════

    private int defaultMaxIterations = 15;

    private int minIterations = 8;

    private int simpleQueryFastPathMaxIterations = 4;

    private int reasoningBaseIterations = 20;

    // ═══════════════════════════════════════════════
    // 主循环重试 / 失败控制
    // ═══════════════════════════════════════════════

    private int maxRetries = 3;

    private long retryDelayBaseMs = 1000L;

    private int maxFinishAttempts = 3;

    private int maxSameSqlFailures = 2;

    private int maxConsecutiveFailures = 3;

    private long confirmationTimeoutMs = 120000L;

    private int recentCallLimit = 3;

    // ═══════════════════════════════════════════════
    // 主循环 Remediation 控制
    // ═══════════════════════════════════════════════

    private int remediationReExecuteLimit = 1;

    private int noSnippetMatchLoopCutoff = 2;

    // ═══════════════════════════════════════════════
    // AgentEngine 检查点
    // ═══════════════════════════════════════════════

    private int checkpointInterval = 2;

    private long checkpointTtlMs = 86400000L;

    // ═══════════════════════════════════════════════
    // AgentToolService 评估阈值
    // ═══════════════════════════════════════════════

    private long pollIntervalMs = 2000L;

    private long pollInfoLogIntervalSec = 30L;

    private int rowExplosionThreshold = 1000;

    private long stalePartitionDays = 90L;

    // ═══════════════════════════════════════════════
    // 主循环全局超时（独立于确认超时）
    // ═══════════════════════════════════════════════

    private long globalTimeoutMs = 300000L;

    // ═══════════════════════════════════════════════
    // 异步 executeQuery 模式
    // ═══════════════════════════════════════════════

    /** 全局开关：是否启用 PlanStep 异步 executeQuery 能力（默认开启，PlanGenerator 会让独立 executeQuery 步骤声明 async=true） */
    private boolean asyncQueryEnabled = true;

    /** 异步查询单次轮询超时（默认 10 分钟） */
    private long asyncQueryTimeoutMs = 600000L;

    /**
     * ASYNC_ON_TIMEOUT 软超时（默认 90s）：executeQuery 同步等待此时长仍未完成 → 转后台异步。
     * 平衡点：典型元数据/分析查询多在 90s 内完成走同步内联；真正长查询才转异步 + 回喂，
     * 避免既不长时间阻塞 skill 线程、也不让常见查询多付一次回喂往返。
     * 实际生效值 = min(此值, stepTimeout - 拉取预留)，见 SqlToolService。
     */
    private long asyncSoftTimeoutMs = 90000L;

    /** Explicit deployment override for diagnosing the async handoff; zero keeps the UI setting authoritative. */
    private long asyncSoftTimeoutOverrideMs = 0L;

    /** finish 前等待所有异步结果的最大超时（默认 5 分钟） */
    private long asyncQueryAwaitAllMs = 300000L;

    // ═══════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════

    // -- 主循环迭代 --
    public int getDefaultMaxIterations() { return defaultMaxIterations; }
    public int getMinIterations() { return minIterations; }
    public int getSimpleQueryFastPathMaxIterations() { return simpleQueryFastPathMaxIterations; }
    public int getReasoningBaseIterations() { return reasoningBaseIterations; }

    // -- 主循环重试 --
    public int getMaxRetries() { return maxRetries; }
    public long getRetryDelayBaseMs() { return retryDelayBaseMs; }
    public int getMaxFinishAttempts() { return maxFinishAttempts; }
    public int getMaxSameSqlFailures() { return maxSameSqlFailures; }
    public int getMaxConsecutiveFailures() { return maxConsecutiveFailures; }
    public long getConfirmationTimeoutMs() { return confirmationTimeoutMs; }
    public int getRecentCallLimit() { return recentCallLimit; }

    // -- 主循环 Remediation --
    public int getRemediationReExecuteLimit() { return remediationReExecuteLimit; }
    public int getNoSnippetMatchLoopCutoff() { return noSnippetMatchLoopCutoff; }

    // -- AgentEngine 检查点 --
    public int getCheckpointInterval() { return checkpointInterval; }
    public long getCheckpointTtlMs() { return checkpointTtlMs; }

    // -- AgentToolService --
    public long getPollIntervalMs() { return pollIntervalMs; }
    public long getPollInfoLogIntervalSec() { return pollInfoLogIntervalSec; }
    public int getRowExplosionThreshold() { return rowExplosionThreshold; }
    public long getStalePartitionDays() { return stalePartitionDays; }

    // -- 主循环全局超时 --
    public long getGlobalTimeoutMs() { return globalTimeoutMs; }

    /**
     * 设置界面(AgentAdvancedSettings)覆盖:SQL 软超时 / 后台上限优先取 UI 配置,
     * UI 未配(或不可用)时回退到本类的 @Value 默认。可选注入,测试可不装配。
     */
    private AgentAdvancedSettings advancedConfig;

    public void setAdvancedConfig(AgentAdvancedSettings advancedConfig) {
        this.advancedConfig = advancedConfig;
    }

    // -- 异步 executeQuery --
    public boolean isAsyncQueryEnabled() { return asyncQueryEnabled; }

    /** SQL 后台运行上限:优先设置界面值,回退 @Value 默认。 */
    public long getAsyncQueryTimeoutMs() {
        return advancedConfig != null ? advancedConfig.getSqlMaxTimeoutMs() : asyncQueryTimeoutMs;
    }

    /** SQL 转后台软超时:显式诊断覆盖优先,否则界面值优先,最后回退 @Value 默认。 */
    public long getAsyncSoftTimeoutMs() {
        if (asyncSoftTimeoutOverrideMs > 0) return asyncSoftTimeoutOverrideMs;
        return advancedConfig != null ? advancedConfig.getSqlSoftTimeoutMs() : asyncSoftTimeoutMs;
    }

    public long getAsyncQueryAwaitAllMs() { return asyncQueryAwaitAllMs; }

    public void configure(java.util.Map<String,?> values) {
        if (values.containsKey("maxquery.agent.rules.default-max-iterations")) this.defaultMaxIterations = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.default-max-iterations")));
        if (values.containsKey("maxquery.agent.rules.min-iterations")) this.minIterations = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.min-iterations")));
        if (values.containsKey("maxquery.agent.rules.simple-query-fast-path-max-iterations")) this.simpleQueryFastPathMaxIterations = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.simple-query-fast-path-max-iterations")));
        if (values.containsKey("maxquery.agent.rules.reasoning-base-iterations")) this.reasoningBaseIterations = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.reasoning-base-iterations")));
        if (values.containsKey("maxquery.agent.rules.max-retries")) this.maxRetries = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.max-retries")));
        if (values.containsKey("maxquery.agent.rules.retry-delay-base-ms")) this.retryDelayBaseMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.retry-delay-base-ms")));
        if (values.containsKey("maxquery.agent.rules.max-finish-attempts")) this.maxFinishAttempts = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.max-finish-attempts")));
        if (values.containsKey("maxquery.agent.rules.max-same-sql-failures")) this.maxSameSqlFailures = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.max-same-sql-failures")));
        if (values.containsKey("maxquery.agent.rules.max-consecutive-failures")) this.maxConsecutiveFailures = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.max-consecutive-failures")));
        if (values.containsKey("maxquery.agent.rules.confirmation-timeout-ms")) this.confirmationTimeoutMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.confirmation-timeout-ms")));
        if (values.containsKey("maxquery.agent.rules.recent-call-limit")) this.recentCallLimit = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.recent-call-limit")));
        if (values.containsKey("maxquery.agent.rules.remediation-re-execute-limit")) this.remediationReExecuteLimit = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.remediation-re-execute-limit")));
        if (values.containsKey("maxquery.agent.rules.no-snippet-match-loop-cutoff")) this.noSnippetMatchLoopCutoff = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.no-snippet-match-loop-cutoff")));
        if (values.containsKey("maxquery.agent.rules.checkpoint-interval")) this.checkpointInterval = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.checkpoint-interval")));
        if (values.containsKey("maxquery.agent.rules.checkpoint-ttl-ms")) this.checkpointTtlMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.checkpoint-ttl-ms")));
        if (values.containsKey("maxquery.agent.rules.poll-interval-ms")) this.pollIntervalMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.poll-interval-ms")));
        if (values.containsKey("maxquery.agent.rules.poll-info-log-interval-sec")) this.pollInfoLogIntervalSec = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.poll-info-log-interval-sec")));
        if (values.containsKey("maxquery.agent.rules.row-explosion-threshold")) this.rowExplosionThreshold = Integer.parseInt(String.valueOf(values.get("maxquery.agent.rules.row-explosion-threshold")));
        if (values.containsKey("maxquery.agent.rules.stale-partition-days")) this.stalePartitionDays = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.stale-partition-days")));
        if (values.containsKey("maxquery.agent.rules.global-timeout-ms")) this.globalTimeoutMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.global-timeout-ms")));
        if (values.containsKey("maxquery.agent.rules.async-query-enabled")) this.asyncQueryEnabled = Boolean.parseBoolean(String.valueOf(values.get("maxquery.agent.rules.async-query-enabled")));
        if (values.containsKey("maxquery.agent.rules.async-query-timeout-ms")) this.asyncQueryTimeoutMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.async-query-timeout-ms")));
        if (values.containsKey("maxquery.agent.rules.async-soft-timeout-ms")) this.asyncSoftTimeoutMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.async-soft-timeout-ms")));
        if (values.containsKey("maxquery.agent.rules.async-soft-timeout-override-ms")) this.asyncSoftTimeoutOverrideMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.async-soft-timeout-override-ms")));
        if (values.containsKey("maxquery.agent.rules.async-query-await-all-ms")) this.asyncQueryAwaitAllMs = Long.parseLong(String.valueOf(values.get("maxquery.agent.rules.async-query-await-all-ms")));
    }
}
