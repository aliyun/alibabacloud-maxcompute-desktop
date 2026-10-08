package com.aliyun.odps.agentic.config;

/**
 * 推理执行模式枚举
 * 表示推理任务的执行方式
 */
public enum ReasoningExecutionMode {
    /**
     * 最小模式 - 普通结构化执行，不注入重推理框架
     * 适用于不支持推理的模型或 LIGHT 深度
     */
    MINIMAL,

    /**
     * 结构化模式 - 注入轻/中量结构化分析提示
     * 适用于 MEDIUM 深度或不支持原生推理的 DEEP 任务
     */
    STRUCTURED,

    /**
     * 原生辅助模式 - 未来可接原生推理模型参数
     * 适用于支持原生推理的模型执行 DEEP 任务
     */
    NATIVE_ASSISTED
}