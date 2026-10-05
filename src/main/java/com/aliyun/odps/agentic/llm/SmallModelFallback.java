package com.aliyun.odps.agentic.llm;

import java.util.Map;

/**
 * 小模型回退逻辑。
 *
 * <p>在上下文压缩、标题生成、工具结果摘要等场景中，
 * 优先使用更小或更经济的模型以节约 Token。
 */
public final class SmallModelFallback {

    private SmallModelFallback() {}

    /**
     * 内置的大模型到小模型的映射关系。
     */
    private static final Map<String, String> SMALL_MODEL_MAP = Map.of(
        "claude-4-sonnet", "claude-4-haiku",
        "claude-3.7-sonnet", "claude-3.5-haiku",
        "claude-3.5-sonnet", "claude-3.5-haiku",
        "gpt-4o", "gpt-4o-mini",
        "gpt-4.1", "gpt-4o-mini",
        "gemini-2.5-pro", "gemini-2.5-flash",
        "deepseek-chat", "deepseek-chat"
    );

    /**
     * 为给定模型查找对应的小模型。
     *
     * @param primaryModelId      主模型标识
     * @param configuredSmallModel 用户手动配置的小模型（优先级最高）
     * @return 小模型标识；找不到时返回原模型
     */
    public static String findSmallModel(String primaryModelId, String configuredSmallModel) {
        if (configuredSmallModel != null && !configuredSmallModel.isBlank()) {
            return configuredSmallModel;
        }

        String small = SMALL_MODEL_MAP.get(primaryModelId);
        if (small != null) return small;

        for (var entry : SMALL_MODEL_MAP.entrySet()) {
            if (primaryModelId.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }

        return primaryModelId;
    }

    /**
     * 判断模型是否属于"小模型"。
     *
     * @param modelId 模型标识
     * @return 属于小模型时返回 {@code true}
     */
    public static boolean isSmallModel(String modelId) {
        if (modelId == null) return false;
        String lower = modelId.toLowerCase();
        return lower.contains("haiku") || lower.contains("mini")
            || lower.contains("flash") || lower.contains("small");
    }

    /** 上下文压缩操作的最大输出 Token 数。 */
    public static final int COMPACTION_MAX_TOKENS = 4096;
    /** 标题生成操作的最大输出 Token 数。 */
    public static final int TITLE_MAX_TOKENS = 128;
    /** 摘要生成操作的最大输出 Token 数。 */
    public static final int SUMMARY_MAX_TOKENS = 2048;
}
