package com.aliyun.odps.agentic.model;

/**
 * 运行循环中待处理的任务。
 *
 * @param type 任务类型
 * @param data 任务相关数据（可为 null）
 */
public record PendingTask(TaskType type, Object data) {}
