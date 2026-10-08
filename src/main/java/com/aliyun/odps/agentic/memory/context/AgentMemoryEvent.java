package com.aliyun.odps.agentic.memory.context;

import java.util.Map;

/**
 * Agent Memory 事件
 * 会话结束时写入 inbox，后台合并时消费
 */
public class AgentMemoryEvent {

    /**
     * 事件类型
     */
    public enum EventType {
        TABLE_DISCOVERED,      // 发现新表
        TABLE_SCHEMA_CHANGED,  // 表结构变更
        QUERY_PATTERN_LEARNED, // 学习查询模式
        USER_FEEDBACK,         // 用户反馈（纠正/确认）
        USER_PREFERENCE        // 用户偏好
    }

    private EventType event;
    private String topicId;           // 稳定主题标识，如 table:information_schema.tables
    private Map<String, Object> data; // 事件数据
    private String sessionId;         // 来源会话
    private long timestamp;           // 时间戳

    public AgentMemoryEvent() {
        this.timestamp = System.currentTimeMillis();
    }

    public AgentMemoryEvent(EventType event, String topicId, Map<String, Object> data, String sessionId) {
        this.event = event;
        this.topicId = topicId;
        this.data = data;
        this.sessionId = sessionId;
        this.timestamp = System.currentTimeMillis();
    }

    // ==================== 静态工厂方法 ====================

    public static AgentMemoryEvent tableDiscovered(String project, String table,
                                                    Map<String, Object> schema, String sessionId) {
        String topicId = "table:" + project + "." + table;
        Map<String, Object> data = Map.of(
            "project", project,
            "table", table,
            "schema", schema
        );
        return new AgentMemoryEvent(EventType.TABLE_DISCOVERED, topicId, data, sessionId);
    }

    public static AgentMemoryEvent queryPatternLearned(String patternName, String sqlTemplate,
                                                        String description, String sessionId) {
        String topicId = "query-pattern:" + slugify(patternName);
        Map<String, Object> data = Map.of(
            "name", patternName,
            "sqlTemplate", sqlTemplate,
            "description", description
        );
        return new AgentMemoryEvent(EventType.QUERY_PATTERN_LEARNED, topicId, data, sessionId);
    }

    public static AgentMemoryEvent userFeedback(String feedbackType, String content,
                                                 String context, String sessionId) {
        String topicId = "feedback:" + slugify(feedbackType);
        Map<String, Object> data = Map.of(
            "feedbackType", feedbackType,
            "content", content,
            "context", context
        );
        return new AgentMemoryEvent(EventType.USER_FEEDBACK, topicId, data, sessionId);
    }

    public static AgentMemoryEvent userPreference(String preferenceType, String key,
                                                   String value, String sessionId) {
        String topicId = "user:" + slugify(preferenceType);
        Map<String, Object> data = Map.of(
            "preferenceType", preferenceType,
            "key", key,
            "value", value
        );
        return new AgentMemoryEvent(EventType.USER_PREFERENCE, topicId, data, sessionId);
    }

    // ==================== 工具方法 ====================

    /**
     * 生成 slug：小写，非 [a-z0-9_] 转 _，合并连续 _，去掉首尾 _
     */
    public static String slugify(String input) {
        if (input == null || input.isBlank()) {
            return "unknown";
        }
        String slug = input.toLowerCase()
            .replaceAll("[^a-z0-9_]", "_")
            .replaceAll("_+", "_")
            .replaceAll("^_|_$", "");
        return slug.isBlank() ? "unknown" : slug;
    }

    // ==================== Getters/Setters ====================

    public EventType getEvent() {
        return event;
    }

    public void setEvent(EventType event) {
        this.event = event;
    }

    public String getTopicId() {
        return topicId;
    }

    public void setTopicId(String topicId) {
        this.topicId = topicId;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}