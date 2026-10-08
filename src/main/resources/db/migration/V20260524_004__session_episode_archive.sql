-- §23.12d 冷归档表（同 session_episode schema，但无 FTS5）+ summary_quality 列
-- 仅在 schema_version < 2 时由 SessionEpisodeSchemaInitializer 执行。

ALTER TABLE session_episode ADD COLUMN summary_quality TEXT NOT NULL DEFAULT 'empty';
ALTER TABLE session_episode ADD COLUMN archived_at INTEGER;

CREATE TABLE IF NOT EXISTS session_episode_archive (
    session_id      TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    workspace_id    TEXT,
    created_at      INTEGER NOT NULL,
    last_message_at INTEGER NOT NULL,
    goal_text       TEXT,
    summary_text    TEXT,
    tool_names_csv  TEXT,
    status          TEXT NOT NULL,
    summary_quality TEXT NOT NULL DEFAULT 'empty',
    indexed_at      INTEGER NOT NULL,
    archived_at     INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_session_episode_archive_user
    ON session_episode_archive(user_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_episode_archive_archived
    ON session_episode_archive(archived_at DESC);
