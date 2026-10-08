package com.aliyun.odps.agentic.config;

import com.aliyun.odps.agentic.config.ModelReasoningCapability;
import com.aliyun.odps.agentic.config.ReasoningDepth;
import com.aliyun.odps.agentic.config.ReasoningExecutionMode;

/**
 * 有效推理配置
 *
 * 封装经过用户策略、任务保护规则、模型能力综合计算后的最终推理配置。
 */
public class EffectiveReasoningConfig {

    /** 自动推断的深度（任务路由结果） */
    private final ReasoningDepth autoDepth;

    /** 最终生效的深度（经过策略调整和降级后） */
    private final ReasoningDepth effectiveDepth;

    /** 用户选择的推理策略: auto/fast/balanced/deep/fixed */
    private final String reasoningProfile;

    /** 模型推理能力 */
    private final ModelReasoningCapability capability;

    /** 执行模式 */
    private final ReasoningExecutionMode executionMode;

    /** 是否发生了降级 */
    private final boolean downgraded;

    /** 降级原因 */
    private final String downgradeReason;

    private EffectiveReasoningConfig(Builder builder) {
        this.autoDepth = builder.autoDepth;
        this.effectiveDepth = builder.effectiveDepth;
        this.reasoningProfile = builder.reasoningProfile;
        this.capability = builder.capability;
        this.executionMode = builder.executionMode;
        this.downgraded = builder.downgraded;
        this.downgradeReason = builder.downgradeReason;
    }

    // ==================== Getters ====================

    public ReasoningDepth getAutoDepth() { return autoDepth; }
    public ReasoningDepth getEffectiveDepth() { return effectiveDepth; }
    public String getReasoningProfile() { return reasoningProfile; }
    public ModelReasoningCapability getCapability() { return capability; }
    public ReasoningExecutionMode getExecutionMode() { return executionMode; }
    public boolean isDowngraded() { return downgraded; }
    public String getDowngradeReason() { return downgradeReason; }

    // ==================== Builder ====================

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private ReasoningDepth autoDepth;
        private ReasoningDepth effectiveDepth;
        private String reasoningProfile;
        private ModelReasoningCapability capability;
        private ReasoningExecutionMode executionMode;
        private boolean downgraded;
        private String downgradeReason;

        public Builder autoDepth(ReasoningDepth autoDepth) {
            this.autoDepth = autoDepth;
            return this;
        }

        public Builder effectiveDepth(ReasoningDepth effectiveDepth) {
            this.effectiveDepth = effectiveDepth;
            return this;
        }

        public Builder reasoningProfile(String reasoningProfile) {
            this.reasoningProfile = reasoningProfile;
            return this;
        }

        public Builder capability(ModelReasoningCapability capability) {
            this.capability = capability;
            return this;
        }

        public Builder executionMode(ReasoningExecutionMode executionMode) {
            this.executionMode = executionMode;
            return this;
        }

        public Builder downgraded(boolean downgraded) {
            this.downgraded = downgraded;
            return this;
        }

        public Builder downgradeReason(String downgradeReason) {
            this.downgradeReason = downgradeReason;
            return this;
        }

        public EffectiveReasoningConfig build() {
            return new EffectiveReasoningConfig(this);
        }
    }

    @Override
    public String toString() {
        return "EffectiveReasoningConfig{" +
            "autoDepth=" + autoDepth +
            ", effectiveDepth=" + effectiveDepth +
            ", reasoningProfile='" + reasoningProfile + '\'' +
            ", capability=" + capability +
            ", executionMode=" + executionMode +
            ", downgraded=" + downgraded +
            (downgradeReason != null ? ", downgradeReason='" + downgradeReason + '\'' : "") +
            '}';
    }
}