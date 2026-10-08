package com.aliyun.odps.agentic.memory.context;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Agent Memory 状态
 * 记录合并状态和统计信息
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentMemoryState {

    private String lastConsolidationTime;  // 最后合并时间 (ISO 8601)
    private int totalSessions;              // 总会话数
    private int sessionsSinceLastConsolidation;  // 自上次合并以来的会话数
    private int totalEvents;                // 总事件数
    private int consolidationCount;         // 合并次数

    public AgentMemoryState() {
        this.totalSessions = 0;
        this.totalEvents = 0;
        this.consolidationCount = 0;
    }

    // ==================== 业务方法 ====================

    /**
     * 记录一次合并
     */
    public void recordConsolidation(int eventsProcessed) {
        this.lastConsolidationTime = java.time.Instant.now().toString();
        this.totalEvents += eventsProcessed;
        this.consolidationCount++;
        this.sessionsSinceLastConsolidation = 0; // reset after consolidation
    }

    /**
     * 增加会话计数
     */
    public void incrementSessionCount() {
        this.totalSessions++;
        this.sessionsSinceLastConsolidation++;
    }

    /**
     * 获取状态摘要
     * 包含下次合并条件提示
     */
    @JsonIgnore
    public String getSummary() {
        int effectiveSessions = getEffectiveSessionsSinceConsolidation();
        return String.format(
            "合并次数: %d | 总会话: %d | 下次合并需 %d/5 会话 | 总事件: %d | 最后合并: %s",
            consolidationCount,
            totalSessions,
            effectiveSessions,
            totalEvents,
            lastConsolidationTime != null ? lastConsolidationTime : "从未"
        );
    }

    /**
     * 获取有效的"自上次合并以来会话数"。
     * 对旧 state.json（缺少 sessionsSinceLastConsolidation）做迁移兜底：
     * 如果该字段为 0 但 totalSessions > 0 且从未合并过，回退到 totalSessions。
     */
    @JsonIgnore
    public int getEffectiveSessionsSinceConsolidation() {
        if (sessionsSinceLastConsolidation > 0) {
            return sessionsSinceLastConsolidation;
        }
        // Migration fallback: old state.json had no sessionsSinceLastConsolidation
        // If never consolidated and totalSessions > 0, use totalSessions as baseline
        if (lastConsolidationTime == null && totalSessions > 0) {
            return totalSessions;
        }
        return 0;
    }

    // ==================== Getters/Setters ====================

    public String getLastConsolidationTime() {
        return lastConsolidationTime;
    }

    public void setLastConsolidationTime(String lastConsolidationTime) {
        this.lastConsolidationTime = lastConsolidationTime;
    }

    public int getTotalSessions() {
        return totalSessions;
    }

    public void setTotalSessions(int totalSessions) {
        this.totalSessions = totalSessions;
    }

    public int getSessionsSinceLastConsolidation() {
        return sessionsSinceLastConsolidation;
    }

    public void setSessionsSinceLastConsolidation(int sessionsSinceLastConsolidation) {
        this.sessionsSinceLastConsolidation = sessionsSinceLastConsolidation;
    }

    public int getTotalEvents() {
        return totalEvents;
    }

    public void setTotalEvents(int totalEvents) {
        this.totalEvents = totalEvents;
    }

    public int getConsolidationCount() {
        return consolidationCount;
    }

    public void setConsolidationCount(int consolidationCount) {
        this.consolidationCount = consolidationCount;
    }
}