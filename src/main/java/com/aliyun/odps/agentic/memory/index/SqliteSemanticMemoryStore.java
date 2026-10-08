package com.aliyun.odps.agentic.memory.index;

import com.aliyun.odps.agentic.memory.index.SemanticFact;
import com.aliyun.odps.agentic.memory.index.SemanticMemoryStore;

import com.aliyun.odps.agentic.memory.index.dto.RankedFact;
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
import java.util.Set;

/**
 * 单机单 owner 形态(2026-10-08 用户裁定移除 user/team 分类,owner_type 列已随
 * migration V20261008_005 删除)。`memoryScope` 是记忆类目(custom 等),不是隔离维度。
 */
public class SqliteSemanticMemoryStore implements SemanticMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(SqliteSemanticMemoryStore.class);
    private final SqlDatabase jdbc;

    public SqliteSemanticMemoryStore(SqlDatabase jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(SemanticFact fact) {
        put(fact, "custom", "manual");
    }

    @Override
    public void put(SemanticFact fact, String memoryScope, String source) {
        if (fact == null) return;
        String ownerId = requireText(fact.ownerId(), "ownerId");
        String scope = isBlank(memoryScope) ? "custom" : memoryScope.trim();
        String key = fact.key();
        String value = fact.value() == null ? "" : String.valueOf(fact.value());
        String tags = tagsCsv(fact.tags());
        String src = isBlank(source) ? "manual" : source.trim();
        long updatedAt = fact.updatedAt() > 0 ? fact.updatedAt() : System.currentTimeMillis();

        // §23.12f dedup audit：命中 (owner_id, scope, key) UNIQUE 冲突 → 旧值入 history
        Double existingConfidence = readExistingAndAuditToHistory(ownerId, scope, key, src);
        double newConfidence = nextConfidence(existingConfidence, src);

        jdbc.update("""
            INSERT INTO semantic_fact(owner_id, scope, key, value, tags, source, updated_at, created_at, confidence, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(owner_id, scope, key) DO UPDATE SET
                value = excluded.value,
                tags = excluded.tags,
                source = excluded.source,
                updated_at = excluded.updated_at,
                confidence = excluded.confidence,
                soft_deleted_at = NULL
            """, ownerId, scope, key, value, tags, src, updatedAt, updatedAt, newConfidence, src);
    }

    private Double readExistingAndAuditToHistory(String ownerId, String scope, String key, String newSource) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT id, value, tags, source, updated_at, confidence
            FROM semantic_fact
            WHERE owner_id = ? AND scope = ? AND key = ?
            """, ownerId, scope, key);
        if (rows.isEmpty()) return null;
        Map<String, Object> old = rows.get(0);
        long now = System.currentTimeMillis();
        try {
            jdbc.update("""
                INSERT INTO semantic_fact_history(owner_id, scope, key, value, tags, source, confidence, created_at, replaced_at, replaced_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, ownerId, scope, key,
                old.get("value"), old.get("tags"), old.get("source"),
                old.get("confidence"), old.get("updated_at"), now, newSource);
        } catch (Exception e) {
            log.warn("[SemanticMemory] history audit failed: {}", e.getMessage());
        }
        Object cobj = old.get("confidence");
        return cobj instanceof Number n ? n.doubleValue() : null;
    }

    private static double nextConfidence(Double existing, String newSource) {
        double newBase = "manual".equalsIgnoreCase(newSource) ? 1.0 : 0.4;
        double base = existing == null ? newBase : Math.max(existing, newBase);
        // §23.12f：dedup 升一档
        double bumped = existing == null ? base : base + 0.05;
        return Math.min(1.0, bumped);
    }

    @Override
    public SemanticFact get(String ownerId, String key) {
        if (isBlank(ownerId) || isBlank(key)) return null;
        List<SemanticFact> facts = jdbc.query("""
            SELECT owner_id, key, value, tags, updated_at, created_at
            FROM semantic_fact
            WHERE owner_id = ? AND key = ?
            ORDER BY updated_at DESC LIMIT 1
            """, (rs, rowNum) -> toFact(rs.getString("owner_id"),
            rs.getString("key"), rs.getString("value"), rs.getString("tags"), rs.getLong("updated_at"), rs.getLong("created_at")),
            ownerId, key);
        return facts.isEmpty() ? null : facts.get(0);
    }

    @Override
    public boolean remove(String ownerId, String key) {
        if (isBlank(ownerId) || isBlank(key)) return false;
        return jdbc.update("DELETE FROM semantic_fact WHERE owner_id = ? AND key = ?",
            ownerId, key) > 0;
    }

    @Override
    public void delete(String ownerId, String memoryScope, String key) {
        requireText(ownerId, "ownerId");
        if (isBlank(key)) return;
        jdbc.update("""
            DELETE FROM semantic_fact
            WHERE owner_id = ? AND (? IS NULL OR scope = ?) AND key = ?
            """, ownerId, blankToNull(memoryScope), blankToNull(memoryScope), key);
    }

    @Override
    public List<SemanticFact> findByTags(String ownerId, Set<String> anyTags) {
        if (isBlank(ownerId) || anyTags == null || anyTags.isEmpty()) return List.of();
        Set<String> wanted = normalizeTags(anyTags);
        return jdbc.query("""
            SELECT owner_id, key, value, tags, updated_at, created_at
            FROM semantic_fact
            WHERE owner_id = ?
            ORDER BY updated_at DESC
            """, (rs, rowNum) -> toFact(rs.getString("owner_id"),
            rs.getString("key"), rs.getString("value"), rs.getString("tags"), rs.getLong("updated_at"), rs.getLong("created_at")),
            ownerId).stream()
            .filter(f -> normalizeTags(f.tags()).stream().anyMatch(wanted::contains))
            .toList();
    }

    @Override
    public List<SemanticFact> listFor(String ownerId, int offset, int limit) {
        if (isBlank(ownerId)) return List.of();
        int safeLimit = Math.max(1, Math.min(limit, 200));
        int safeOffset = Math.max(0, offset);
        return jdbc.query("""
            SELECT owner_id, key, value, tags, updated_at, created_at
            FROM semantic_fact
            WHERE owner_id = ? AND soft_deleted_at IS NULL
            ORDER BY updated_at DESC LIMIT ? OFFSET ?
            """, (rs, rowNum) -> toFact(rs.getString("owner_id"),
            rs.getString("key"), rs.getString("value"), rs.getString("tags"), rs.getLong("updated_at"), rs.getLong("created_at")),
            ownerId, safeLimit, safeOffset);
    }

    @Override
    public List<RankedFact> findByQuery(String query, String memoryScope, String ownerId, int topK) {
        if (query == null || query.isBlank()) return List.of();
        requireText(ownerId, "ownerId");
        int limit = topK > 0 ? topK : 10;
        String normalized = normalizeQuery(query);
        String factScope = blankToNull(memoryScope);
        List<RankedFact> exact = findByExactKey(normalized, ownerId, factScope, limit);
        if (!exact.isEmpty()) return exact;
        List<RankedFact> fts = findByFtsMatch(normalized, ownerId, factScope, limit);
        if (fts.size() >= Math.min(3, limit)) return fts.stream().limit(limit).toList();
        return mergeAndRank(fts, findByLike(normalized, ownerId, factScope, Math.max(limit, 50)), normalized, limit);
    }

    @Override
    public int size() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM semantic_fact", Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public int sizeFor(String ownerId) {
        if (isBlank(ownerId)) return 0;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM semantic_fact WHERE owner_id = ?",
            Integer.class, ownerId);
        return count == null ? 0 : count;
    }

    @Override
    public void clear() {
        jdbc.update("DELETE FROM semantic_fact");
    }

    // §23.12g 软删：标记 soft_deleted_at；GC 在 T+30d 物理删
    public boolean softDelete(String ownerId, String key) {
        if (isBlank(ownerId) || isBlank(key)) return false;
        return jdbc.update("""
            UPDATE semantic_fact SET soft_deleted_at = ?
            WHERE owner_id = ? AND key = ? AND soft_deleted_at IS NULL
            """, System.currentTimeMillis(), ownerId, key) > 0;
    }

    // §23.12g GC 物理删：清掉所有 soft_deleted_at < cutoff 的记录
    public int hardDeleteSoftDeletedBefore(long cutoffMillis) {
        return jdbc.update("""
            DELETE FROM semantic_fact
            WHERE soft_deleted_at IS NOT NULL AND soft_deleted_at < ?
            """, cutoffMillis);
    }

    // §23.12h 管理面：列出全部 fact（可选包含软删）
    public List<Map<String, Object>> listAllForAdmin(boolean includeSoftDeleted, int limit) {
        int lim = limit > 0 ? Math.min(limit, 5000) : 500;
        String softFilter = includeSoftDeleted ? "" : "WHERE soft_deleted_at IS NULL ";
        return jdbc.queryForList("""
            SELECT id, owner_id, scope, key, value, tags, source,
                   confidence, expires_at, soft_deleted_at, updated_at, created_at, created_by
            FROM semantic_fact
            """ + softFilter +
            "ORDER BY updated_at DESC LIMIT ?", lim);
    }

    // §23.12h 管理面：根据 id 物理删
    public boolean hardDeleteById(long id) {
        return jdbc.update("DELETE FROM semantic_fact WHERE id = ?", id) > 0;
    }

    // §23.12g 召回阈值：confidence ≥ 0.2 + 未过期 + 未软删
    private static final double MIN_CONFIDENCE = 0.2;

    private List<RankedFact> findByExactKey(String normalized, String ownerId, String scope, int limit) {
        long now = System.currentTimeMillis();
        return jdbc.query("""
            SELECT owner_id, key, value, tags, updated_at, created_at
            FROM semantic_fact
            WHERE lower(key) = lower(?) AND owner_id = ?
              AND (? IS NULL OR scope = ?)
              AND confidence >= ?
              AND (expires_at IS NULL OR expires_at > ?)
              AND soft_deleted_at IS NULL
            ORDER BY updated_at DESC LIMIT ?
            """, (rs, rowNum) -> new RankedFact(toFact(rs.getString("owner_id"),
            rs.getString("key"), rs.getString("value"), rs.getString("tags"), rs.getLong("updated_at"), rs.getLong("created_at")), 1.0),
            normalized, ownerId, scope, scope, MIN_CONFIDENCE, now, limit);
    }

    private List<RankedFact> findByFtsMatch(String normalized, String ownerId, String scope, int limit) {
        String ftsQuery = buildFtsQuery(normalized);
        if (ftsQuery.isBlank()) return List.of();
        long now = System.currentTimeMillis();
        try {
            return jdbc.query("""
                SELECT f.owner_id, f.key, f.value, f.tags, f.updated_at,
                       bm25(semantic_fact_fts) AS rank
                FROM semantic_fact f
                JOIN semantic_fact_fts fts ON fts.rowid = f.id
                WHERE semantic_fact_fts MATCH ?
                  AND f.owner_id = ?
                  AND (? IS NULL OR f.scope = ?)
                  AND f.confidence >= ?
                  AND (f.expires_at IS NULL OR f.expires_at > ?)
                  AND f.soft_deleted_at IS NULL
                ORDER BY rank LIMIT ?
                """, (rs, rowNum) -> new RankedFact(toFact(rs.getString("owner_id"),
                rs.getString("key"), rs.getString("value"), rs.getString("tags"), rs.getLong("updated_at"), rs.getLong("updated_at")),
                toRelevance(rs.getDouble("rank"))), ftsQuery, ownerId, scope, scope, MIN_CONFIDENCE, now, limit);
        } catch (Exception e) {
            log.debug("[SemanticMemory] FTS search failed: {}", e.getMessage());
            return List.of();
        }
    }

    private List<RankedFact> findByLike(String normalized, String ownerId, String scope, int limit) {
        Map<String, RankedFact> hits = new LinkedHashMap<>();
        long now = System.currentTimeMillis();
        for (String pattern : likePatterns(normalized)) {
            List<RankedFact> rows = jdbc.query("""
                SELECT owner_id, key, value, tags, updated_at, created_at
                FROM semantic_fact
                WHERE owner_id = ?
                  AND (? IS NULL OR scope = ?)
                  AND confidence >= ?
                  AND (expires_at IS NULL OR expires_at > ?)
                  AND soft_deleted_at IS NULL
                  AND (key LIKE ? ESCAPE '!' OR value LIKE ? ESCAPE '!' OR tags LIKE ? ESCAPE '!')
                ORDER BY updated_at DESC LIMIT ?
                """, (rs, rowNum) -> new RankedFact(toFact(rs.getString("owner_id"),
                rs.getString("key"), rs.getString("value"), rs.getString("tags"), rs.getLong("updated_at"), rs.getLong("created_at")), 0.45),
                ownerId, scope, scope, MIN_CONFIDENCE, now, pattern, pattern, pattern, limit);
            for (RankedFact row : rows) hits.putIfAbsent(row.fact().key(), row);
            if (hits.size() >= limit) break;
        }
        return new ArrayList<>(hits.values());
    }

    private List<RankedFact> mergeAndRank(List<RankedFact> fts, List<RankedFact> like, String query, int limit) {
        Map<String, RankedFact> merged = new LinkedHashMap<>();
        for (RankedFact hit : fts) merged.put(hit.fact().key(), hit);
        for (RankedFact hit : like) merged.merge(hit.fact().key(), hit, (a, b) -> a.bm25Score() >= b.bm25Score() ? a : b);
        Set<String> tokens = queryTokens(query);
        return merged.values().stream()
            .map(hit -> new RankedFact(hit.fact(), Math.min(1.0, hit.bm25Score() + tagBoost(hit.fact(), tokens))))
            .sorted(Comparator.comparingDouble(RankedFact::bm25Score).reversed()
                .thenComparing((RankedFact r) -> r.fact().updatedAt(), Comparator.reverseOrder()))
            .limit(limit)
            .toList();
    }

    private double tagBoost(SemanticFact fact, Set<String> queryTokens) {
        if (fact.tags() == null || fact.tags().isEmpty() || queryTokens.isEmpty()) return 0.0;
        double boost = 0.0;
        for (String tag : fact.tags()) if (queryTokens.contains(normalizeQuery(tag))) boost += 0.15;
        return Math.min(0.3, boost);
    }

    private static SemanticFact toFact(String ownerId, String key, String value, String tags, long updatedAt) {
        return toFact(ownerId, key, value, tags, updatedAt, updatedAt);
    }

    private static SemanticFact toFact(String ownerId, String key, String value, String tags,
                                       long updatedAt, long createdAt) {
        return new SemanticFact(key, value, ownerId, parseTags(tags), updatedAt, createdAt);
    }

    private static String normalizeQuery(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

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

    private static String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private static double toRelevance(double rank) { return Math.max(0.0, Math.min(1.0, 1.0 / (1.0 + Math.abs(rank)))); }
    private static String tagsCsv(Set<String> tags) { return tags == null || tags.isEmpty() ? "" : String.join(",", normalizeTags(tags)); }
    private static Set<String> parseTags(String tags) {
        if (isBlank(tags)) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String tag : tags.split(",")) if (!tag.isBlank()) out.add(tag.trim());
        return Set.copyOf(out);
    }
    private static Set<String> normalizeTags(Set<String> tags) {
        if (tags == null || tags.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String tag : tags) if (!isBlank(tag)) out.add(normalizeQuery(tag));
        return out;
    }
    private static String blankToNull(String value) { return isBlank(value) ? null : value.trim(); }
    private static String requireText(String value, String name) {
        if (isBlank(value)) throw new IllegalArgumentException(name + " required");
        return value.trim();
    }
    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
}
