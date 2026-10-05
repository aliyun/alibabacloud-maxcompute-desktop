package com.aliyun.odps.agentic.llm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 提供者特定模型选项计算工具。
 *
 * <p>该类根据提供者、模型和配置生成请求所需的扩展参数，
 * 例如推理强度、思考预算与默认温度等。
 */
public final class ProviderOptions {

    private ProviderOptions() {}

    /**
     * 计算提供者命名空间下的选项映射。
     *
     * @param providerId 提供者标识
     * @param modelId    模型标识
     * @param config     模型配置
     * @return 提供者特定选项映射
     */
    public static Map<String, Object> compute(String providerId, String modelId, ModelConfig config) {
        if (providerId == null) return Map.of();

        String normalizedProvider = providerId.toLowerCase(Locale.ROOT);
        Map<String, Object> scoped = new LinkedHashMap<>();

        switch (normalizedProvider) {
            case "anthropic" -> populateAnthropicOptions(scoped, modelId, config);
            case "openai", "xai" -> populateOpenAIOptions(scoped, modelId, config);
            case "google", "gemini", "google-vertex" -> populateGoogleOptions(scoped, modelId, config);
            default -> populateOpenAICompatibleOptions(scoped, normalizedProvider, modelId, config);
        }

        if (scoped.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> namespaced = new LinkedHashMap<>();
        switch (normalizedProvider) {
            case "anthropic" -> namespaced.put("anthropic", scoped);
            case "openai" -> namespaced.put("openai", scoped);
            case "google", "gemini", "google-vertex" -> namespaced.put("google", scoped);
            default -> {
                namespaced.put("openaiCompatible", scoped);
                if (isAlibabaProvider(normalizedProvider)) {
                    namespaced.put("alibaba", new LinkedHashMap<>(scoped));
                }
            }
        }
        return namespaced;
    }

    /**
     * 计算模型请求应使用的温度值。
     *
     * @param providerId 提供者标识
     * @param modelId    模型标识
     * @param agentTemp  显式指定的温度
     * @return 温度值；若应交由提供者默认处理则返回 {@code null}
     */
    public static Double temperature(String providerId, String modelId, Double agentTemp) {
        if (agentTemp != null) {
            return agentTemp;
        }
        return defaultTemperature(modelId);
    }

    /**
     * 判断请求中是否应显式设置温度参数。
     *
     * @param providerId 提供者标识
     * @param modelId    模型标识
     * @param agentTemp  显式指定的温度
     * @return 需要显式设置时返回 {@code true}
     */
    public static boolean shouldSetTemperature(String providerId, String modelId, Double agentTemp) {
        return temperature(providerId, modelId, agentTemp) != null;
    }

    /**
     * 计算最大输出 Token 数。
     *
     * @param providerId 提供者标识
     * @param modelId    模型标识
     * @param defaultMax 默认最大值
     * @return 最大输出 Token 数
     */
    public static int maxOutputTokens(String providerId, String modelId, int defaultMax) {
        if (isThinkingModel(modelId)) {
            return Math.max(defaultMax, Math.min(defaultMax, 32000));
        }
        return defaultMax;
    }

    private static void populateAnthropicOptions(Map<String, Object> scoped, String modelId, ModelConfig config) {
        if (!isThinkingModel(modelId)) {
            return;
        }
        int budgetTokens = config != null && config.thinkingBudget() > 0
            ? config.thinkingBudget() : 10000;
        scoped.put("thinking", Map.of(
            "type", "enabled",
            "budget_tokens", budgetTokens
        ));
    }

    private static void populateOpenAIOptions(Map<String, Object> scoped, String modelId, ModelConfig config) {
        if (!isReasoningModel(modelId)) {
            return;
        }
        String effort = config != null && config.reasoningEffort() != null
            ? config.reasoningEffort() : "medium";
        scoped.put("reasoningEffort", effort);
    }

    private static void populateGoogleOptions(Map<String, Object> scoped, String modelId, ModelConfig config) {
        if (!isThinkingModel(modelId)) {
            return;
        }
        int budgetTokens = config != null && config.thinkingBudget() > 0
            ? config.thinkingBudget() : 8192;
        scoped.put("thinkingConfig", Map.of(
            "thinkingBudget", budgetTokens
        ));
    }

    private static void populateOpenAICompatibleOptions(
            Map<String, Object> scoped, String providerId, String modelId, ModelConfig config) {
        if (isAlibabaProvider(providerId) && supportsReasoningContent(modelId)) {
            // 三态：调用方没表态就沿用历史默认（开）；显式 true / false 一律照发。
            // 只在"阿里系 + 模型确实支持思考链"这个原有范围内下发，避免把
            // enable_thinking 发给根本不认这个参数的模型而被提供者拒绝。
            Boolean explicit = config != null ? config.enableThinking() : null;
            scoped.put("enableThinking", explicit != null ? explicit : Boolean.TRUE);
        }
        if (isReasoningModel(modelId) && config != null && config.reasoningEffort() != null) {
            scoped.put("reasoningEffort", config.reasoningEffort());
        }
    }

    /**
     * 是否阿里系提供者。{@code dashscope}（灵积）与 {@code bailian}（百炼）就是这套服务
     * 对外的两个通行名字，与 {@code alibaba} 同义；只认字面 "alibaba" 会让所有按真实服务名
     * 注册提供者的宿主永远拿不到思考链开关 —— 请求侧一次都不会下发 {@code enable_thinking}，
     * 是否思考完全由服务端默认值决定，且无法关闭。
     */
    private static boolean isAlibabaProvider(String providerId) {
        if (providerId == null) return false;
        String lower = providerId.toLowerCase(Locale.ROOT);
        return lower.contains("alibaba") || lower.contains("dashscope") || lower.contains("bailian");
    }

    private static boolean supportsReasoningContent(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase(Locale.ROOT);
        return lower.contains("qwen") || lower.contains("qwq") || lower.contains("deepseek-r1")
            || lower.contains("kimi-k2.5") || lower.contains("kimi-k2") || lower.contains("reasoning");
    }

    private static Double defaultTemperature(String modelId) {
        if (modelId == null) return null;
        String lower = modelId.toLowerCase(Locale.ROOT);
        if (lower.contains("north-mini-code")) return 1.0;
        if (lower.contains("qwen")) return 0.55;
        if (lower.contains("claude")) return null;
        if (lower.contains("gemini")) return 1.0;
        if (lower.contains("glm-4.6") || lower.contains("glm-4.7") || lower.contains("minimax-m2")) {
            return 1.0;
        }
        if (lower.contains("kimi-k2")) {
            if (lower.contains("thinking") || lower.contains("k2.") || lower.contains("k2p") || lower.contains("k2-5")) {
                return 1.0;
            }
            return 0.6;
        }
        return null;
    }

    // ── 模型分类辅助方法 ──

    private static boolean isThinkingModel(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        return lower.contains("claude-4") || lower.contains("claude-3.7")
            || lower.contains("claude-3.5-sonnet") || lower.contains("gemini-2.5")
            || lower.contains("thinking");
    }

    private static boolean isReasoningModel(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        return lower.contains("o1") || lower.contains("o3") || lower.contains("o4")
            || lower.contains("reasoning");
    }

    /**
     * 模型配置对象，承载提供者特定设置。
     *
     * @param providerId       提供者标识
     * @param modelId          模型标识
     * @param thinkingBudget   思考预算 Token 数
     * @param reasoningEffort  推理强度，如 {@code low}、{@code medium}、{@code high}
     * @param temperature      温度参数
     * @param topP             Top-p 参数
     * @param maxTokens        最大输出 Token 数
     */
    public record ModelConfig(
        String providerId,
        String modelId,
        int thinkingBudget,
        String reasoningEffort,
        Double temperature,
        Double topP,
        Integer maxTokens,
        Boolean enableThinking
    ) {
        /**
         * 创建仅指定提供者和模型的配置对象。
         */
        public ModelConfig(String providerId, String modelId) {
            this(providerId, modelId, 0, null, null, null, null, null);
        }

        /**
         * 兼容不指定思考链开关的旧七参构造 —— {@code enableThinking} 留空表示
         * "调用方没表态"，由提供者默认值决定。
         */
        public ModelConfig(String providerId, String modelId, int thinkingBudget, String reasoningEffort,
                           Double temperature, Double topP, Integer maxTokens) {
            this(providerId, modelId, thinkingBudget, reasoningEffort, temperature, topP, maxTokens, null);
        }

        /**
         * 返回一个更新了思考预算的新配置对象。
         *
         * @param budget 思考预算
         * @return 新配置对象
         */
        public ModelConfig withThinkingBudget(int budget) {
            return new ModelConfig(providerId, modelId, budget, reasoningEffort,
                temperature, topP, maxTokens, enableThinking);
        }

        /**
         * 返回一个更新了推理强度的新配置对象。
         *
         * @param effort 推理强度
         * @return 新配置对象
         */
        public ModelConfig withReasoningEffort(String effort) {
            return new ModelConfig(providerId, modelId, thinkingBudget, effort,
                temperature, topP, maxTokens, enableThinking);
        }

        /**
         * 返回一个更新了思考链开关的新配置对象。
         *
         * @param enabled {@code null} 表示不表态（随提供者默认值），否则强制开/关
         * @return 新配置对象
         */
        public ModelConfig withEnableThinking(Boolean enabled) {
            return new ModelConfig(providerId, modelId, thinkingBudget, reasoningEffort,
                temperature, topP, maxTokens, enabled);
        }
    }
}
