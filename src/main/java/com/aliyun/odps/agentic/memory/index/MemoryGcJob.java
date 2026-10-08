package com.aliyun.odps.agentic.memory.index;

import com.aliyun.odps.agentic.storage.SqlDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;

/**
 * §23.12g 月负记忆 GC：
 * <ul>
 *   <li>SemanticMemory：物理删 soft_deleted_at &lt; NOW-30d</li>
 *   <li>EpisodicMemory hot：T+90d 未访问 → 搬到 archive 表</li>
 *   <li>EpisodicMemory archive：T+365d → 物理删</li>
 * </ul>
 * 默认每月 1 日 03:00 触发；可通过 {@code maxquery.memory.gc.cron} override。
 *
 * <p>设计原则：GC 不是"自动生成"，是 lifecycle 维护，符合用户约束。
 */
public class MemoryGcJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryGcJob.class);

    public static final long SEMANTIC_RETENTION_MS = 30L * 24 * 3600 * 1000;
    public static final long EPISODE_HOT_TTL_MS = 90L * 24 * 3600 * 1000;
    public static final long EPISODE_ARCHIVE_TTL_MS = 365L * 24 * 3600 * 1000;

    private final SqliteSemanticMemoryStore semanticStore;
    private final SqlDatabase jdbc;
    private final boolean enabled;
    private volatile Clock clock = Clock.systemUTC();

    public MemoryGcJob(SqliteSemanticMemoryStore semanticStore,
                       SqlDatabase jdbc,
                       boolean enabled) {
        this.semanticStore = semanticStore;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    /** 测试 hook：注入 mock clock。 */
    public void setClock(Clock clock) { this.clock = clock; }

    public void runGc() {
        if (!enabled) {
            log.info("[MemoryGC] disabled via maxquery.memory.gc.enabled=false");
            return;
        }
        long now = clock.millis();
        try {
            int semantic = sweepSemantic(now);
            int archived = sweepEpisodesToArchive(now);
            int purged = sweepArchiveExpired(now);
            log.info("[MemoryGC] done — semantic_purged={} episode_archived={} archive_purged={}",
                semantic, archived, purged);
        } catch (Exception e) {
            log.warn("[MemoryGC] failed: {}", e.getMessage());
        }
    }

    public int sweepSemantic(long nowMillis) {
        return semanticStore.hardDeleteSoftDeletedBefore(nowMillis - SEMANTIC_RETENTION_MS);
    }

    public int sweepEpisodesToArchive(long nowMillis) {
        long cutoff = nowMillis - EPISODE_HOT_TTL_MS;
        try {
            int copied = jdbc.update("""
                INSERT OR IGNORE INTO session_episode_archive
                    (session_id, user_id, workspace_id, created_at, last_message_at,
                     goal_text, summary_text, tool_names_csv, status, summary_quality,
                     indexed_at, archived_at)
                SELECT session_id, user_id, workspace_id, created_at, last_message_at,
                       goal_text, summary_text, tool_names_csv, status,
                       COALESCE(summary_quality, 'empty'),
                       indexed_at, ?
                FROM session_episode
                WHERE last_message_at < ?
                  AND (archived_at IS NULL)
                """, nowMillis, cutoff);
            jdbc.update("DELETE FROM session_episode WHERE last_message_at < ?", cutoff);
            return copied;
        } catch (Exception e) {
            log.warn("[MemoryGC] sweep hot→archive failed: {}", e.getMessage());
            return 0;
        }
    }

    public int sweepArchiveExpired(long nowMillis) {
        long cutoff = nowMillis - EPISODE_ARCHIVE_TTL_MS;
        try {
            return jdbc.update("DELETE FROM session_episode_archive WHERE archived_at < ?", cutoff);
        } catch (Exception e) {
            log.warn("[MemoryGC] sweep archive expired failed: {}", e.getMessage());
            return 0;
        }
    }
}
