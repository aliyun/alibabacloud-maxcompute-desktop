package com.aliyun.odps.agentic.config;

/**
 * 推理深度枚举
 * 表示任务需要的推理复杂度
 */
public enum ReasoningDepth {
    /**
     * 无需推理 - 简单查询，直接执行即可
     */
    NONE,

    /**
     * 轻度推理 - 需要基本的数据理解和解释
     */
    LIGHT,

    /**
     * 中度推理 - 需要多角度分析和综合判断
     */
    MEDIUM,

    /**
     * 深度推理 - 需要假设驱动的分析流程（假设/证据/结论标签由 ReasoningSignalBackfillService 解析，
     * 完成度由 CompletionGuard.validateClaimCoverage 校验）
     */
    DEEP;

    /**
     * 获取建议的最大迭代次数基数
     */
    public int getBaseIterations() {
        return switch (this) {
            case NONE -> 8;
            case LIGHT -> 12;
            case MEDIUM -> 15;
            case DEEP -> 20;
        };
    }
}