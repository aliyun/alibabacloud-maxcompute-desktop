package com.aliyun.odps.agentic.config;

/**
 * 模型推理能力枚举
 * 表示当前模型是否支持原生推理能力
 */
public enum ModelReasoningCapability {
    /**
     * 支持原生推理 - 模型支持 extended thinking / reasoning tokens
     */
    SUPPORTED,

    /**
     * 不支持原生推理 - 普通 chat 模型
     */
    UNSUPPORTED,

    /**
     * 未知 - 保守处理，视为不支持
     */
    UNKNOWN
}