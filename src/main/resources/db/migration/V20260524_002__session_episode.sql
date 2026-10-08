CREATE TABLE IF NOT EXISTS session_episode (
    session_id      TEXT PRIMARY KEY,
    user_id         TEXT NOT NULL,
    workspace_id    TEXT,
    created_at      INTEGER NOT NULL,
    last_message_at INTEGER NOT NULL,
    goal_text       TEXT,
    summary_text    TEXT,
    tool_names_csv  TEXT,
    status          TEXT NOT NULL,
    indexed_at      INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_session_episode_user ON session_episode(user_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_episode_workspace ON session_episode(workspace_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_session_episode_status ON session_episode(status, last_message_at DESC);

CREATE VIRTUAL TABLE IF NOT EXISTS session_episode_fts USING fts5(
    goal_text, summary_text, tool_names_csv,
    content='session_episode',
    content_rowid='rowid',
    tokenize='unicode61'
);

CREATE TRIGGER IF NOT EXISTS session_episode_ai AFTER INSERT ON session_episode BEGIN
    INSERT INTO session_episode_fts(rowid, goal_text, summary_text, tool_names_csv)
    VALUES (new.rowid, new.goal_text, new.summary_text, new.tool_names_csv);
END;

CREATE TRIGGER IF NOT EXISTS session_episode_ad AFTER DELETE ON session_episode BEGIN
    INSERT INTO session_episode_fts(session_episode_fts, rowid, goal_text, summary_text, tool_names_csv)
    VALUES('delete', old.rowid, old.goal_text, old.summary_text, old.tool_names_csv);
END;

CREATE TRIGGER IF NOT EXISTS session_episode_au AFTER UPDATE ON session_episode BEGIN
    INSERT INTO session_episode_fts(session_episode_fts, rowid, goal_text, summary_text, tool_names_csv)
    VALUES('delete', old.rowid, old.goal_text, old.summary_text, old.tool_names_csv);
    INSERT INTO session_episode_fts(rowid, goal_text, summary_text, tool_names_csv)
    VALUES (new.rowid, new.goal_text, new.summary_text, new.tool_names_csv);
END;

INSERT INTO session_episode_fts(session_episode_fts) VALUES('rebuild');
