package com.aliyun.odps.agentic.memory.context;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Agent Memory 主题
 * 一个主题可以有多个日期的快照
 */
public class AgentMemoryTopic {

    /**
     * 记忆类型
     */
    public enum MemoryType {
        TABLE,           // 表知识
        QUERY_PATTERN,   // 查询模式
        USER,            // 用户偏好
        FEEDBACK         // 反馈记忆
    }

    private String topicId;             // 稳定主题标识，如 table:information_schema.tables
    private MemoryType type;            // 类型
    private String name;                // 显示名称
    private String description;         // 一句话描述
    private String latestSnapshotDate;  // 最新快照日期 (YYYY-MM-DD)
    private int snapshotCount;          // 快照数量
    private List<AgentMemorySnapshot> snapshots;  // 快照列表

    public AgentMemoryTopic() {
        this.snapshots = new ArrayList<>();
    }

    public AgentMemoryTopic(String topicId, MemoryType type, String name, String description) {
        this.topicId = topicId;
        this.type = type;
        this.name = name;
        this.description = description;
        this.snapshots = new ArrayList<>();
    }

    // ==================== 业务方法 ====================

    /**
     * 获取最新快照
     */
    public AgentMemorySnapshot getLatestSnapshot() {
        if (snapshots == null || snapshots.isEmpty()) {
            return null;
        }
        // 按日期降序排序后取第一个
        return snapshots.stream()
            .sorted((a, b) -> b.getSnapshotDate().compareTo(a.getSnapshotDate()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 添加快照（自动更新 latestSnapshotDate 和 snapshotCount）
     */
    public void addSnapshot(AgentMemorySnapshot snapshot) {
        if (snapshots == null) {
            snapshots = new ArrayList<>();
        }
        snapshots.add(snapshot);
        snapshotCount = snapshots.size();
        // 更新最新日期
        if (latestSnapshotDate == null || snapshot.getSnapshotDate().compareTo(latestSnapshotDate) > 0) {
            latestSnapshotDate = snapshot.getSnapshotDate();
        }
    }

    /**
     * 从 topicId 解析类型
     */
    public static MemoryType parseTypeFromTopicId(String topicId) {
        if (topicId == null || !topicId.contains(":")) {
            return null;
        }
        String typePart = topicId.substring(0, topicId.indexOf(":"));
        return switch (typePart) {
            case "table" -> MemoryType.TABLE;
            case "query-pattern" -> MemoryType.QUERY_PATTERN;
            case "user" -> MemoryType.USER;
            case "feedback" -> MemoryType.FEEDBACK;
            default -> null;
        };
    }

    /**
     * 主题是否过期（最新快照超过 N 天）
     */
    public boolean isStale(int daysThreshold) {
        if (latestSnapshotDate == null) {
            return true;
        }
        LocalDate latest = LocalDate.parse(latestSnapshotDate);
        return latest.isBefore(LocalDate.now().minusDays(daysThreshold));
    }

    public boolean isStale() {
        return isStale(1);  // 默认超过 1 天算过期
    }

    // ==================== Getters/Setters ====================

    public String getTopicId() {
        return topicId;
    }

    public void setTopicId(String topicId) {
        this.topicId = topicId;
    }

    public MemoryType getType() {
        return type;
    }

    public void setType(MemoryType type) {
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

    public String getLatestSnapshotDate() {
        return latestSnapshotDate;
    }

    public void setLatestSnapshotDate(String latestSnapshotDate) {
        this.latestSnapshotDate = latestSnapshotDate;
    }

    public int getSnapshotCount() {
        return snapshotCount;
    }

    public void setSnapshotCount(int snapshotCount) {
        this.snapshotCount = snapshotCount;
    }

    public List<AgentMemorySnapshot> getSnapshots() {
        return snapshots;
    }

    public void setSnapshots(List<AgentMemorySnapshot> snapshots) {
        this.snapshots = snapshots;
        this.snapshotCount = snapshots != null ? snapshots.size() : 0;
    }
}