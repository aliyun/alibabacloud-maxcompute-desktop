package com.aliyun.odps.agentic.model;

/**
 * 运行循环中的待处理任务类型。
 * 运行循环根据任务类型分发处理：子任务、上下文压缩、上下文溢出。
 */
public enum TaskType {
    /** 子任务 */
    SUBTASK,
    /** 上下文压缩 */
    COMPACTION,
    /** 上下文溢出 */
    OVERFLOW
}
