package com.aliyun.odps.agentic.config;

import com.aliyun.odps.agentic.storage.SqlDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Agent 高级配置服务
 * 从 SQLite 加载配置并缓存在内存中
 */
public class AgentAdvancedSettings {

    private static final Logger log = LoggerFactory.getLogger(AgentAdvancedSettings.class);

    // 默认值
    public static final int DEFAULT_CONTEXT_LENGTH = 400;        // K tokens
    /**
     * 上下文窗口下界（K tokens）。取 16 而非 0：system envelope + 工具 JSON Schema 的实测输入
     * 开销就有 ~15.5K（见 {@code MessageCompactionService.estimateRequestOverheadTokens} 的注释），
     * 低于此值 {@code inputHardCeilingTokens} 会算出负天花板，A2 闸门每轮误判溢出并强折全部结果 ——
     * 那是崩溃而非降级。想「不配置」请填 0（走 {@link #DEFAULT_CONTEXT_LENGTH}），不要填 1..15。
     */
    public static final int MIN_CONTEXT_LENGTH = 16;
    /** 上下文窗口上界（K tokens）：覆盖 1M 级窗口并留一倍余量。 */
    public static final int MAX_CONTEXT_LENGTH = 2048;

    /**
     * 一个整数配置项的有效区间。存在的理由与 {@link #clampContextLength} 同源，只是把它从
     * contextLength 一个字段推到全部数值字段：此前每个字段的上下界至少写三处——
     * {@code loadFromDB} 走 clampInt 字面量、{@code save} 走内联 Math.max/min、
     * SQL 两个超时在 {@code saveSqlTimeouts} 里又写一处；而前端 SettingsDialog 的 min/max 是第四处。
     *
     * <p>同一规则多个真相源的代价已实测到：前端放行 800、旧构建后端上限 512，超出部分被
     * <b>静默截断后持久化</b>，而且抬高上限后库里仍是 512（读取路径只 clamp 不恢复）。
     * 现在所有 clamp 路径共用本表，并由 {@code GET /advanced/limits} 下发给前端派生 input 的 min/max。
     */
    public record Bound(int min, int max) {
        public int clamp(int v) { return Math.max(min, Math.min(max, v)); }
    }

    public static final Bound BOUND_CONTEXT_LENGTH = new Bound(MIN_CONTEXT_LENGTH, MAX_CONTEXT_LENGTH);
    public static final Bound BOUND_MAX_STEPS = new Bound(10, 2000);
    public static final Bound BOUND_STEP_TIMEOUT = new Bound(60, 600);
    public static final Bound BOUND_SQL_SOFT_TIMEOUT = new Bound(10, 600);
    public static final Bound BOUND_SQL_MAX_TIMEOUT = new Bound(60, 3600);
    public static final Bound BOUND_MAX_PARALLEL_TOOLS = new Bound(1, 8);
    public static final Bound BOUND_SESSION_HISTORY_SIZE = new Bound(1, 20);

    /**
     * 单次任务允许的最大推理-执行循环次数（每条 user message 触发一次 ReAct 循环的上限）。
     * Wire-up 2026-06：与 {@code StudioLoopContract.MAX_TURNS}（50）对齐——maxSteps 此前因
     * 未在主循环生效，UI 保守取 30；现接入 SDK 主助手的 turn cap。
     *
     * <p>2026-09：长 agentic 任务（多跳分析 / 大批量建表写数 / 深度研究）实测 50 轮不够用，
     * clamp 上界抬到 2000、默认抬到 200。跑飞的会话不靠轮数上限兜——SDK 宿主策略
     * 的循环检测（3 次相同批次）、无进展熔断（5 轮）、单工具频率硬停（35 次）都独立于本值，
     * 且在 plan 真推进时归零，所以只杀卡死、不杀正在推进的长任务。
     *
     * <p>DB 已有的旧值不做 migration：本值只对「从未保存过 AI 高级设置」的安装生效，
     * 已持久化 maxSteps 行的实例需在设置面板重新保存（或点恢复默认）才会跟上。
     */
    public static final int DEFAULT_MAX_STEPS = 200;
    public static final double DEFAULT_TEMPERATURE = 0.7;
    public static final int DEFAULT_STEP_TIMEOUT = 120;          // seconds
    /** SQL 转后台软超时(秒):executeQuery 同步等待多久仍未完成就转后台异步(结果自动回喂)。 */
    public static final int DEFAULT_SQL_SOFT_TIMEOUT = 90;       // seconds
    /** SQL 后台运行上限(秒):转后台后最长运行时间,超过则终止服务端查询。 */
    public static final int DEFAULT_SQL_MAX_TIMEOUT = 600;       // seconds
    public static final int DEFAULT_MAX_PARALLEL_TOOLS = 4;
    public static final int DEFAULT_SESSION_HISTORY_SIZE = 5;
    public static final int DEFAULT_PLAN_MAX_TOKENS = 1200;
    public static final int DEFAULT_CLARIFY_MAX_TOKENS = 600;

    // 推理配置默认值
    public static final String DEFAULT_REASONING_PROFILE = "auto";
    public static final String DEFAULT_REASONING_CAPABILITY_MODE = "auto";

    // Prompt locale: EN 节省 cached system prompt token，CN 保留作回滚 fallback
    public static final String DEFAULT_PROMPT_LOCALE = "EN";

    // FINAL_ANSWER lane max_tokens 上限（复杂报告/多 SQL 总结需更大空间）
    // 默认 4096；上限 16384（避免无 cap 失控）；最低 1024（短答案场景）
    public static final int DEFAULT_FINAL_ANSWER_MAX_TOKENS = 4096;
    public static final int MIN_FINAL_ANSWER_MAX_TOKENS = 1024;
    public static final int MAX_FINAL_ANSWER_MAX_TOKENS = 16384;

    // 无 AgentAdvancedSettings 可用时的输出预算回退（客户端实发 max_tokens 与 A2 闸门预留共用同一常量）
    public static final int FALLBACK_MAX_OUTPUT_TOKENS = 4096;

    // Memory 开关（默认开启 — 2026-10-08 用户裁定；DB 只存显式设置过的键，未触碰的用户跟随新默认）
    public static final boolean DEFAULT_MEMORY_ENABLED = true;

    // 快速路由开关（默认关闭 — 跳过规划直接执行简单意图，可能误判复杂查询）
    public static final boolean DEFAULT_FAST_ROUTE_ENABLED = false;

    // D6 (2026-05-26): TOOL_CALL lane 模型覆盖（默认空 = 沿用主模型，不做切换）。
    // 用户可在 ai_advanced_config 表设 toolCallLaneModel 为同 endpoint 上的更便宜模型
    // （例如 qwen-turbo），让"决策调哪个工具"走更便宜的 lane。
    public static final String DEFAULT_TOOL_CALL_LANE_MODEL = "";

    // P-7: Expert Critic — multi_agent deep research 分析质量 LLM 复审。专家模式常开，
    // 单会话调用次数由下方熔断上限约束（clamp [1,5]，默认 2）。
    public static final int DEFAULT_EXPERT_CRITIC_MAX_INVOCATIONS = 2;

    // 文本向量 / 重排序模型（Memory 知识库构建与检索用；运行时统一复用 AI 设置的 provider+apiKey）。
    // 向量空间按模型隔离——不同模型（含同族 flash/非 flash）产出向量不可互比，
    // 知识库落库时钉住 embeddingModel，切换模型 = 全量重建索引；rerank 不产生向量，可自由切换。
    public static final String DEFAULT_EMBEDDING_MODEL = "qwen3.7-text-embedding-flash";
    public static final String DEFAULT_RERANK_MODEL = "qwen3.7-text-rerank";
    public static final java.util.Set<String> EMBEDDING_MODELS = java.util.Set.of(
            "qwen3.7-text-embedding-flash", "qwen3.7-text-embedding", "text-embedding-v4");
    public static final java.util.Set<String> RERANK_MODELS = java.util.Set.of("qwen3.7-text-rerank");

    private final SqlDatabase jdbcTemplate;

    // 内存缓存
    private final AtomicReference<ConfigSnapshot> cached = new AtomicReference<>(new ConfigSnapshot());

    public AgentAdvancedSettings(SqlDatabase jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

        public void init() {
        ensureTable();
        loadFromDB();
        log.info("[AgentAdvancedSettings] Loaded: contextLength={}K, maxSteps={}, temperature={}, stepTimeout={}s, maxParallelTools={}, sessionHistorySize={}",
                getContextLength(), getMaxSteps(), getTemperature(), getStepTimeout(), getMaxParallelTools(), getSessionHistorySize());
    }

    private void ensureTable() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS ai_advanced_config (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
        """);
    }

    private void loadFromDB() {
        ConfigSnapshot snapshot = new ConfigSnapshot();
        try {
            var rows = jdbcTemplate.queryForList("SELECT key, value FROM ai_advanced_config");
            for (var row : rows) {
                String key = (String) row.get("key");
                String value = (String) row.get("value");
                if (key == null || value == null) continue;
                switch (key) {
                    // 0 是合法哨兵值（= 跟随默认），所以这里用 0 作下界放它过，再由 clampContextLength 映成默认值。
                    case "contextLength" -> snapshot.contextLength =
                        clampContextLength(clampInt(value, 0, BOUND_CONTEXT_LENGTH.max(), DEFAULT_CONTEXT_LENGTH));
                    case "maxSteps" -> snapshot.maxSteps = clampInt(value, BOUND_MAX_STEPS, DEFAULT_MAX_STEPS);
                    case "temperature" -> snapshot.temperature = clampDouble(value, 0.0, 1.0, DEFAULT_TEMPERATURE);
                    case "stepTimeout" -> snapshot.stepTimeout = clampInt(value, BOUND_STEP_TIMEOUT, DEFAULT_STEP_TIMEOUT);
                    case "sqlSoftTimeout" -> snapshot.sqlSoftTimeout = clampInt(value, BOUND_SQL_SOFT_TIMEOUT, DEFAULT_SQL_SOFT_TIMEOUT);
                    case "sqlMaxTimeout" -> snapshot.sqlMaxTimeout = clampInt(value, BOUND_SQL_MAX_TIMEOUT, DEFAULT_SQL_MAX_TIMEOUT);
                    case "maxParallelTools" -> snapshot.maxParallelTools = clampInt(value, BOUND_MAX_PARALLEL_TOOLS, DEFAULT_MAX_PARALLEL_TOOLS);
                    case "sessionHistorySize" -> snapshot.sessionHistorySize = clampInt(value, BOUND_SESSION_HISTORY_SIZE, DEFAULT_SESSION_HISTORY_SIZE);
                    case "reasoningProfile" -> snapshot.reasoningProfile = value;
                    case "fixedReasoningDepth" -> snapshot.fixedReasoningDepth = value;
                    case "reasoningCapabilityMode" -> snapshot.reasoningCapabilityMode = value;
                    case "promptLocale" -> snapshot.promptLocale = value;
                    case "finalAnswerMaxTokens" -> snapshot.finalAnswerMaxTokens = clampInt(value,
                        MIN_FINAL_ANSWER_MAX_TOKENS, MAX_FINAL_ANSWER_MAX_TOKENS, DEFAULT_FINAL_ANSWER_MAX_TOKENS);
                    case "memoryEnabled" -> snapshot.memoryEnabled = "true".equalsIgnoreCase(value);
                    case "fastRouteEnabled" -> snapshot.fastRouteEnabled = "true".equalsIgnoreCase(value);
                    case "toolCallLaneModel" -> snapshot.toolCallLaneModel = value.trim();
                    case "embeddingModel" -> snapshot.embeddingModel =
                        EMBEDDING_MODELS.contains(value.trim()) ? value.trim() : DEFAULT_EMBEDDING_MODEL;
                    case "rerankModel" -> snapshot.rerankModel =
                        RERANK_MODELS.contains(value.trim()) ? value.trim() : DEFAULT_RERANK_MODEL;
                    case "expertCriticMaxInvocations" -> snapshot.expertCriticMaxInvocations = clampInt(value, 1, 5, DEFAULT_EXPERT_CRITIC_MAX_INVOCATIONS);
                }
            }
        } catch (Exception e) {
            log.warn("[AgentAdvancedSettings] Failed to load from DB, using defaults: {}", e.getMessage());
        }
        cached.set(snapshot);
    }

    public void save(int contextLength, int maxSteps, double temperature,
                     int stepTimeout, int maxParallelTools, int sessionHistorySize) {
        save(contextLength, maxSteps, temperature, stepTimeout, maxParallelTools, sessionHistorySize,
            DEFAULT_REASONING_PROFILE, null, DEFAULT_REASONING_CAPABILITY_MODE, DEFAULT_MEMORY_ENABLED);
    }

    public void save(int contextLength, int maxSteps, double temperature,
                     int stepTimeout, int maxParallelTools, int sessionHistorySize,
                     String reasoningProfile, String fixedReasoningDepth, String reasoningCapabilityMode) {
        save(contextLength, maxSteps, temperature, stepTimeout, maxParallelTools, sessionHistorySize,
            reasoningProfile, fixedReasoningDepth, reasoningCapabilityMode, DEFAULT_MEMORY_ENABLED);
    }

    public void save(int contextLength, int maxSteps, double temperature,
                     int stepTimeout, int maxParallelTools, int sessionHistorySize,
                     String reasoningProfile, String fixedReasoningDepth, String reasoningCapabilityMode,
                     boolean memoryEnabled, boolean fastRouteEnabled) {
        save(contextLength, maxSteps, temperature, stepTimeout, maxParallelTools, sessionHistorySize,
            reasoningProfile, fixedReasoningDepth, reasoningCapabilityMode, memoryEnabled);
        upsert("fastRouteEnabled", String.valueOf(fastRouteEnabled));
        cached.updateAndGet(prev -> {
            ConfigSnapshot next = new ConfigSnapshot(prev);
            next.fastRouteEnabled = fastRouteEnabled;
            return next;
        });
    }

    public void save(int contextLength, int maxSteps, double temperature,
                     int stepTimeout, int maxParallelTools, int sessionHistorySize,
                     String reasoningProfile, String fixedReasoningDepth, String reasoningCapabilityMode,
                     boolean memoryEnabled) {
        final int clampedContextLength = clampContextLength(contextLength);
        final int clampedMaxSteps = BOUND_MAX_STEPS.clamp(maxSteps);
        final double clampedTemperature = Math.max(0.0, Math.min(1.0, temperature));
        final int clampedStepTimeout = BOUND_STEP_TIMEOUT.clamp(stepTimeout);
        final int clampedMaxParallelTools = BOUND_MAX_PARALLEL_TOOLS.clamp(maxParallelTools);
        final int clampedSessionHistorySize = BOUND_SESSION_HISTORY_SIZE.clamp(sessionHistorySize);
        final String resolvedReasoningProfile = reasoningProfile != null ? reasoningProfile : DEFAULT_REASONING_PROFILE;
        final String resolvedReasoningCapabilityMode = reasoningCapabilityMode != null
            ? reasoningCapabilityMode : DEFAULT_REASONING_CAPABILITY_MODE;
        final String resolvedFixedReasoningDepth = fixedReasoningDepth;

        upsert("contextLength", String.valueOf(clampedContextLength));
        upsert("maxSteps", String.valueOf(clampedMaxSteps));
        upsert("temperature", String.valueOf(clampedTemperature));
        upsert("stepTimeout", String.valueOf(clampedStepTimeout));
        upsert("maxParallelTools", String.valueOf(clampedMaxParallelTools));
        upsert("sessionHistorySize", String.valueOf(clampedSessionHistorySize));

        // Memory 配置
        upsert("memoryEnabled", String.valueOf(memoryEnabled));

        // 推理配置
        if (reasoningProfile != null) {
            upsert("reasoningProfile", reasoningProfile);
        }
        if (fixedReasoningDepth != null) {
            upsert("fixedReasoningDepth", fixedReasoningDepth);
        }
        if (reasoningCapabilityMode != null) {
            upsert("reasoningCapabilityMode", reasoningCapabilityMode);
        }

        // 更新内存缓存：函数式派生（PATCH 语义）—— 未在 save 签名中的 sidechannel
        // 字段（promptLocale / finalAnswerMaxTokens / toolCallLaneModel，仅靠
        // loadFromDB / DB 旁路 upsert 写入）从 prev 继承，避免被静默吞回 default。
        // 修复 2026-06：之前是 new ConfigSnapshot() 全替换，任何一次 save 都会让上述
        // sidechannel 字段在内存层退回默认值，直到下次重启从 DB 重读才恢复。
        cached.updateAndGet(prev -> {
            ConfigSnapshot next = new ConfigSnapshot(prev);
            next.contextLength = clampedContextLength;
            next.maxSteps = clampedMaxSteps;
            next.temperature = clampedTemperature;
            next.stepTimeout = clampedStepTimeout;
            next.maxParallelTools = clampedMaxParallelTools;
            next.sessionHistorySize = clampedSessionHistorySize;
            next.reasoningProfile = resolvedReasoningProfile;
            next.fixedReasoningDepth = resolvedFixedReasoningDepth;
            next.reasoningCapabilityMode = resolvedReasoningCapabilityMode;
            next.memoryEnabled = memoryEnabled;
            return next;
        });

        log.info("[AgentAdvancedSettings] Saved: contextLength={}K, maxSteps={}, temperature={}, stepTimeout={}s, maxParallelTools={}, sessionHistorySize={}, reasoningProfile={}",
                clampedContextLength, clampedMaxSteps, clampedTemperature, clampedStepTimeout,
                clampedMaxParallelTools, clampedSessionHistorySize, resolvedReasoningProfile);
    }

    private void upsert(String key, String value) {
        jdbcTemplate.update(
            "INSERT INTO ai_advanced_config (key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value = ?",
            key, value, value
        );
    }

    // ==================== Getters ====================

    /** 上下文长度，单位 K tokens */
    public int getContextLength() { return cached.get().contextLength; }

    /** 上下文长度，转换为 token 数 */
    public int getContextLengthTokens() { return cached.get().contextLength * 1000; }

    /**
     * 默认输出预算（{@code max_tokens}）：{@code min(16384, contextWindow * 5%)}，下限 1024。
     *
     * <p>此前该公式只内联在 {@code OpenAICompatibleClient.buildRequestBodyFromSnapshot}，
     * 而 A2 输入闸门（{@code MessageCompactionService.inputHardCeilingTokens}）另写死 4K 预留：
     * 同一条 provider 协议不变量（{@code prompt + max_tokens ≤ contextWindow}）的两半各自取值、
     * 互不知情 —— 120K 窗口下闸门放行 116K 输入 + 客户端实发 6K 输出 = 122K，必被 provider 拒。
     * 收敛为单一源后，闸门预留与实发输出恒等（2026-08-23）。</p>
     *
     * <p>无配置回退值见 {@link #FALLBACK_MAX_OUTPUT_TOKENS}（客户端 / 闸门共用）。</p>
     */
    public int getDefaultMaxOutputTokens() {
        return defaultMaxOutputTokens(getContextLengthTokens());
    }

    /**
     * {@link #getDefaultMaxOutputTokens()} 的纯函数形式（供拿不到实例的 static 调用方用，
     * 如 {@code MessageCompactionService.computeBudget}）。抽出的理由：那边原本写死 4K 预留，
     * 与本公式是同一个量的两个值；复制公式而非共用会把双轨换个位置保留。
     */
    public static int defaultMaxOutputTokens(int contextLengthTokens) {
        return Math.max(1024, Math.min(16384, (int) (contextLengthTokens * 0.05)));
    }

    public int getMaxSteps() { return cached.get().maxSteps; }

    public double getTemperature() { return cached.get().temperature; }

    /** 模型感知温度：Qwen DashScope 下限 0.55，其余模型使用配置值 */
    public double getModelAwareTemperature(boolean qwenDashScope) {
        double base = getTemperature();
        if (qwenDashScope) {
            return Math.min(base, 0.55);
        }
        return base;
    }

    /** 单步超时，单位毫秒 */
    public long getStepTimeoutMs() { return cached.get().stepTimeout * 1000L; }

    /** 单步超时，单位秒 */
    public int getStepTimeout() { return cached.get().stepTimeout; }

    /** SQL 转后台软超时(秒/毫秒):同步等待多久转后台异步。 */
    public int getSqlSoftTimeout() { return cached.get().sqlSoftTimeout; }
    public long getSqlSoftTimeoutMs() { return cached.get().sqlSoftTimeout * 1000L; }

    /** SQL 后台运行上限(秒/毫秒):转后台后最长运行时间。 */
    public int getSqlMaxTimeout() { return cached.get().sqlMaxTimeout; }
    public long getSqlMaxTimeoutMs() { return cached.get().sqlMaxTimeout * 1000L; }

    /**
     * 保存 SQL 超时配置(转后台软超时 + 后台运行上限,单位秒)。持久化 + 更新缓存。
     * 与主 save() 分开:属 sidechannel 字段,不污染主 save 签名。
     */
    public void saveSqlTimeouts(int sqlSoftTimeoutSec, int sqlMaxTimeoutSec) {
        final int soft = BOUND_SQL_SOFT_TIMEOUT.clamp(sqlSoftTimeoutSec);
        final int max = BOUND_SQL_MAX_TIMEOUT.clamp(sqlMaxTimeoutSec);
        upsert("sqlSoftTimeout", String.valueOf(soft));
        upsert("sqlMaxTimeout", String.valueOf(max));
        cached.updateAndGet(prev -> {
            ConfigSnapshot next = new ConfigSnapshot(prev);
            next.sqlSoftTimeout = soft;
            next.sqlMaxTimeout = max;
            return next;
        });
        log.info("[AgentAdvancedSettings] Saved SQL timeouts: soft={}s, max={}s", soft, max);
    }

    /**
     * 保存文本向量/重排序模型选择。持久化 + 更新缓存。
     * sidechannel 字段，不进主 save() 签名（同 saveSqlTimeouts 模式）。
     */
    public void saveRetrievalModels(String embeddingModel, String rerankModel) {
        final String emb = embeddingModel != null && EMBEDDING_MODELS.contains(embeddingModel.trim())
                ? embeddingModel.trim() : DEFAULT_EMBEDDING_MODEL;
        final String rerank = rerankModel != null && RERANK_MODELS.contains(rerankModel.trim())
                ? rerankModel.trim() : DEFAULT_RERANK_MODEL;
        upsert("embeddingModel", emb);
        upsert("rerankModel", rerank);
        cached.updateAndGet(prev -> {
            ConfigSnapshot next = new ConfigSnapshot(prev);
            next.embeddingModel = emb;
            next.rerankModel = rerank;
            return next;
        });
        log.info("[AgentAdvancedSettings] Saved retrieval models: embedding={}, rerank={}", emb, rerank);
    }

    public String getEmbeddingModel() { return cached.get().embeddingModel; }

    public String getRerankModel() { return cached.get().rerankModel; }

    public int getMaxParallelTools() { return cached.get().maxParallelTools; }

    public int getSessionHistorySize() { return cached.get().sessionHistorySize; }

    /** 计划生成阶段的输出 token 上限。当前采用保守固定值，避免影响执行阶段。 */
    public int getPlanMaxTokens() { return DEFAULT_PLAN_MAX_TOKENS; }

    /** 需求澄清阶段的输出 token 上限。当前采用保守固定值，避免影响执行阶段。 */
    public int getClarifyMaxTokens() { return DEFAULT_CLARIFY_MAX_TOKENS; }

    // ==================== 推理配置 Getters ====================

    /** 推理策略: auto/fast/balanced/deep/fixed */
    public String getReasoningProfile() { return cached.get().reasoningProfile; }

    /** 固定推理深度: NONE/LIGHT/MEDIUM/DEEP (仅当 reasoningProfile=fixed 时生效) */
    public String getFixedReasoningDepth() { return cached.get().fixedReasoningDepth; }

    /** 模型推理能力模式: auto/supported/unsupported */
    public String getReasoningCapabilityMode() { return cached.get().reasoningCapabilityMode; }

    /** Prompt 渲染语言（EN/CN），默认 EN 节省 cached prompt token */
    public PromptLocale getPromptLocale() { return PromptLocale.fromConfig(cached.get().promptLocale); }

    /** FINAL_ANSWER lane max_tokens 上限（默认 4096，可调到 16384 用于复杂分析报告） */
    public int getFinalAnswerMaxTokens() { return cached.get().finalAnswerMaxTokens; }

    /** Memory 开关（默认关闭） */
    public boolean isMemoryEnabled() { return cached.get().memoryEnabled; }

    /** 快速路由开关（默认关闭） */
    public boolean isFastRouteEnabled() { return cached.get().fastRouteEnabled; }

    /** 仅更新内存快照，不写 DB——供测试和运行时热切换使用 */
    public void setFastRouteEnabled(boolean enabled) {
        cached.updateAndGet(prev -> {
            ConfigSnapshot next = new ConfigSnapshot(prev);
            next.fastRouteEnabled = enabled;
            return next;
        });
    }

    /**
     * D6 (2026-05-26): TOOL_CALL lane 模型覆盖。返回非空字符串时，
     * {@code OpenAICompatibleClient.buildRequestBodyFromSnapshot} 会用此 modelId 覆盖
     * snapshot.modelName() 仅对 RequestLane.TOOL_CALL 生效。默认空 = 不切换。
     */
    public String getToolCallLaneModel() { return cached.get().toolCallLaneModel; }

    /** P-7: Expert Critic 单会话最大调用次数（clamp [1,5]，默认 2；专家模式复审熔断上限） */
    public int getExpertCriticMaxInvocations() { return cached.get().expertCriticMaxInvocations; }

    // ==================== 内部类 ====================

    private static class ConfigSnapshot {
        int contextLength = DEFAULT_CONTEXT_LENGTH;
        int maxSteps = DEFAULT_MAX_STEPS;
        double temperature = DEFAULT_TEMPERATURE;
        int stepTimeout = DEFAULT_STEP_TIMEOUT;
        int sqlSoftTimeout = DEFAULT_SQL_SOFT_TIMEOUT;
        int sqlMaxTimeout = DEFAULT_SQL_MAX_TIMEOUT;
        int maxParallelTools = DEFAULT_MAX_PARALLEL_TOOLS;
        int sessionHistorySize = DEFAULT_SESSION_HISTORY_SIZE;
        String reasoningProfile = DEFAULT_REASONING_PROFILE;
        String fixedReasoningDepth = null;
        String reasoningCapabilityMode = DEFAULT_REASONING_CAPABILITY_MODE;
        String promptLocale = DEFAULT_PROMPT_LOCALE;
        int finalAnswerMaxTokens = DEFAULT_FINAL_ANSWER_MAX_TOKENS;
        boolean memoryEnabled = DEFAULT_MEMORY_ENABLED;
        boolean fastRouteEnabled = DEFAULT_FAST_ROUTE_ENABLED;
        String toolCallLaneModel = DEFAULT_TOOL_CALL_LANE_MODEL;
        int expertCriticMaxInvocations = DEFAULT_EXPERT_CRITIC_MAX_INVOCATIONS;
        String embeddingModel = DEFAULT_EMBEDDING_MODEL;
        String rerankModel = DEFAULT_RERANK_MODEL;

        ConfigSnapshot() { }

        /**
         * 拷贝构造 —— 供 {@link #save} 路径派生新 snapshot 用。
         *
         * <p>{@code save()} 接受的入参只覆盖前 10 个字段，其余字段（仅靠
         * {@code loadFromDB} 或 DB 旁路 upsert 写入的 sidechannel：
         * {@code promptLocale} / {@code finalAnswerMaxTokens} /
         * {@code toolCallLaneModel}）必须从 prev 完整继承——否则 save 后内存值会静默回退到字段默认。
         *
         * <p>新增字段约束：在此处再加一行赋值；
         * {@code AgentAdvancedSettingsSidechannelTest} 守门防回归。
         */
        ConfigSnapshot(ConfigSnapshot src) {
            this.contextLength = src.contextLength;
            this.maxSteps = src.maxSteps;
            this.temperature = src.temperature;
            this.stepTimeout = src.stepTimeout;
            this.sqlSoftTimeout = src.sqlSoftTimeout;
            this.sqlMaxTimeout = src.sqlMaxTimeout;
            this.maxParallelTools = src.maxParallelTools;
            this.sessionHistorySize = src.sessionHistorySize;
            this.reasoningProfile = src.reasoningProfile;
            this.fixedReasoningDepth = src.fixedReasoningDepth;
            this.reasoningCapabilityMode = src.reasoningCapabilityMode;
            this.promptLocale = src.promptLocale;
            this.finalAnswerMaxTokens = src.finalAnswerMaxTokens;
            this.memoryEnabled = src.memoryEnabled;
            this.fastRouteEnabled = src.fastRouteEnabled;
            this.toolCallLaneModel = src.toolCallLaneModel;
            this.expertCriticMaxInvocations = src.expertCriticMaxInvocations;
            this.embeddingModel = src.embeddingModel;
            this.rerankModel = src.rerankModel;
        }
    }

    /**
     * 上下文窗口 clamp 的唯一实现（load 与 save 两条路径共用）。
     *
     * <p>此前两条路径各写一份 {@code [8,512]}（load 走 clampInt、save 走内联 Math.max/min），
     * 同一规则两个真相源；改上下界时漏改一处就会让「存进去的值」与「读出来的值」不一致。
     *
     * <p>{@code 0} = 未配置 → 落 {@link #DEFAULT_CONTEXT_LENGTH}，与解析失败共用同一条回退路径
     * （不新增分支）。有效区间见 {@link #MIN_CONTEXT_LENGTH} / {@link #MAX_CONTEXT_LENGTH}。
     */
    public static int clampContextLength(int contextLength) {
        if (contextLength <= 0) return DEFAULT_CONTEXT_LENGTH;
        return BOUND_CONTEXT_LENGTH.clamp(contextLength);
    }

    /**
     * 供 {@code GET /advanced/limits} 下发：字段名 → 有效区间。key 与前端
     * {@code AIAdvancedConfig} 的字段名一致，顺序按设置面板的展示顺序。
     *
     * <p>temperature 不在表内：它是 0~1 的归一化量且 UI 用 range 滑块（滑块无法输入越界值），
     * 不存在「填超了被静默截断」这条路径。
     */
    public static Map<String, Bound> limits() {
        Map<String, Bound> m = new LinkedHashMap<>();
        m.put("contextLength", BOUND_CONTEXT_LENGTH);
        m.put("maxSteps", BOUND_MAX_STEPS);
        m.put("stepTimeout", BOUND_STEP_TIMEOUT);
        m.put("sqlSoftTimeout", BOUND_SQL_SOFT_TIMEOUT);
        m.put("sqlMaxTimeout", BOUND_SQL_MAX_TIMEOUT);
        m.put("maxParallelTools", BOUND_MAX_PARALLEL_TOOLS);
        m.put("sessionHistorySize", BOUND_SESSION_HISTORY_SIZE);
        return m;
    }

    /** {@link #clampInt(String, int, int, int)} 的 Bound 重载 —— 让 loadFromDB 与 save 读同一张表。 */
    private static int clampInt(String value, Bound bound, int defaultValue) {
        return clampInt(value, bound.min(), bound.max(), defaultValue);
    }

    private static int clampInt(String value, int min, int max, int defaultValue) {
        try {
            int v = Integer.parseInt(value);
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static double clampDouble(String value, double min, double max, double defaultValue) {
        try {
            double v = Double.parseDouble(value);
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
