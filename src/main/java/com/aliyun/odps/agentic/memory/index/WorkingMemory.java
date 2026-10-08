package com.aliyun.odps.agentic.memory.index;

import java.util.function.Function;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 工作记忆（Phase 1，2026-05-22）。
 *
 * 为 message+tool_result 范式准备的会话级记忆抽象：把原本散落在
 * the host context 上的 {@code knownTableSchemas} / {@code knownPartitionedTables}
 * 等"已查 schema"缓存，集中到一个能描述结构、能 budget、能裁剪的对象里。
 *
 * 当前阶段（Phase 1）只是把已存在的两块缓存包装起来，并新增「最近 SQL / 图表」槽位，
 * 不改变任何外部 API 行为。Phase 4 message[] 持久化上线后，PromptManager 会从这里取
 * 「最近 K 条工具结果摘要」拼上下文，替代直接遍历 {@code List<ReActStep>}。
 */
public class WorkingMemory<S> {

    public static final int DEFAULT_RECENT_SQL_CAPACITY = 8;
    public static final int DEFAULT_RECENT_CHART_CAPACITY = 4;

    private final Map<String, S> knownTableSchemas;
    private final Map<String, List<String>> knownPartitionedTables;

    private final Deque<RecentSql> recentSqls = new ConcurrentLinkedDeque<>();
    private final Deque<RecentChart> recentCharts = new ConcurrentLinkedDeque<>();

    private final Function<S, Map<String,Object>> encodeSchema;
    private final Function<Map<String,Object>, S> decodeSchema;
    private final int recentSqlCapacity;
    private final int recentChartCapacity;

    public WorkingMemory(Map<String,S> schemas, Map<String,List<String>> partitions,
                         int sqlCapacity, int chartCapacity,
                         Function<S,Map<String,Object>> encodeSchema, Function<Map<String,Object>,S> decodeSchema) {
        this.knownTableSchemas = schemas != null ? schemas : new ConcurrentHashMap<>();
        this.knownPartitionedTables = partitions != null ? partitions : new ConcurrentHashMap<>();
        this.recentSqlCapacity = sqlCapacity;
        this.recentChartCapacity = chartCapacity;
        this.encodeSchema = encodeSchema;
        this.decodeSchema = decodeSchema;
    }

    public Map<String, S> knownTableSchemas() {
        return knownTableSchemas;
    }

    public Map<String, List<String>> knownPartitionedTables() {
        return knownPartitionedTables;
    }

    public S getTableSchema(String tableName) {
        if (tableName == null) return null;
        return knownTableSchemas.get(tableName.toLowerCase());
    }

    public void recordSql(String sql, String summary, Integer rowCount, String instanceId) {
        if (sql == null || sql.isBlank()) return;
        RecentSql entry = new RecentSql(sql, summary, rowCount, instanceId, System.currentTimeMillis());
        recentSqls.addFirst(entry);
        while (recentSqls.size() > recentSqlCapacity) {
            recentSqls.pollLast();
        }
    }

    public void recordChart(String chartType, String summary, String artifactId) {
        if (chartType == null || chartType.isBlank()) return;
        RecentChart entry = new RecentChart(chartType, summary, artifactId, System.currentTimeMillis());
        recentCharts.addFirst(entry);
        while (recentCharts.size() > recentChartCapacity) {
            recentCharts.pollLast();
        }
    }

    public List<RecentSql> recentSqls() {
        return new ArrayList<>(recentSqls);
    }

    public List<RecentChart> recentCharts() {
        return new ArrayList<>(recentCharts);
    }

    public Map<String, Object> snapshotForObservability() {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("knownTableCount", knownTableSchemas.size());
        snap.put("knownPartitionedTableCount", knownPartitionedTables.size());
        snap.put("recentSqlCount", recentSqls.size());
        snap.put("recentChartCount", recentCharts.size());
        return Collections.unmodifiableMap(snap);
    }

    // ════════════════════════════════════════════════════════════
    // Lane C3（2026-05-25）— WorkingMemory 终态持久化
    //   终态时 snapshot 落盘到 sessions/<id>/working-memory.json，
    //   resume 时反序列化 + installSnapshot 还原。
    //   红线：snapshot 字段缺失/格式异常时降级（不抛），主链路不受影响。
    // ════════════════════════════════════════════════════════════

    /**
     * 序列化为 JSON-friendly Map（NDJSON-friendly，Jackson 可直接 writeValue）。
     * <p>包含全部四块状态：knownTableSchemas / knownPartitionedTables / recentSqls / recentCharts。
     * <p>capacity 字段也写出，便于 resume 时复原相同的窗口大小（避免老快照在新版本默认值变化时丢失精度）。
     */
    public Map<String, Object> toSnapshot() {
        Map<String, Object> snap = new LinkedHashMap<>();

        Map<String, Object> schemaMap = new LinkedHashMap<>();
        for (Map.Entry<String, S> e : knownTableSchemas.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                schemaMap.put(e.getKey(), encodeSchema.apply(e.getValue()));
            }
        }
        snap.put("knownTableSchemas", schemaMap);

        Map<String, Object> partMap = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : knownPartitionedTables.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                partMap.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
        }
        snap.put("knownPartitionedTables", partMap);

        // recentSqls / recentCharts：保持 deque 的"最近优先"顺序（addFirst 写入，所以
        // recentSqls.iterator() 自然就是 most-recent-first）。
        List<Map<String, Object>> sqls = new ArrayList<>(recentSqls.size());
        for (RecentSql s : recentSqls) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sql", s.sql());
            if (s.summary() != null) row.put("summary", s.summary());
            if (s.rowCount() != null) row.put("rowCount", s.rowCount());
            if (s.instanceId() != null) row.put("instanceId", s.instanceId());
            row.put("timestamp", s.timestamp());
            sqls.add(row);
        }
        snap.put("recentSqls", sqls);

        List<Map<String, Object>> charts = new ArrayList<>(recentCharts.size());
        for (RecentChart c : recentCharts) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chartType", c.chartType());
            if (c.summary() != null) row.put("summary", c.summary());
            if (c.artifactId() != null) row.put("artifactId", c.artifactId());
            row.put("timestamp", c.timestamp());
            charts.add(row);
        }
        snap.put("recentCharts", charts);

        snap.put("recentSqlCapacity", recentSqlCapacity);
        snap.put("recentChartCapacity", recentChartCapacity);
        return snap;
    }

    /**
     * 从 snapshot 恢复 — 用于 session resume。
     * <p>语义：清空当前的 recents（覆盖式，非合并），knownTableSchemas / knownPartitionedTables
     * 走 putAll（合并：保留已有 + 添加快照）。
     * <p>红线：snapshot 为 {@code null} 或缺字段时静默跳过（不抛），主链路不受影响。
     * <p>capacity 不可变（构造期固定）；snapshot 中的 capacity 字段仅做 sanity check 日志，不应用。
     */
    public void installSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null) return;

        Object schemasRaw = snapshot.get("knownTableSchemas");
        if (schemasRaw instanceof Map<?, ?> schemasMap) {
            for (Map.Entry<?, ?> e : schemasMap.entrySet()) {
                if (e.getKey() == null || !(e.getValue() instanceof Map<?, ?> schemaData)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) schemaData;
                S schema = decodeSchema.apply(typed);
                if (schema != null) {
                    knownTableSchemas.put(String.valueOf(e.getKey()), schema);
                }
            }
        }

        Object partRaw = snapshot.get("knownPartitionedTables");
        if (partRaw instanceof Map<?, ?> partMap) {
            for (Map.Entry<?, ?> e : partMap.entrySet()) {
                if (e.getKey() == null || !(e.getValue() instanceof List<?> partList)) continue;
                List<String> cols = new ArrayList<>();
                for (Object item : partList) {
                    if (item != null) cols.add(String.valueOf(item));
                }
                knownPartitionedTables.put(String.valueOf(e.getKey()), cols);
            }
        }

        // recentSqls / recentCharts：字段级覆盖语义 —
        //   字段存在（含空 list）→ 清空 + 重放；字段缺失 → 保留现有状态（避免老/坏快照丢数据）。
        //   snapshot 中的顺序是 most-recent-first，addLast 还原成 deque 的"末尾较旧"顺序。
        if (snapshot.containsKey("recentSqls") && snapshot.get("recentSqls") instanceof List<?> sqlsList) {
            recentSqls.clear();
            for (Object item : sqlsList) {
                if (!(item instanceof Map<?, ?> rowMap)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) rowMap;
                String sql = (String) row.get("sql");
                if (sql == null || sql.isBlank()) continue;
                String summary = (String) row.get("summary");
                Integer rowCount = row.get("rowCount") instanceof Number n ? n.intValue() : null;
                String instanceId = (String) row.get("instanceId");
                long ts = row.get("timestamp") instanceof Number n2 ? n2.longValue() : System.currentTimeMillis();
                if (recentSqls.size() < recentSqlCapacity) {
                    recentSqls.addLast(new RecentSql(sql, summary, rowCount, instanceId, ts));
                }
            }
        }

        if (snapshot.containsKey("recentCharts") && snapshot.get("recentCharts") instanceof List<?> chartsList) {
            recentCharts.clear();
            for (Object item : chartsList) {
                if (!(item instanceof Map<?, ?> rowMap)) continue;
                @SuppressWarnings("unchecked")
                Map<String, Object> row = (Map<String, Object>) rowMap;
                String chartType = (String) row.get("chartType");
                if (chartType == null || chartType.isBlank()) continue;
                String summary = (String) row.get("summary");
                String artifactId = (String) row.get("artifactId");
                long ts = row.get("timestamp") instanceof Number n ? n.longValue() : System.currentTimeMillis();
                if (recentCharts.size() < recentChartCapacity) {
                    recentCharts.addLast(new RecentChart(chartType, summary, artifactId, ts));
                }
            }
        }
    }

    public record RecentSql(
        String sql,
        String summary,
        Integer rowCount,
        String instanceId,
        long timestamp
    ) {}

    public record RecentChart(
        String chartType,
        String summary,
        String artifactId,
        long timestamp
    ) {}
}
