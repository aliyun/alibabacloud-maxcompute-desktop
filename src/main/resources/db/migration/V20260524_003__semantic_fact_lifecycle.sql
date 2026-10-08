-- §23.12d lifecycle 列：created_by / expires_at / soft_deleted_at / audit_log_id / confidence
-- 注意：SQLite ADD COLUMN 不支持 IF NOT EXISTS；本 migration 仅在 schema_version < 3 时由
-- SemanticFactSchemaInitializer 执行，重启不重跑。

ALTER TABLE semantic_fact ADD COLUMN created_by   TEXT;
ALTER TABLE semantic_fact ADD COLUMN expires_at   INTEGER;
ALTER TABLE semantic_fact ADD COLUMN soft_deleted_at INTEGER;
ALTER TABLE semantic_fact ADD COLUMN audit_log_id TEXT;
ALTER TABLE semantic_fact ADD COLUMN confidence   REAL NOT NULL DEFAULT 1.0;

CREATE INDEX IF NOT EXISTS idx_semantic_fact_softdeleted ON semantic_fact(soft_deleted_at);
CREATE INDEX IF NOT EXISTS idx_semantic_fact_expires ON semantic_fact(expires_at);

-- §23.12f dedup audit：每次 UPSERT 命中 UNIQUE 冲突时把旧值落 history
CREATE TABLE IF NOT EXISTS semantic_fact_history (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_type   TEXT    NOT NULL,
    owner_id     TEXT    NOT NULL,
    scope        TEXT    NOT NULL,
    key          TEXT    NOT NULL,
    value        TEXT    NOT NULL,
    tags         TEXT,
    source       TEXT,
    confidence   REAL,
    created_at   INTEGER NOT NULL,
    replaced_at  INTEGER NOT NULL,
    replaced_by  TEXT
);

CREATE INDEX IF NOT EXISTS idx_semantic_fact_history_owner
    ON semantic_fact_history(owner_type, owner_id, key);
CREATE INDEX IF NOT EXISTS idx_semantic_fact_history_replaced
    ON semantic_fact_history(replaced_at DESC);
