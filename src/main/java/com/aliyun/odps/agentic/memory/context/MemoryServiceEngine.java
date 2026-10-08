package com.aliyun.odps.agentic.memory.context;

import com.aliyun.odps.agentic.memory.context.MemoryService;
import com.aliyun.odps.agentic.memory.context.WorkingMemory;
import com.aliyun.odps.agentic.memory.context.SemanticMemoryEntry;
import com.aliyun.odps.agentic.memory.context.EpisodicMemoryEntry;

import com.aliyun.odps.agentic.memory.index.EpisodicMemoryService;
import com.aliyun.odps.agentic.memory.index.SemanticFact;
import com.aliyun.odps.agentic.memory.index.SemanticMemoryStore;
import com.aliyun.odps.agentic.memory.index.dto.RankedEpisode;
import com.aliyun.odps.agentic.memory.index.dto.RankedFact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Memory facade for working memory plus SQLite FTS5 semantic/episodic indexes.
 *
 * <p>失败时降级（recall 返回空，persist 返回 false），不抛出（不卡 LLM 主调用）。
 *
 * <p>桌面单机用户的默认 userId 为空字符串安全降级 → 实现层强制 fallback 到 "local-user"。
 */
public class MemoryServiceEngine implements MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryServiceEngine.class);

    /** 桌面单机默认 userId（红线 #12：仍需强制非空 userId 隔离） */
    public static final String DEFAULT_USER_ID = "local-user";

    /** Episodic 召回上限 clamp（接口注释 §recallEpisodic #4） */
    private static final int EPISODIC_TOP_K_CAP = 10;

    /** Semantic 召回上限 clamp */
    private static final int SEMANTIC_TOP_K_CAP = 20;

    private final EpisodicMemoryService episodicMemoryService;
    private final SemanticMemoryStore semanticStore;

    /** 可选 — 测试场景下不注入 SessionManager / AgentSessionState 直接用快照 ctor */
    private final MemorySessionSource sessionSource;

    /** FTS5/BM25 recall metrics. */
    private final EpisodicMemoryMetrics episodicMetrics;

    private final boolean semanticEnabled;

    /**
     * Semantic 视图字段（source / confidence / contentType）映射 —— SemanticFact 不存这些字段，
     * 这里走旁路 map 维护，scope+ownerId+key 唯一键。
     */
    private final Map<String, SemanticAuxMeta> semanticAux = new ConcurrentHashMap<>();

    public MemoryServiceEngine(EpisodicMemoryService episodicMemoryService, SemanticMemoryStore semanticStore,
                               MemorySessionSource sessionSource, EpisodicMemoryMetrics episodicMetrics, boolean semanticEnabled) {
        this.episodicMemoryService = episodicMemoryService;
        this.semanticStore = semanticStore;
        this.sessionSource = sessionSource;
        this.episodicMetrics = episodicMetrics;
        this.semanticEnabled = semanticEnabled;
    }

    public boolean isSemanticEnabled() {
        return semanticEnabled;
    }

    // ════════════════════════════════════════════════════════════
    // WorkingMemory
    // ════════════════════════════════════════════════════════════

    @Override
    public WorkingMemory recallWorking(String sessionId) {
        try {
            MemorySessionSource.Context ctx = resolveContext(sessionId);
            return buildWorkingMemorySnapshot(sessionId, ctx);
        } catch (Exception e) {
            log.warn("[Memory] recallWorking failed for session {}: {}", sessionId, e.getMessage());
            return emptyWorking();
        }
    }

    private MemorySessionSource.Context resolveContext(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        if (sessionSource != null) {
            MemorySessionSource.Context ctx = sessionSource.context(sessionId);
            if (ctx != null) return ctx;
        }
        return null;
    }

    private WorkingMemory buildWorkingMemorySnapshot(String sessionId, MemorySessionSource.Context ctx) {
        Map<String, String> tableSummaries = new LinkedHashMap<>();
        Map<String, List<String>> partitionedTables = new LinkedHashMap<>();
        List<WorkingMemory.RecentSqlSummary> recentSqls = new ArrayList<>();
        List<WorkingMemory.RecentChartSummary> recentCharts = new ArrayList<>();
        List<WorkingMemory.RecentMessageSummary> recentMessages = new ArrayList<>();
        String userGoalSummary = "";

        if (ctx != null) {
            // ── schema 摘要
            for (Map.Entry<String, ? extends MemorySessionSource.Schema> e : ctx.getKnownTableSchemas().entrySet()) {
                MemorySessionSource.Schema s = e.getValue();
                if (s == null) continue;
                String summary = s.getColumns() == null ? "" :
                    s.getColumns().entrySet().stream()
                        .limit(20)
                        .map(c -> c.getKey() + ":" + c.getValue())
                        .collect(Collectors.joining(", "));
                tableSummaries.put(e.getKey(), truncate(summary, 200));
            }
            for (Map.Entry<String, List<String>> e : ctx.getKnownPartitionedTables().entrySet()) {
                partitionedTables.put(e.getKey(), List.copyOf(e.getValue()));
            }
            // ── recent SQL / chart from core WorkingMemory
            try {
                com.aliyun.odps.agentic.memory.index.WorkingMemory<?> coreWm = ctx.getWorkingMemory();
                if (coreWm != null) {
                    for (var rs : coreWm.recentSqls()) {
                        if (recentSqls.size() >= WorkingMemory.DEFAULT_RECENT_SQL_CAP) break;
                        recentSqls.add(new WorkingMemory.RecentSqlSummary(
                            truncate(rs.sql(), 240),
                            rs.summary(),
                            rs.rowCount(),
                            rs.instanceId(),
                            rs.timestamp()));
                    }
                    for (var rc : coreWm.recentCharts()) {
                        if (recentCharts.size() >= WorkingMemory.DEFAULT_RECENT_CHART_CAP) break;
                        recentCharts.add(new WorkingMemory.RecentChartSummary(
                            rc.chartType(), rc.summary(), rc.artifactId(), rc.timestamp()));
                    }
                }
            } catch (Exception swallow) {
                log.debug("[Memory] core WorkingMemory unavailable: {}", swallow.getMessage());
            }
            String goal = ctx.getGoal();
            if (goal != null) userGoalSummary = truncate(goal, 400);
        }

        // ── 最近 message 摘要 — 走 SessionManager（避免直接读 Session.messages 列表内部状态）
        if (sessionSource != null && sessionId != null) {
            try {
                MemorySessionSource.SessionView session = sessionSource.session(sessionId);
                if (session != null) {
                    if ((userGoalSummary == null || userGoalSummary.isBlank()) && session.getGoal() != null) {
                        userGoalSummary = truncate(session.getGoal(), 400);
                    }
                    List<Map<String, Object>> messages = session.getMessages();
                    if (messages != null) {
                        // 取最近 N 条
                        int total = messages.size();
                        int from = Math.max(0, total - WorkingMemory.DEFAULT_RECENT_MESSAGES_CAP);
                        for (int i = total - 1; i >= from; i--) {
                            Map<String, Object> m = messages.get(i);
                            if (m == null) continue;
                            String role = String.valueOf(m.getOrDefault("role", ""));
                            String content = String.valueOf(m.getOrDefault("content", ""));
                            Object messageId = m.get("messageId");
                            Object ts = m.getOrDefault("timestamp", m.get("ts"));
                            long when = (ts instanceof Number) ? ((Number) ts).longValue() : 0L;
                            recentMessages.add(new WorkingMemory.RecentMessageSummary(
                                role,
                                truncate(content, 200),
                                messageId != null ? String.valueOf(messageId) : null,
                                when));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[Memory] reading session messages failed: {}", e.getMessage());
            }
        }

        // ── budget 截断
        WorkingMemory wm = new WorkingMemory(
            Map.copyOf(tableSummaries),
            Map.copyOf(partitionedTables),
            List.copyOf(recentSqls),
            List.copyOf(recentCharts),
            List.copyOf(recentMessages),
            userGoalSummary != null ? userGoalSummary : "",
            0,
            System.currentTimeMillis());
        return clampToBudget(wm);
    }

    private WorkingMemory clampToBudget(WorkingMemory wm) {
        int est = estimateChars(wm);
        if (est <= WorkingMemory.DEFAULT_BUDGET_CHARS) {
            return new WorkingMemory(
                wm.knownTableSchemaSummaries(),
                wm.knownPartitionedTables(),
                wm.recentSqls(),
                wm.recentCharts(),
                wm.recentMessages(),
                wm.userGoalSummary(),
                est,
                wm.snapshotAt());
        }
        // 优先丢弃顺序：recentMessages → recentCharts → recentSqls → schema 长字符串截断
        List<WorkingMemory.RecentMessageSummary> msgs = new ArrayList<>(wm.recentMessages());
        List<WorkingMemory.RecentChartSummary> charts = new ArrayList<>(wm.recentCharts());
        List<WorkingMemory.RecentSqlSummary> sqls = new ArrayList<>(wm.recentSqls());
        Map<String, String> schemas = new LinkedHashMap<>(wm.knownTableSchemaSummaries());

        while (est > WorkingMemory.DEFAULT_BUDGET_CHARS && !msgs.isEmpty()) {
            msgs.remove(msgs.size() - 1);
            est = estimateChars(schemas, wm.knownPartitionedTables(), sqls, charts, msgs, wm.userGoalSummary());
        }
        while (est > WorkingMemory.DEFAULT_BUDGET_CHARS && !charts.isEmpty()) {
            charts.remove(charts.size() - 1);
            est = estimateChars(schemas, wm.knownPartitionedTables(), sqls, charts, msgs, wm.userGoalSummary());
        }
        while (est > WorkingMemory.DEFAULT_BUDGET_CHARS && !sqls.isEmpty()) {
            sqls.remove(sqls.size() - 1);
            est = estimateChars(schemas, wm.knownPartitionedTables(), sqls, charts, msgs, wm.userGoalSummary());
        }
        while (est > WorkingMemory.DEFAULT_BUDGET_CHARS && !schemas.isEmpty()) {
            // 进一步截短 schema 摘要
            String firstKey = schemas.keySet().iterator().next();
            String value = schemas.get(firstKey);
            if (value.length() > 60) {
                schemas.put(firstKey, value.substring(0, 60));
            } else {
                schemas.remove(firstKey);
            }
            est = estimateChars(schemas, wm.knownPartitionedTables(), sqls, charts, msgs, wm.userGoalSummary());
        }

        return new WorkingMemory(
            Map.copyOf(schemas),
            wm.knownPartitionedTables(),
            List.copyOf(sqls),
            List.copyOf(charts),
            List.copyOf(msgs),
            wm.userGoalSummary(),
            est,
            wm.snapshotAt());
    }

    private int estimateChars(WorkingMemory wm) {
        return estimateChars(wm.knownTableSchemaSummaries(), wm.knownPartitionedTables(),
            wm.recentSqls(), wm.recentCharts(), wm.recentMessages(), wm.userGoalSummary());
    }

    private int estimateChars(Map<String, String> schemas,
                              Map<String, List<String>> partitions,
                              List<WorkingMemory.RecentSqlSummary> sqls,
                              List<WorkingMemory.RecentChartSummary> charts,
                              List<WorkingMemory.RecentMessageSummary> msgs,
                              String userGoal) {
        int total = 0;
        if (userGoal != null) total += userGoal.length();
        for (Map.Entry<String, String> e : schemas.entrySet()) {
            total += e.getKey().length() + (e.getValue() == null ? 0 : e.getValue().length()) + 4;
        }
        for (Map.Entry<String, List<String>> e : partitions.entrySet()) {
            total += e.getKey().length() + 4;
            for (String p : e.getValue()) total += p.length() + 2;
        }
        for (var s : sqls) total += (s.sql() == null ? 0 : s.sql().length()) + 16;
        for (var c : charts) total += (c.summary() == null ? 0 : c.summary().length()) + 16;
        for (var m : msgs) total += (m.summary() == null ? 0 : m.summary().length()) + 16;
        return total;
    }

    private WorkingMemory emptyWorking() {
        return new WorkingMemory(
            Map.of(), Map.of(),
            List.of(), List.of(), List.of(),
            "", 0, System.currentTimeMillis());
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ════════════════════════════════════════════════════════════
    // EpisodicMemory
    // ════════════════════════════════════════════════════════════

    @Override
    public List<EpisodicMemoryEntry> recallEpisodic(String userId, String query, int topK) {
        String uid = normalizeUserId(userId);
        if (topK <= 0) return List.of();
        int k = Math.min(topK, EPISODIC_TOP_K_CAP);
        // S1.5：埋点 query 计数（无论是否命中）—— 不改业务路径
        if (episodicMetrics != null) {
            try { episodicMetrics.recordQuery(); } catch (Exception ignore) { }
        }
        try {
            List<RankedEpisode> raw = episodicMemoryService.searchByQuery(uid, query == null ? "" : query, k);
            if (raw == null || raw.isEmpty()) return List.of();
            if (episodicMetrics != null) {
                try {
                    episodicMetrics.recordHit(raw.get(0).bm25Score(), true);
                } catch (Exception ignore) { }
            }
            List<EpisodicMemoryEntry> out = new ArrayList<>(raw.size());
            for (RankedEpisode e : raw) {
                out.add(toView(e));
            }
            return Collections.unmodifiableList(out);
        } catch (Exception e) {
            log.warn("[Memory] recallEpisodic failed for user {}: {}", uid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public boolean persistEpisodic(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            episodicMemoryService.indexSession(sessionId);
            return true;
        } catch (Exception e) {
            log.warn("[Memory] persistEpisodic failed for session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    @Override
    public void revokeEpisodicAuth(String userId, boolean keepMetadata) {
        String uid = normalizeUserId(userId);
        try {
            int removed = episodicMemoryService.deleteForUser(uid);
            log.info("[Memory] revokeEpisodicAuth: user={}, removed={}, keepMetadata={}",
                uid, removed, keepMetadata);
        } catch (Exception e) {
            log.warn("[Memory] revokeEpisodicAuth failed for user {}: {}", uid, e.getMessage());
        }
    }

    private EpisodicMemoryEntry toView(RankedEpisode e) {
        String outcome = switch (e.status()) {
            case "finished" -> "success";
            case "failed" -> "failed";
            case "aborted" -> "aborted";
            default -> "running";
        };
        return new EpisodicMemoryEntry(
            UUID.randomUUID().toString(),
            normalizeUserId(e.userId()),
            "",
            e.sessionId(),
            e.goalText(),
            e.summaryText(),
            outcome,
            "",
            String.join(",", e.toolNames()),
            e.bm25Score(),
            e.createdAt(),
            e.lastMessageAt(),
            Map.of("workspaceId", e.workspaceId() == null ? "" : e.workspaceId()));
    }

    // ════════════════════════════════════════════════════════════
    // SemanticMemory
    // ════════════════════════════════════════════════════════════

    @Override
    public List<SemanticMemoryEntry> recallSemantic(String userId, String query, int topK) {
        if (!semanticEnabled || topK <= 0) return List.of();
        String uid = normalizeUserId(userId);
        int k = Math.min(topK, SEMANTIC_TOP_K_CAP);
        try {
            // 单机单 owner(2026-10-08 移除 user/team 分类):一次查询即全集
            List<RankedFact> scored = new ArrayList<>(semanticStore.findByQuery(query, null, uid, k));
            scored.sort(Comparator.comparingDouble(RankedFact::bm25Score).reversed());
            List<SemanticMemoryEntry> out = new ArrayList<>();
            for (int i = 0; i < Math.min(k, scored.size()); i++) {
                RankedFact hit = scored.get(i);
                out.add(toView(hit.fact(), hit.bm25Score()));
            }
            return Collections.unmodifiableList(out);
        } catch (Exception e) {
            log.warn("[Memory] recallSemantic failed for user {}: {}", uid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public List<SemanticMemoryEntry> listSemantic(String userId, int offset, int limit) {
        if (!semanticEnabled) return List.of();
        String uid = normalizeUserId(userId);
        int safeLimit = Math.max(1, Math.min(limit, 50));
        int safeOffset = Math.max(0, offset);
        try {
            // 单 owner 直读(旧 R69 k 路归并随 user/team 分类移除而消失——单一来源,SQL 层分页即精确)
            List<SemanticMemoryEntry> out = new ArrayList<>();
            for (SemanticFact f : semanticStore.listFor(uid, safeOffset, safeLimit)) {
                out.add(toView(f, 0.0));
            }
            return Collections.unmodifiableList(out);
        } catch (Exception e) {
            log.warn("[Memory] listSemantic failed for user {}: {}", uid, e.getMessage());
            return List.of();
        }
    }

    @Override
    public void persistSemantic(String ownerId, String key,
                                String value, Set<String> tags, String source) {
        if (!semanticEnabled) return;
        String owner = normalizeUserId(ownerId);
        if (key == null || key.isBlank()) return;
        try {
            Set<String> tagSet = tags == null ? Set.of() : Set.copyOf(tags);
            SemanticFact fact = new SemanticFact(key, value == null ? "" : value, owner, tagSet, System.currentTimeMillis());
            String src = (source == null || source.isBlank()) ? "manual" : source;
            semanticStore.put(fact, "custom", src);
            double conf = "manual".equalsIgnoreCase(src) ? 1.0 : 0.4;
            semanticAux.put(auxKey(owner, key),
                new SemanticAuxMeta(src, conf, "text/plain", System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("[Memory] persistSemantic failed: key={}, err={}",
                key, e.getMessage());
        }
    }

    @Override
    public boolean deleteSemantic(String ownerId, String key) {
        if (ownerId == null || key == null) return false;
        try {
            boolean removed = semanticStore.remove(normalizeUserId(ownerId), key);
            semanticAux.remove(auxKey(normalizeUserId(ownerId), key));
            return removed;
        } catch (Exception e) {
            log.warn("[Memory] deleteSemantic failed: key={}, err={}",
                key, e.getMessage());
            return false;
        }
    }


    private SemanticMemoryEntry toView(SemanticFact f, double score) {
        SemanticAuxMeta aux = semanticAux.get(auxKey(f.ownerId(), f.key()));
        String source = aux != null ? aux.source : "manual";
        double confidence = aux != null ? aux.confidence : 1.0;
        String contentType = aux != null ? aux.contentType : "text/plain";
        // created_at 已落库(V20261006_004):重启后 aux 旁路为空时读真值,不再退化为 updatedAt(评审 Minor)
        long createdAt = aux != null ? aux.createdAt : (f.createdAt() > 0 ? f.createdAt() : f.updatedAt());

        return new SemanticMemoryEntry(
            UUID.randomUUID().toString(),
            f.ownerId(),
            f.key(),
            f.value() == null ? "" : f.value().toString(),
            contentType,
            f.tags(),
            source,
            confidence,
            score,
            createdAt,
            f.updatedAt());
    }

    private static String normalizeUserId(String userId) {
        return (userId == null || userId.isBlank()) ? DEFAULT_USER_ID : userId;
    }

    private static String auxKey(String ownerId, String key) {
        return ownerId + ":" + key;
    }

    private record SemanticAuxMeta(String source, double confidence, String contentType, long createdAt) {}
}
