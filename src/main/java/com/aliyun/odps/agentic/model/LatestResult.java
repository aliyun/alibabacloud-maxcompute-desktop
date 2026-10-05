package com.aliyun.odps.agentic.model;

/**
 * 运行循环中最新消息的提取结果。
 * 运行循环从消息历史中提取最新的用户消息、助手消息和待处理任务。
 *
 * @param lastUser      最近一条用户消息
 * @param lastAssistant 最近一条助手消息（可为 null）
 * @param pendingTasks  助手工具调用产生的待处理任务列表
 */
public record LatestResult(
    Message lastUser,
    Message lastAssistant,
    java.util.List<PendingTask> pendingTasks
) {
    /**
     * 弹出并返回下一个待处理任务，无任务时返回 null。
     *
     * @return 下一个待处理任务或 null
     */
    public PendingTask popTask() {
        if (pendingTasks == null || pendingTasks.isEmpty()) return null;
        return pendingTasks.remove(pendingTasks.size() - 1);
    }

    /**
     * 检查是否存在待执行的工具调用。
     *
     * @return 存在待执行工具调用时返回 true
     */
    public boolean hasPendingToolCalls() {
        if (lastAssistant == null) return false;
        return lastAssistant.hasPendingToolCalls();
    }
}
