package com.aliyun.odps.agentic.memory.context;

import java.time.LocalDate;
import java.util.List;

/**
 * Agent Memory 快照
 * 某个主题在某一天的知识快照
 */
public class AgentMemorySnapshot {

    /**
     * 快照状态
     */
    public enum SnapshotStatus {
        ACTIVE,     // 活跃（当前有效）
        ARCHIVED    // 已归档
    }

    private String topicId;             // 所属主题
    private AgentMemoryTopic.MemoryType type;  // 类型
    private String name;                // 显示名称
    private String description;         // 一句话描述
    private String snapshotDate;        // 快照日期 (YYYY-MM-DD)
    private String capturedAt;          // 捕获时间 (ISO 8601)
    private List<String> sourceSessionIds;  // 来源会话ID列表
    private double confidence;          // 置信度 (0.0-1.0)
    private SnapshotStatus status;      // 状态
    private String path;                // 文件相对路径
    private String content;             // 正文内容（Markdown）

    public AgentMemorySnapshot() {
        this.confidence = 0.5;
        this.status = SnapshotStatus.ACTIVE;
    }

    // ==================== 业务方法 ====================

    /**
     * 快照是否过期（超过 N 天）
     * 语义：N 天前或更早算过期
     */
    public boolean isStale(int daysThreshold) {
        if (snapshotDate == null) {
            return true;
        }
        LocalDate snapshot = LocalDate.parse(snapshotDate);
        LocalDate threshold = LocalDate.now().minusDays(daysThreshold);
        // snapshot <= threshold 表示过期
        return !snapshot.isAfter(threshold);
    }

    /**
     * 快照是否过期（默认超过 1 天）
     */
    public boolean isStale() {
        return isStale(1);
    }

    /**
     * 获取新鲜度描述
     */
    public String getFreshnessText() {
        if (snapshotDate == null) {
            return "未知";
        }
        LocalDate snapshot = LocalDate.parse(snapshotDate);
        LocalDate today = LocalDate.now();
        long days = java.time.temporal.ChronoUnit.DAYS.between(snapshot, today);

        if (days == 0) {
            return "今天";
        } else if (days == 1) {
            return "昨天";
        } else if (days <= 7) {
            return days + " 天前";
        } else if (days <= 30) {
            return (days / 7) + " 周前";
        } else {
            return (days / 30) + " 个月前";
        }
    }

    /**
     * 生成新鲜度警告
     */
    public String getStalenessWarning() {
        if (isStale()) {
            return "⚠️ 此记忆快照日期：" + snapshotDate + "，请验证后使用。";
        }
        return null;
    }

    /**
     * 生成文件名（日期前缀 + slug）
     */
    public String generateFileName() {
        String slug = AgentMemoryEvent.slugify(name);
        return snapshotDate + "__" + slug + ".md";
    }

    // ==================== Getters/Setters ====================

    public String getTopicId() {
        return topicId;
    }

    public void setTopicId(String topicId) {
        this.topicId = topicId;
    }

    public AgentMemoryTopic.MemoryType getType() {
        return type;
    }

    public void setType(AgentMemoryTopic.MemoryType type) {
        this.type = type;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSnapshotDate() {
        return snapshotDate;
    }

    public void setSnapshotDate(String snapshotDate) {
        this.snapshotDate = snapshotDate;
    }

    public String getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(String capturedAt) {
        this.capturedAt = capturedAt;
    }

    public List<String> getSourceSessionIds() {
        return sourceSessionIds;
    }

    public void setSourceSessionIds(List<String> sourceSessionIds) {
        this.sourceSessionIds = sourceSessionIds;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public SnapshotStatus getStatus() {
        return status;
    }

    public void setStatus(SnapshotStatus status) {
        this.status = status;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}