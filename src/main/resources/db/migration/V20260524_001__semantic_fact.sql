CREATE TABLE IF NOT EXISTS semantic_fact (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_type  TEXT    NOT NULL,
    owner_id    TEXT    NOT NULL,
    scope       TEXT    NOT NULL,
    key         TEXT    NOT NULL,
    value       TEXT    NOT NULL,
    tags        TEXT,
    source      TEXT,
    updated_at  INTEGER NOT NULL,
    UNIQUE(owner_type, owner_id, scope, key)
);

CREATE INDEX IF NOT EXISTS idx_semantic_fact_owner ON semantic_fact(owner_type, owner_id, scope);
CREATE INDEX IF NOT EXISTS idx_semantic_fact_updated ON semantic_fact(updated_at DESC);

CREATE VIRTUAL TABLE IF NOT EXISTS semantic_fact_fts USING fts5(
    key, value, tags,
    content='semantic_fact',
    content_rowid='id',
    tokenize='unicode61'
);

CREATE TRIGGER IF NOT EXISTS semantic_fact_ai AFTER INSERT ON semantic_fact BEGIN
    INSERT INTO semantic_fact_fts(rowid, key, value, tags) VALUES (new.id, new.key, new.value, new.tags);
END;

CREATE TRIGGER IF NOT EXISTS semantic_fact_ad AFTER DELETE ON semantic_fact BEGIN
    INSERT INTO semantic_fact_fts(semantic_fact_fts, rowid, key, value, tags) VALUES('delete', old.id, old.key, old.value, old.tags);
END;

CREATE TRIGGER IF NOT EXISTS semantic_fact_au AFTER UPDATE ON semantic_fact BEGIN
    INSERT INTO semantic_fact_fts(semantic_fact_fts, rowid, key, value, tags) VALUES('delete', old.id, old.key, old.value, old.tags);
    INSERT INTO semantic_fact_fts(rowid, key, value, tags) VALUES (new.id, new.key, new.value, new.tags);
END;

INSERT INTO semantic_fact_fts(semantic_fact_fts) VALUES('rebuild');
