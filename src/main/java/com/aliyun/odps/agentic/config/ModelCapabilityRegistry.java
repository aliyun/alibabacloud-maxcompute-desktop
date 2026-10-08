package com.aliyun.odps.agentic.config;
import com.aliyun.odps.agentic.config.ModelReasoningCapability;


import java.util.Set;

/**
 * 模型能力注册表
 *
 * 根据模型名称推断其推理能力，用于 auto 模式下的自动检测。
 */
public class ModelCapabilityRegistry {

    /**
     * 支持原生推理能力的模型模式列表
     * 使用 contains 匹配，支持模型名的变体
     */
    private static final Set<String> REASONING_CAPABLE_PATTERNS = Set.of(
        // OpenAI o 系列
        "o1", "o1-mini", "o1-preview", "o3", "o3-mini", "gpt-5",
        // Claude 4 系列（支持 extended thinking）
        "claude-opus-4", "claude-sonnet-4",
        // DeepSeek R 系列
        "deepseek-r1", "deepseek-reasoner", "deepseek-v4", "deepseek-v4-pro", "deepseek-prover",
        // Qwen 推理模型 - 扩展覆盖 qwen3 系列
        "qwen-qwq", "qwq", "qwen3.5-plus", "qwen3.6-plus", "qwen3.",
        // GLM
        "glm-4.7", "glm-5"
    );

    /**
     * 检测模型的推理能力
     *
     * @param modelName 模型名称（如 "qwen3-max-2026-01-23", "gpt-4o", "o1-mini"）
     * @return 模型推理能力枚举
     */
    public ModelReasoningCapability detect(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return ModelReasoningCapability.UNKNOWN;
        }

        String normalized = modelName.toLowerCase().trim();

        // 检查是否匹配已知支持推理的模型
        for (String pattern : REASONING_CAPABLE_PATTERNS) {
            if (normalized.contains(pattern.toLowerCase())) {
                return ModelReasoningCapability.SUPPORTED;
            }
        }

        // 默认认为不支持原生推理
        return ModelReasoningCapability.UNSUPPORTED;
    }

    /**
     * 检查模型名是否匹配已知模式
     *
     * @param modelName 模型名称
     * @return 是否支持推理
     */
    public boolean isReasoningCapable(String modelName) {
        return detect(modelName) == ModelReasoningCapability.SUPPORTED;
    }
}
