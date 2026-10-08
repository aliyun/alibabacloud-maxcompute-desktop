package com.aliyun.odps.agentic.config;
import com.aliyun.odps.agentic.config.EffectiveReasoningConfig;
import com.aliyun.odps.agentic.config.ReasoningExecutionMode;
import com.aliyun.odps.agentic.config.ReasoningDepth;
import com.aliyun.odps.agentic.config.ModelReasoningCapability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 推理配置解析器
 *
 * 根据用户配置、任务类型和模型能力，计算最终生效的推理深度和执行模式。
 *
 * 核心职责：
 * 1. 解析用户推理策略配置
 * 2. 应用任务保护规则
 * 3. 根据模型能力降级
 * 4. 生成 EffectiveReasoningConfig
 */
public class ReasoningConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(ReasoningConfigResolver.class);

    private final AgentAdvancedSettings advancedConfig;
    private final ModelCapabilityRegistry capabilityRegistry;
    private final java.util.function.Supplier<String> modelName;

    public ReasoningConfigResolver(
            AgentAdvancedSettings advancedConfig,
            ModelCapabilityRegistry capabilityRegistry,
            java.util.function.Supplier<String> modelName) {
        this.advancedConfig = advancedConfig;
        this.capabilityRegistry = capabilityRegistry;
        this.modelName=modelName;
    }

    /**
     * 解析并计算最终推理配置
     *
     * @param autoDepth 自动推断的深度（来自任务路由）
     * @param modification whether the host classifies this task as a mutation
     * @return 有效推理配置
     */
    public EffectiveReasoningConfig resolve(ReasoningDepth autoDepth, boolean modification) {
        String reasoningProfile = advancedConfig.getReasoningProfile();
        String fixedReasoningDepth = advancedConfig.getFixedReasoningDepth();
        String capabilityMode = advancedConfig.getReasoningCapabilityMode();

        // 1. 解析模型能力
        ModelReasoningCapability capability = parseCapability(capabilityMode);

        // 2. 根据用户策略计算期望深度
        ReasoningDepth desiredDepth = calculateDesiredDepth(autoDepth, reasoningProfile, fixedReasoningDepth);

        // 3. 应用任务保护规则（fixed 模式直接跳过，完全尊重用户配置）
        boolean isFixed = "fixed".equals(reasoningProfile);
        ReasoningDepth protectedDepth = isFixed
            ? desiredDepth
            : applyTaskProtectionRules(desiredDepth, modification);

        if (isFixed && desiredDepth != protectedDepth) {
            log.debug("[ReasoningConfigResolver] fixed mode: task protection rules bypassed, using user-specified depth={}", desiredDepth);
        }

        // 4. 应用模型能力降级
        ReasoningDepth effectiveDepth = protectedDepth;
        boolean downgraded = false;
        String downgradeReason = null;

        if (capability != ModelReasoningCapability.SUPPORTED && protectedDepth == ReasoningDepth.DEEP) {
            // 不支持原生推理的模型，DEEP 任务不强制降级深度，但执行模式会调整
            downgraded = true;
            downgradeReason = "model_not_reasoning_capable";
            log.debug("[ReasoningConfigResolver] Model does not support native reasoning, using STRUCTURED mode for DEEP task");
        }

        // 5. 计算执行模式
        ReasoningExecutionMode executionMode = determineExecutionMode(effectiveDepth, capability);

        EffectiveReasoningConfig config = EffectiveReasoningConfig.builder()
            .autoDepth(autoDepth)
            .effectiveDepth(effectiveDepth)
            .reasoningProfile(reasoningProfile)
            .capability(capability)
            .executionMode(executionMode)
            .downgraded(downgraded)
            .downgradeReason(downgradeReason)
            .build();

        log.debug("[ReasoningConfigResolver] Resolved: autoDepth={}, desiredDepth={}, effectiveDepth={}, profile={}, isFixed={}, capability={}, executionMode={}, downgraded={}",
            autoDepth, desiredDepth, effectiveDepth, reasoningProfile, isFixed, capability, executionMode, downgraded);

        return config;
    }

    /**
     * 解析模型能力配置
     */
    private ModelReasoningCapability parseCapability(String capabilityMode) {
        if (capabilityMode == null || "auto".equals(capabilityMode)) {
            // auto 模式：从模型名推断
            String modelName = this.modelName.get();
            ModelReasoningCapability detected = capabilityRegistry.detect(modelName);
            log.debug("[ReasoningConfigResolver] Auto-detected capability for model '{}': {}", modelName, detected);
            return detected;
        }
        return switch (capabilityMode) {
            case "supported" -> ModelReasoningCapability.SUPPORTED;
            case "unsupported" -> ModelReasoningCapability.UNSUPPORTED;
            default -> ModelReasoningCapability.UNKNOWN;
        };
    }

    /**
     * 根据用户策略计算期望深度
     */
    private ReasoningDepth calculateDesiredDepth(ReasoningDepth autoDepth, String profile, String fixedDepth) {
        if ("fixed".equals(profile) && fixedDepth != null) {
            // 固定深度模式
            try {
                return ReasoningDepth.valueOf(fixedDepth);
            } catch (IllegalArgumentException e) {
                log.warn("[ReasoningConfigResolver] Invalid fixedReasoningDepth: {}, falling back to auto", fixedDepth);
                return autoDepth;
            }
        }

        return switch (profile) {
            case "fast" -> downgradeDepth(autoDepth);
            case "deep" -> upgradeDepth(autoDepth);
            case "balanced", "auto" -> autoDepth;
            default -> autoDepth;
        };
    }

    /**
     * 降一档深度
     */
    private ReasoningDepth downgradeDepth(ReasoningDepth depth) {
        return switch (depth) {
            case DEEP -> ReasoningDepth.MEDIUM;
            case MEDIUM -> ReasoningDepth.LIGHT;
            case LIGHT, NONE -> ReasoningDepth.NONE;
        };
    }

    /**
     * 升一档深度（仅对分析型任务有效）
     */
    private ReasoningDepth upgradeDepth(ReasoningDepth depth) {
        return switch (depth) {
            case NONE -> ReasoningDepth.LIGHT;
            case LIGHT -> ReasoningDepth.MEDIUM;
            case MEDIUM, DEEP -> ReasoningDepth.DEEP;
        };
    }

    /**
     * 应用任务保护规则（仅在非 fixed 模式下生效）
     *
     * 上限设计原则：
     * - 纯查询任务（QUERY）：最高 MEDIUM（查询也可能涉及复杂 SQL）
     * - 纯可视化（VISUALIZE）：不限，DEEP 生效（图表配置也可能需要深度思考）
     * - follow-up chart：不限，DEEP 生效
     * - 修改类任务（MODIFY）：最高 MEDIUM（操作明确，不需要进一步推理）
     * - 分析/诊断/建议等：不限，DEEP 生效
     * - 生成 SQL：不限，DEEP 生效
     */
    private ReasoningDepth applyTaskProtectionRules(ReasoningDepth desiredDepth, boolean modification) {
        // 修改类任务：最高 MEDIUM
        if (modification) {
            if (desiredDepth == ReasoningDepth.DEEP) {
                log.debug("[ReasoningConfigResolver] MODIFY task, capping at MEDIUM");
                return ReasoningDepth.MEDIUM;
            }
        }

        return desiredDepth;
    }

    /**
     * 根据深度和能力确定执行模式
     */
    private ReasoningExecutionMode determineExecutionMode(ReasoningDepth depth, ModelReasoningCapability capability) {
        if (depth == null || depth == ReasoningDepth.NONE) {
            return ReasoningExecutionMode.MINIMAL;
        }

        if (depth == ReasoningDepth.LIGHT) {
            return ReasoningExecutionMode.MINIMAL;
        }

        // MEDIUM 或 DEEP
        if (capability == ModelReasoningCapability.SUPPORTED && depth == ReasoningDepth.DEEP) {
            // 未来可接入原生推理参数
            return ReasoningExecutionMode.NATIVE_ASSISTED;
        }

        // 不支持原生推理或 MEDIUM 深度，使用结构化模式
        return ReasoningExecutionMode.STRUCTURED;
    }
}