package com.aliyun.odps.agentic.config;

/**
 * AI 配置只读快照
 * 用于避免重复查询数据库和 URL 规范化
 */
public record AIConfigSnapshot(
    String originalApiUrl,
    String normalizedApiUrl,
    String apiKey,
    String modelName,
    long loadedAt,
    boolean useBuiltinAI
) {
    public AIConfigSnapshot(String originalApiUrl, String normalizedApiUrl, String apiKey,
                            String modelName, long loadedAt) {
        this(originalApiUrl, normalizedApiUrl, apiKey, modelName, loadedAt, false);
    }

    /**
     * 检查快照是否有效（非空必要字段）
     */
    public boolean isValid() {
        if (modelName == null || modelName.isBlank()) {
            return false;
        }
        return useBuiltinAI || (normalizedApiUrl != null && !normalizedApiUrl.isBlank()
            && apiKey != null && !apiKey.isBlank());
    }

    /**
     * 判断是否走阿里云百炼/DashScope OpenAI-compatible endpoint。
     * 只看 endpoint，不限制模型族；GLM、Qwen 等都可能通过该 endpoint 暴露。
     */
    public boolean isDashScopeEndpoint() {
        return normalizedApiUrl != null
            && normalizedApiUrl.contains("dashscope.aliyuncs.com");
    }

    /**
     * 检查快照是否过期
     * @param ttlMs TTL 毫秒数
     * @return 是否过期
     */
    public boolean isExpired(long ttlMs) {
        return System.currentTimeMillis() - loadedAt > ttlMs;
    }

    /**
     * 判断是否是 Qwen/DashScope provider
     * 用于 Qwen-aware 特性的 provider gating
     *
     * 严格判断条件（必须同时满足）：
     * 1. apiUrl 包含 dashscope.aliyuncs.com
     * 2. modelName 以 qwen 开头（qwen/qwen3/qwen2/qwen-max 等）
     *
     * 这样避免：
     * - 非百炼端点上的 qwen-compatible 模型误判
     * - 百炼上的非 Qwen 模型误判
     */
    public boolean isQwenDashScope() {
        // 必须同时满足 URL 和 modelName 条件
        boolean isDashScopeUrl = isDashScopeEndpoint();

        boolean isQwenModel = modelName != null
            && modelName.toLowerCase().startsWith("qwen");

        return isDashScopeUrl && isQwenModel;
    }

    /**
     * 判断是否是已知支持 enable_thinking 的推理模型。
     * 包括 Qwen 推理系列、GLM 推理系列、DeepSeek 推理系列等。
     * 这些模型在 DashScope endpoint 上均支持 enable_thinking 参数。
     */
    public boolean isQwenReasoningModel() {
        if (!isDashScopeEndpoint()) {
            return false;
        }
        if (modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase();
        // DashScope 上已知支持 enable_thinking 的推理模型
        // Qwen 推理系列（qwen3 / qwen3.5 / qwen3.6 / qwen-max / qwq）
        // codex review #3 P0 fix: 必须与 supportsReasoningBudget 的 token 列表同步,
        // 否则 supportsReasoningBudget 内的短路检查会让 budget 永不发出。
        return lower.contains("qwen3")        // covers qwen3, qwen3.5, qwen3.6, qwen3-plus, etc.
            || lower.contains("qwen-max")
            || lower.contains("qwq")
            || lower.contains("qwen-qwq")
            // GLM 推理系列(同步 supportsReasoningBudget 全部 GLM 型号)
            || lower.contains("glm-5")
            || lower.contains("glm-4.6")
            // DeepSeek 推理系列。用 deepseek-v4 而不是 deepseek-v4-pro：《DeepSeek API》文档的
            // “模型列表与计费”把 deepseek-v4-pro / v4-flash / v4-flash-0731 / v4-flash-us 并列为
            // 混合思考模型（均由 enable_thinking 控制），只写 -pro 会让 flash 系列整个
            // thinking 参数块被跳过，拿不到 enable_thinking。
            || lower.contains("deepseek-v4")
            || lower.contains("deepseek-r1");
    }

    /**
     * codex review #3 P0 fix —— DashScope 上的 cache_control 兼容判定。
     *
     * <p>原 {@link #isQwenDashScope()} 要求 modelName 以 "qwen" 开头,导致 DashScope 上的
     * GLM / DeepSeek 推理模型(glm-5 / glm-4.6 / deepseek-v4-pro / deepseek-r1)无法拿到
     * inline cache_control 标记,损失约 90% 的 prompt prefix 复用成本。
     *
     * <p>DashScope 的 OpenAI-compatible API 对所有部署模型一致支持 {@code cache_control: ephemeral},
     * 因此 cache control 可以放宽到整个 DashScope endpoint。低于 the explicit-cache token threshold
     * 的 cache 块仍会由 breakpoint policy 主动降级为普通 system message,不会因放宽 gate 触发 provider 错误。
     */
    public boolean supportsCacheControl() {
        return isDashScopeEndpoint();
    }

    /**
     * 判断是否是「恒思考」模型 —— 思考模式无法关闭，{@code enable_thinking=false} 会被拒。
     *
     * <p>与 {@link #isQwenReasoningModel()} 的分工：后者回答「模型<b>支持</b> thinking 参数吗」，
     * 本方法回答「模型的 thinking <b>能关吗</b>」。历史上只建模了前者，四车道因而建立在
     * 「thinking 随时可关」的隐含假设上，对恒思考模型会发出必然被拒的
     * {@code enable_thinking=false}。
     *
     * <p>官方错误码：{@code The value of the enable_thinking parameter is restricted to True.}
     * —— 部分模型（如 qwen3-235b-a22b-thinking-2507）不可将 enable_thinking 设为 false。
     * 来源：https://help.aliyun.com/zh/model-studio/error-code
     *
     * <p>命中范围：
     * <ul>
     *   <li>{@code *-thinking-*} 命名的思考专用版本（qwen3-235b-a22b-thinking-2507 等）</li>
     *   <li>QwQ 系列（qwq / qwq-plus / qwen-qwq，推理专用模型，无非思考模式）</li>
     * </ul>
     * DeepSeek-R1 未纳入：官方文档未明示其拒绝 enable_thinking=false，不做无依据推断。
     */
    public boolean isThinkingAlwaysOnModel() {
        if (!isDashScopeEndpoint() || modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return lower.contains("-thinking-")
            || lower.endsWith("-thinking")
            || lower.contains("qwq");
    }

    /**
     * 判断是否支持 thinking_budget 参数（DashScope chat/completions 协议，精确 token 预算）。
     * <ul>
     *   <li>Qwen3 系列（qwen3 / qwen3.5 / qwen3.6 / qwen3.7 / qwen3.8，统一由 qwen3 前缀覆盖）</li>
     *   <li>GLM-5 / GLM-4.6 系列（DashScope 直供，参数语义同 Qwen3）</li>
     *   <li>DeepSeek-V4 系列 —— 实测认定（2026-09-02，探针
     *       {@code .ai/probe-deepseek-v4-budget.py}）：发 {@code thinking_budget=512} 后
     *       reasoning_tokens 精确为 512，确认是生效的硬上限。与《DeepSeek API》文档
     *       “deepseek-v4 系列 max_tokens 与 thinking_budget 共用上限 393,216”一致。</li>
     * </ul>
     */
    public boolean supportsReasoningBudget() {
        if (!isQwenReasoningModel()) {
            return false;
        }
        if (modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return lower.contains("qwen3")
            || lower.contains("glm-5")
            || lower.contains("glm-4.6")
            || lower.contains("deepseek-v4");
    }

    /**
     * 判断是否支持 reasoning_effort 参数（DashScope chat/completions 协议）。
     * 仅 DeepSeek-V4 系列（阿里云直供）支持。取值映射：DEEP → "max"，其它 → "high"。
     *
     * <p>与 {@link #supportsReasoningBudget()} 并非二选一：实测（2026-09-02）两个字段语义正交
     * 且可同时下发 —— budget 管硬上限，effort 管强度倾向（基线 1396 → max 档 2771）。
     * 详见 {@code OpenAICompatibleClient.applyModelSpecificBudgetParam} 的 Javadoc。
     */
    public boolean supportsReasoningEffort() {
        if (!isDashScopeEndpoint() || modelName == null) {
            return false;
        }
        String lower = modelName.toLowerCase();
        return lower.contains("deepseek-v4");
    }
}
