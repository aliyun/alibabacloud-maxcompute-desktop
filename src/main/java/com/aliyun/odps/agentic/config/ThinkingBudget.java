package com.aliyun.odps.agentic.config;


import java.util.Set;

/**
 * Thinking Budget 配置——各 RequestLane 的推理预算（仅对 reasoning-capable 模型生效）。
 *
 * <p><b>本类的 Java 缺省值是各 lane 预算的单一真源</b>（2026-09-02）。
 * application.properties 曾平行声明过一套更小的值（planner 512 / tool-call 0 /
 * final-answer 0），于是改这里的缺省值一律不生效 —— 那些行已删除，
 * 临时调整请用环境变量（如 {@code AGENT_THINKING_PLANNER_BUDGET}）。
 *
 * 配置项前缀 {@code agent.thinking}：
 * - planner-budget: PLANNER lane 预算（复杂分析）
 * - verifier-budget: VERIFIER lane 预算（见下方字段注释的可达性说明）
 * - tool-call-budget: TOOL_CALL lane 预算
 * - final-answer-budget: FINAL_ANSWER lane 预算
 * - adaptive-enabled: 失败信号驱动的 PLANNER 预算升级
 * - adaptive-max-multiplier: 升级倍数上限
 * - adaptive-budget-ceiling: 升级后的绝对 token 封顶
 */
public class ThinkingBudget {

    /**
     * PLANNER lane 预算（复杂分析需要深度推理）
     */
    private int plannerBudget = 4096;

    /**
     * VERIFIER lane 预算。
     *
     * <p><b>当前不可达</b>：主助手 SDK 宿主的 lane 选择只会
     * 产出 PLANNER / TOOL_CALL / FINAL_ANSWER 三种，从不产出 VERIFIER；而旁路调用（SQL 审查、
     * Expert Critic、自省写手等）走的是 {@code RequestGroup.VERIFIER} 限流分组，与
     * {@code RequestLaneHint.VERIFIER} 是两套东西，不读本预算。
     * 保留字段是为了 lane 枚举完整性（{@code mapLane} / {@code mapLaneHint} 的 switch 需要
     * 全分支）；写此注释是为了避开“改了 VERIFIER 就会生效”这个误判。
     */
    private int verifierBudget = 1024;

    /**
     * TOOL_CALL lane 预算。是否开思考由 {@code FunctionCallingProtocol.resolveToolCallThinking}
     * 按“推理策略 + 档位 + 是否有新证据待消化”判定（见 {@link #laneThinkingPolicy}）。
     */
    private int toolCallBudget = 1024;

    /**
     * FINAL_ANSWER lane 预算。
     *
     * <p>收尾轮要整合全部已收集的证据，是思考价值最高的轮次之一，而且每个 session
     * 只发生一次 —— 延迟代价是一次性的，不随轮数放大。
     */
    private int finalAnswerBudget = 2048;

    /**
     * lane → thinking 开关的分配策略。
     *
     * <ul>
     *   <li>{@code sota}（默认）—— 主循环每一轮都思考：PLANNER / FINAL_ANSWER 恒开，
     *       TOOL_CALL 按推理策略分档（fixed 每轮按用户档位；自动/平衡等策略下 DEEP 恒开、
     *       其余档位仅在有新证据待消化时开）。旁路调用（标题/压缩/critic/反思）仍一律关。</li>
     *   <li>{@code legacy} —— 只有 PLANNER 开，其余 lane 全关（本次改造前的行为，留作退路）。</li>
     * </ul>
     *
     * <p>改成 sota 的依据：对 opencode / qwen-code / OpenHands / pydantic-ai / openai-agents-python /
     * Google ADK / crewAI / deepseek-harness 等 13 个项目的 agent loop 比对显示，“思考”是
     * 请求级参数、在主循环的每一次 LLM 调用中都携带；“只在开头思考一次”不属于任何
     * 一家的主循环设计。省 token 发生在旁路调用与角色分配两个维度。
     */
    private String laneThinkingPolicy = "sota";

    /**
     * 失败信号驱动的 PLANNER 预算升级。
     *
     * <p><b>默认关</b>：本机制是 lane-based 关思考时代的补偿 —— 当时只有 PLANNER 轮能思考，
     * 所以要在 reminder 后给那一轮加预算。{@code laneThinkingPolicy=sota} 下每轮都在思考，
     * 这份补偿的必要性大幅下降；而且上述 13 个项目的一致做法是档位选定后会话级恒定，
     * 不做按轮动态调整。保留实现供特定场景（如回退到 legacy 策略）手动开启。
     */
    private boolean adaptiveEnabled = false;

    /**
     * 升级倍数上限（相对 plannerBudget）。
     */
    private double adaptiveMaxMultiplier = 2.0;

    /**
     * 升级后的绝对 token 封顶。
     *
     * <p>不是策略上限（策略由 {@link #adaptiveMaxMultiplier} 控），而是防 provider 400 的兜底：
     * 下游 {@code OpenAICompatibleClient.applyModelSpecificBudgetParam} 直接把值写进
     * {@code thinking_budget}，本身不做 clamp。plannerBudget 被调大后再翻倍就可能越过
     * 模型接受范围，这道封顶把它拦在本地。
     */
    private int adaptiveBudgetCeiling = 16384;

    // Getters
    public int getPlannerBudget() { return plannerBudget; }
    public int getVerifierBudget() { return verifierBudget; }
    public int getToolCallBudget() { return toolCallBudget; }
    public int getFinalAnswerBudget() { return finalAnswerBudget; }
    public String getLaneThinkingPolicy() { return laneThinkingPolicy; }
    /** sota 策略（默认）；只有显式写 legacy 才回到只有 PLANNER 思考的旧行为。 */
    public boolean isSotaLaneThinking() { return !"legacy".equalsIgnoreCase(laneThinkingPolicy); }
    public boolean isAdaptiveEnabled() { return adaptiveEnabled; }
    public double getAdaptiveMaxMultiplier() { return adaptiveMaxMultiplier; }
    public int getAdaptiveBudgetCeiling() { return adaptiveBudgetCeiling; }

    // Setters（配置绑定）
    public void setPlannerBudget(int plannerBudget) { this.plannerBudget = plannerBudget; }
    public void setVerifierBudget(int verifierBudget) { this.verifierBudget = verifierBudget; }
    public void setToolCallBudget(int toolCallBudget) { this.toolCallBudget = toolCallBudget; }
    public void setFinalAnswerBudget(int finalAnswerBudget) { this.finalAnswerBudget = finalAnswerBudget; }
    public void setLaneThinkingPolicy(String laneThinkingPolicy) {
        this.laneThinkingPolicy = laneThinkingPolicy != null ? laneThinkingPolicy : "sota";
    }
    public void setAdaptiveEnabled(boolean adaptiveEnabled) { this.adaptiveEnabled = adaptiveEnabled; }
    public void setAdaptiveMaxMultiplier(double adaptiveMaxMultiplier) { this.adaptiveMaxMultiplier = adaptiveMaxMultiplier; }
    public void setAdaptiveBudgetCeiling(int adaptiveBudgetCeiling) { this.adaptiveBudgetCeiling = adaptiveBudgetCeiling; }

    /**
     * 按失败信号数量抬高 PLANNER 预算（纯函数，同输入恒同输出）。
     *
     * <p>为什么需要它：reminder 后主助手会把 lane 切回 PLANNER（重新开 thinking），
     * 但预算仍是同一个 plannerBudget —— 第 3 次返工与第 1 次用一样的思考量，
     * 失败信号和推理深度之间需要闭环。
     *
     * <p>递增而非翻倍：每个信号 +50%，到 {@link #adaptiveMaxMultiplier} 封顶。
     * 取信号“数量”而不是“是否”，是为了让连续走弯路的任务拿到更多预算。
     *
     * <p><b>适用模型族</b>：Qwen3 / GLM 收 {@code thinking_budget}。DeepSeek-V4 实测（2026-09-02，
     * 探针 {@code .ai/probe-deepseek-v4-budget.py}）同样收 {@code thinking_budget} 且是硬上限
     * （发 512 则 reasoning_tokens 精确为 512），所以抬预算对它也有效。
     *
     * @param failureSignals 本轮之前累积的失败信号数（负值 / 0 视为无信号）
     * @return 生效预算；关闭开关或无信号时恒为 {@link #plannerBudget}
     */
    public int resolveAdaptivePlannerBudget(int failureSignals) {
        return escalatePlannerBudget(plannerBudget, failureSignals,
            adaptiveEnabled, adaptiveMaxMultiplier, adaptiveBudgetCeiling);
    }

    /**
     * {@link #resolveAdaptivePlannerBudget(int)} 的静态纯函数版。
     *
     * <p>SDK 宿主从 config map 读取策略标量，因此保留静态纯函数，供请求上下文
     * 与配置测试共用，避免升级规则出现两份实现。
     */
    public static int escalatePlannerBudget(int base, int failureSignals,
                                            boolean enabled, double maxMultiplier, int ceiling) {
        if (!enabled || failureSignals <= 0 || base <= 0) {
            return base;
        }
        // 倍数不得低于 1.0，否则把 maxMultiplier 误配成 <1 会变成静默降级
        double capped = Math.max(1.0, maxMultiplier);
        double multiplier = Math.min(1.0 + 0.5 * failureSignals, capped);
        long escalated = Math.round(base * multiplier);
        return (int) Math.min(escalated, Math.max(base, ceiling));
    }

    /**
     * “结果内容不可预测、需要 LLM 判断”的工具名单，供
     * {@link #toolResultNeedsDigest(String, boolean)} 使用。
     *
     * <p>四个查询执行类工具全部列入：它们在 {@code AgentEngine} 的 display-kind 分类里
     * 并列为 QUERY_RESULT（返回真实数据行），只收 executeQuery 一个会让 SQL 片段 /
     * 预览 / ODPS 直运行这三条路径的证据轮沉默。
     */
    private static final Set<String> EVIDENCE_TOOLS = Set.of(
        "executeQuery", "executeSqlSnippet", "runOdpsQuery", "getTablePreview",
        "getTableStatistics", "searchSqlKnowledge");

    /**
     * “结果内容不可预测、需要 LLM 判断”的工具。
     *
     * <p>划分标准是结果要不要“看了才知道怎么办”：
     * <ul>
     *   <li>executeQuery / executeSqlSnippet / runOdpsQuery / getTablePreview —— 返回真实数据
     *       或 SQL 报错，下一步完全取决于内容</li>
     *   <li>getTableStatistics —— 统计值需要解读（分布、空值率、数量级）</li>
     *   <li>searchSqlKnowledge —— 检索命中要判相关性，可能全部不适用</li>
     * </ul>
     * schema 类工具（listTables / getTableSchema / getMultiTableSchema / listProjects）不列入：
     * 它们返回确定形状的结构化元数据，直接可用，思考收益低。
     *
     * @param toolName 上一轮执行的工具名
     * @param failed   该工具是否失败
     * @return true 表示下一轮值得开思考消化这份结果
     */
    public static boolean toolResultNeedsDigest(String toolName, boolean failed) {
        // 失败是零维护的判据：不管哪个工具，报错后那一轮要做的就是纠错推理，
        // 而纠错正是 interleaved thinking 最初要解决的场景。无需维护名单。
        if (failed) return true;
        return toolName != null && EVIDENCE_TOOLS.contains(toolName);
    }
}
