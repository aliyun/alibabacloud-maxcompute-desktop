package com.aliyun.odps.agentic.memory.index;

import com.aliyun.odps.agentic.memory.index.EpisodicMemoryService;

import com.aliyun.odps.agentic.memory.index.SessionEpisodeIndexer.SessionEpisodeDocument;
import com.aliyun.odps.agentic.memory.index.dto.RankedEpisode;
import com.aliyun.odps.agentic.memory.index.dto.RebuildResult;
import com.aliyun.odps.agentic.storage.SqlDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SqliteEpisodicMemoryService implements EpisodicMemoryService, AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(SqliteEpisodicMemoryService.class);
    private static final long SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L;
    private static final long THIRTY_DAYS_MS = 30L * 24L * 60L * 60L * 1000L;

    private final SqlDatabase jdbc;
    private final SessionEpisodeIndexer indexer;
    private final SessionEpisodeSource source;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "session-episode-indexer");
        thread.setDaemon(true);
        return thread;
    });
    private Runnable unsubscribe;

    public SqliteEpisodicMemoryService(SqlDatabase jdbc,SessionEpisodeIndexer indexer,SessionEpisodeSource source) {
        this.jdbc=jdbc; this.indexer=indexer; this.source=source;
    }

    public void start() {
        unsubscribe = source.subscribe(this::onLifecycleEvent);
        maybeRebuildIndexAsync();
    }

    @Override public void close() { stop(); }

    public void stop() {
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
        executor.shutdownNow();
    }

    @Override
    public List<RankedEpisode> searchByQuery(String userId, String query, int topK) {
        if (query == null || query.isBlank()) return List.of();
        String uid = requireUserId(userId);
        int limit = topK > 0 ? topK : 10;
        String normalized = normalizeQuery(query);
        if (looksLikeSessionId(normalized)) {
            Optional<RankedEpisode> exact = findById(normalized, uid);
            if (exact.isPresent()) return List.of(exact.get());
        }
        List<RankedEpisode> fts = findByFtsMatch(normalized, uid, limit);
        if (fts.size() >= Math.min(3, limit)) return fts.stream().limit(limit).toList();
        return mergeWithTimeDecay(fts, findByLike(normalized, uid, Math.max(limit, 50)), limit);
    }

    @Override
    public void indexSession(String sessionId) {
        try { indexSessionInternal(sessionId); } catch (Exception e) {
            log.warn("[EpisodicMemory] indexSession failed session={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return;
        try { jdbc.update("DELETE FROM session_episode WHERE session_id = ?", sessionId); } catch (Exception e) {
            log.warn("[EpisodicMemory] deleteSession failed session={}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public RebuildResult rebuildIndex() {
        long start = System.currentTimeMillis();
        int scanned = 0, upserted = 0, failed = 0;
        try {
            jdbc.update("DELETE FROM session_episode");
            List<Map<String, Object>> states = source.listSessionStates();
            scanned = states.size();
            for (Map<String, Object> state : states) {
                String sessionId = firstNonBlank(stringValue(state.get("id")), stringValue(state.get("sessionId")));
                if (sessionId.isBlank()) { failed++; continue; }
                try { if (indexSessionInternal(sessionId)) upserted++; } catch (Exception e) {
                    failed++;
                    log.warn("[EpisodicMemory] rebuild failed session={}: {}", sessionId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[EpisodicMemory] rebuildIndex failed: {}", e.getMessage());
        }
        return new RebuildResult(scanned, upserted, failed, System.currentTimeMillis() - start);
    }

    @Override
    public int deleteForUser(String userId) {
        if (userId == null || userId.isBlank()) return 0;
        try { return jdbc.update("DELETE FROM session_episode WHERE user_id = ?", userId.trim()); } catch (Exception e) {
            log.warn("[EpisodicMemory] deleteForUser failed user={}: {}", userId, e.getMessage());
            return 0;
        }
    }

    public int size() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM session_episode", Integer.class);
        return count == null ? 0 : count;
    }

    private void onLifecycleEvent(SessionEpisodeSource.Change event) {
        if (event == null || event.type() == null) return;
        if (event.type() == SessionEpisodeSource.Type.CREATED || event.type() == SessionEpisodeSource.Type.UPDATED) {
            executor.submit(() -> indexSession(event.sessionId()));
        } else if (event.type() == SessionEpisodeSource.Type.DELETED
            || event.type() == SessionEpisodeSource.Type.DELETE_FAILED) {
            // DELETE_FAILED（磁盘删失败）也删 FTS 行，避免搜索命中指向幽灵目录的 episode。
            executor.submit(() -> deleteSession(event.sessionId()));
        }
    }

    private boolean indexSessionInternal(String sessionId) {
        Optional<SessionEpisodeDocument> maybeDoc = indexer.extract(sessionId);
        if (maybeDoc.isEmpty()) return false;
        SessionEpisodeDocument doc = maybeDoc.get();
        jdbc.update("""
            INSERT INTO session_episode(
                session_id, user_id, workspace_id, created_at, last_message_at,
                goal_text, summary_text, tool_names_csv, status, indexed_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(session_id) DO UPDATE SET
                user_id = excluded.user_id,
                workspace_id = excluded.workspace_id,
                created_at = excluded.created_at,
                last_message_at = excluded.last_message_at,
                goal_text = excluded.goal_text,
                summary_text = excluded.summary_text,
                tool_names_csv = excluded.tool_names_csv,
                status = excluded.status,
                indexed_at = excluded.indexed_at
            """, doc.sessionId(), doc.userId(), doc.workspaceId(), doc.createdAt(), doc.lastMessageAt(),
            doc.goalText(), doc.summaryText(), doc.toolNamesCsv(), doc.status(), doc.indexedAt());
        return true;
    }

    private void maybeRebuildIndexAsync() {
        executor.submit(() -> {
            try {
                int rows = size();
                int sessions = source.listSessionStates().size();
                if (sessions == 0) return;
                if (Math.abs(rows - sessions) / (double) sessions > 0.10d) rebuildIndex();
            } catch (Exception e) {
                log.warn("[EpisodicMemory] startup index check failed: {}", e.getMessage());
            }
        });
    }

    private Optional<RankedEpisode> findById(String sessionId, String userId) {
        return jdbc.query("""
            SELECT session_id, user_id, workspace_id, created_at, last_message_at,
                   goal_text, summary_text, tool_names_csv, status
            FROM session_episode WHERE session_id = ? AND user_id = ? LIMIT 1
            """, (rs, rowNum) -> toEpisode(rs.getString("session_id"), rs.getString("user_id"),
            rs.getString("workspace_id"), rs.getLong("created_at"), rs.getLong("last_message_at"),
            rs.getString("goal_text"), rs.getString("summary_text"), rs.getString("tool_names_csv"),
            rs.getString("status"), 1.0), sessionId, userId).stream().findFirst();
    }

    private List<RankedEpisode> findByFtsMatch(String normalized, String userId, int limit) {
        String ftsQuery = buildFtsQuery(normalized);
        if (ftsQuery.isBlank()) return List.of();
        try {
            return jdbc.query("""
                SELECT e.session_id, e.user_id, e.workspace_id, e.created_at, e.last_message_at,
                       e.goal_text, e.summary_text, e.tool_names_csv, e.status,
                       bm25(session_episode_fts) AS rank
                FROM session_episode e
                JOIN session_episode_fts fts ON fts.rowid = e.rowid
                WHERE session_episode_fts MATCH ? AND e.user_id = ?
                ORDER BY rank LIMIT ?
                """, (rs, rowNum) -> toEpisode(rs.getString("session_id"), rs.getString("user_id"),
                rs.getString("workspace_id"), rs.getLong("created_at"), rs.getLong("last_message_at"),
                rs.getString("goal_text"), rs.getString("summary_text"), rs.getString("tool_names_csv"),
                rs.getString("status"), toRelevance(rs.getDouble("rank"))), ftsQuery, userId, limit);
        } catch (Exception e) {
            log.debug("[EpisodicMemory] FTS search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RankedEpisode> findByLike(String normalized, String userId, int limit) {
        Map<String, RankedEpisode> hits = new LinkedHashMap<>();
        for (String pattern : likePatterns(normalized)) {
            List<RankedEpisode> rows = jdbc.query("""
                SELECT session_id, user_id, workspace_id, created_at, last_message_at,
                       goal_text, summary_text, tool_names_csv, status
                FROM session_episode
                WHERE user_id = ?
                  AND (goal_text LIKE ? ESCAPE '!' OR summary_text LIKE ? ESCAPE '!' OR tool_names_csv LIKE ? ESCAPE '!')
                ORDER BY last_message_at DESC LIMIT ?
                """, (rs, rowNum) -> toEpisode(rs.getString("session_id"), rs.getString("user_id"),
                rs.getString("workspace_id"), rs.getLong("created_at"), rs.getLong("last_message_at"),
                rs.getString("goal_text"), rs.getString("summary_text"), rs.getString("tool_names_csv"),
                rs.getString("status"), 0.45), userId, pattern, pattern, pattern, limit);
            for (RankedEpisode row : rows) hits.putIfAbsent(row.sessionId(), row);
            if (hits.size() >= limit) break;
        }
        return new ArrayList<>(hits.values());
    }

    private List<RankedEpisode> mergeWithTimeDecay(List<RankedEpisode> fts, List<RankedEpisode> like, int limit) {
        Map<String, RankedEpisode> merged = new LinkedHashMap<>();
        for (RankedEpisode hit : fts) merged.put(hit.sessionId(), hit);
        for (RankedEpisode hit : like) merged.merge(hit.sessionId(), hit, (a, b) -> a.bm25Score() >= b.bm25Score() ? a : b);
        long now = System.currentTimeMillis();
        return merged.values().stream()
            .map(hit -> withScore(hit, applyTimeDecay(hit.bm25Score(), hit.lastMessageAt(), now)))
            .sorted(Comparator.comparingDouble(RankedEpisode::bm25Score).reversed()
                .thenComparing(RankedEpisode::lastMessageAt, Comparator.reverseOrder()))
            .limit(limit)
            .toList();
    }

    private static double applyTimeDecay(double score, long lastMessageAt, long now) {
        if (lastMessageAt <= 0) return score;
        long age = Math.max(0L, now - lastMessageAt);
        if (age <= SEVEN_DAYS_MS) return Math.min(1.0, score + 0.20);
        if (age > THIRTY_DAYS_MS) return Math.max(0.0, score - 0.20);
        return score;
    }
    private static RankedEpisode withScore(RankedEpisode e, double score) {
        return new RankedEpisode(e.sessionId(), e.userId(), e.workspaceId(), e.createdAt(), e.lastMessageAt(),
            e.goalText(), e.summaryText(), e.toolNames(), e.status(), score);
    }
    private static RankedEpisode toEpisode(String sessionId, String userId, String workspaceId, long createdAt,
                                           long lastMessageAt, String goalText, String summaryText,
                                           String toolNamesCsv, String status, double score) {
        return new RankedEpisode(sessionId, userId, workspaceId, createdAt, lastMessageAt,
            goalText == null ? "" : goalText, summaryText == null ? "" : summaryText,
            parseCsv(toolNamesCsv), status == null ? "running" : status, score);
    }
    private static boolean looksLikeSessionId(String query) {
        String q = query == null ? "" : query.trim();
        return q.startsWith("agent-") || q.startsWith("chat-") || q.startsWith("data-")
            || q.chars().filter(ch -> ch == '-').count() >= 3;
    }
    private static String requireUserId(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId required");
        return userId.trim();
    }
    private static String normalizeQuery(String query) { return query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT); }
    private static String buildFtsQuery(String normalized) {
        List<String> terms = new ArrayList<>();
        for (String token : queryTokens(normalized)) {
            if (token.length() == 1 && token.codePointAt(0) < 128) continue;
            terms.add("\"" + token.replace("\"", "\"\"") + "\"");
        }
        return String.join(" OR ", terms);
    }
    private static Set<String> queryTokens(String query) {
        String normalized = normalizeQuery(query);
        Set<String> tokens = new LinkedHashSet<>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder nonAscii = new StringBuilder();
        for (int i = 0; i < normalized.length(); ) {
            int cp = normalized.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp) && cp < 128) {
                if (nonAscii.length() > 0) { tokens.add(nonAscii.toString()); nonAscii.setLength(0); }
                ascii.appendCodePoint(cp);
            } else if (!Character.isWhitespace(cp) && cp >= 128) {
                if (ascii.length() > 0) { tokens.add(ascii.toString()); ascii.setLength(0); }
                nonAscii.appendCodePoint(cp);
            } else {
                if (ascii.length() > 0) { tokens.add(ascii.toString()); ascii.setLength(0); }
                if (nonAscii.length() > 0) { tokens.add(nonAscii.toString()); nonAscii.setLength(0); }
            }
        }
        if (ascii.length() > 0) tokens.add(ascii.toString());
        if (nonAscii.length() > 0) tokens.add(nonAscii.toString());
        String compact = normalized.replaceAll("\\s+", "");
        if (compact.length() > 1) tokens.add(compact);
        return tokens;
    }
    private static List<String> likePatterns(String normalized) {
        Set<String> patterns = new LinkedHashSet<>();
        for (String token : queryTokens(normalized)) if (token.length() > 1) patterns.add("%" + escapeLike(token) + "%");
        String compact = normalizeQuery(normalized).replaceAll("\\s+", "");
        if (compact.length() > 1) patterns.add("%" + escapeLike(compact) + "%");
        return new ArrayList<>(patterns);
    }
    private static String escapeLike(String value) { return value.replace("!", "!!").replace("%", "!%").replace("_", "!_"); }
    private static double toRelevance(double rank) { return Math.max(0.0, Math.min(1.0, 1.0 / (1.0 + Math.abs(rank)))); }
    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) if (!part.isBlank()) out.add(part.trim());
        return List.copyOf(out);
    }
    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }
}
