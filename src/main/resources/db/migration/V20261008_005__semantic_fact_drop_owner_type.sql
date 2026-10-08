-- 2026-10-08 用户裁定:user/team 分类在单机桌面形态无意义,owner_type 整体移除。
-- owner_type 参与 UNIQUE 约束,SQLite 无法直接 DROP COLUMN → 重建表。
-- 同一 (owner_id, scope, key) 若同时存在 user/team 双行,保留 updated_at 最新的一行。
-- 自愈:若上次迁移中途崩溃,重跑前先清掉半成品新表。

DROP TABLE IF EXISTS semantic_fact_new;
DROP TABLE IF EXISTS semantic_fact_history_new;

CREATE TABLE semantic_fact_new (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_id     TEXT    NOT NULL,
    scope        TEXT    NOT NULL,
    key          TEXT    NOT NULL,
    value        TEXT    NOT NULL,
    tags         TEXT,
    source       TEXT,
    updated_at   INTEGER NOT NULL,
    created_by   TEXT,
    expires_at   INTEGER,
    soft_deleted_at INTEGER,
    audit_log_id TEXT,
    confidence   REAL    NOT NULL DEFAULT 1.0,
    created_at   INTEGER NOT NULL DEFAULT 0,
    UNIQUE(owner_id, scope, key)
);

INSERT INTO semantic_fact_new(id, owner_id, scope, key, value, tags, source, updated_at,
                              created_by, expires_at, soft_deleted_at, audit_log_id, confidence, created_at)
SELECT f.id, f.owner_id, f.scope, f.key, f.value, f.tags, f.source, f.updated_at,
       f.created_by, f.expires_at, f.soft_deleted_at, f.audit_log_id, f.confidence, f.created_at
FROM semantic_fact f
WHERE NOT EXISTS (
    SELECT 1 FROM semantic_fact n
    WHERE n.owner_id = f.owner_id AND n.scope = f.scope AND n.key = f.key
      AND (n.updated_at > f.updated_at OR (n.updated_at = f.updated_at AND n.id > f.id))
);

-- 旧表的 FTS 同步触发器与索引随 DROP 一并删除
DROP TABLE semantic_fact;
ALTER TABLE semantic_fact_new RENAME TO semantic_fact;

-- 索引必须在 RENAME 之后建:SQLite 索引名全局唯一,旧索引要等旧表 DROP 才释放名字
CREATE INDEX idx_semantic_fact_owner ON semantic_fact(owner_id, scope);
CREATE INDEX idx_semantic_fact_updated ON semantic_fact(updated_at DESC);
CREATE INDEX idx_semantic_fact_softdeleted ON semantic_fact(soft_deleted_at);
CREATE INDEX idx_semantic_fact_expires ON semantic_fact(expires_at);

-- 在新表上重建 FTS 触发器(与 V20260524_001 同语义)
CREATE TRIGGER semantic_fact_ai AFTER INSERT ON semantic_fact BEGIN
    INSERT INTO semantic_fact_fts(rowid, key, value, tags) VALUES (new.id, new.key, new.value, new.tags);
END;
CREATE TRIGGER semantic_fact_ad AFTER DELETE ON semantic_fact BEGIN
    INSERT INTO semantic_fact_fts(semantic_fact_fts, rowid, key, value, tags) VALUES('delete', old.id, old.key, old.value, old.tags);
END;
CREATE TRIGGER semantic_fact_au AFTER UPDATE ON semantic_fact BEGIN
    INSERT INTO semantic_fact_fts(semantic_fact_fts, rowid, key, value, tags) VALUES('delete', old.id, old.key, old.value, old.tags);
    INSERT INTO semantic_fact_fts(rowid, key, value, tags) VALUES (new.id, new.key, new.value, new.tags);
END;
INSERT INTO semantic_fact_fts(semantic_fact_fts) VALUES('rebuild');

-- history 表同步去 owner_type(审计语义不变)
CREATE TABLE semantic_fact_history_new (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
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
INSERT INTO semantic_fact_history_new(owner_id, scope, key, value, tags, source, confidence, created_at, replaced_at, replaced_by)
SELECT owner_id, scope, key, value, tags, source, confidence, created_at, replaced_at, replaced_by
FROM semantic_fact_history;
DROP TABLE semantic_fact_history;
ALTER TABLE semantic_fact_history_new RENAME TO semantic_fact_history;
CREATE INDEX idx_semantic_fact_history_owner ON semantic_fact_history(owner_id, key);
CREATE INDEX idx_semantic_fact_history_replaced ON semantic_fact_history(replaced_at DESC);
