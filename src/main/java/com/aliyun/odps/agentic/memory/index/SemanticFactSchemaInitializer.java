package com.aliyun.odps.agentic.memory.index;


import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class SemanticFactSchemaInitializer {
    private static final String MEMORY_SCHEMA_VERSION_TABLE = "maxquery_memory_schema_version";

    private final DataSource dataSource;

    public SemanticFactSchemaInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void initializeSchema() {
        try {
            initializeSchema(dataSource, "semantic_fact", 1, "db/migration/V20260524_001__semantic_fact.sql");
            // §23.12d lifecycle 列 + history 表（仅 version < 3 时应用，ALTER 不可重跑）
            applyVersionedMigration(dataSource, "semantic_fact", 3,
                "db/migration/V20260524_003__semantic_fact_lifecycle.sql");
            // M0 评审 #9:created_at 列补缺（前端已读该字段多年恒空）
            applyVersionedMigration(dataSource, "semantic_fact", 4,
                "db/migration/V20261006_004__semantic_fact_created_at.sql");
            // 2026-10-08 用户裁定:移除 user/team 分类,owner_type 列重建删除
            applyVersionedMigration(dataSource, "semantic_fact", 5,
                "db/migration/V20261008_005__semantic_fact_drop_owner_type.sql");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to init semantic_fact schema", e);
        }
    }

    public static void initializeSchema(DataSource dataSource, String schemaKey, int latestVersion, String migrationResource)
            throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ensureMemorySchemaVersionTable(conn);
            int current = readCurrentVersion(conn, schemaKey);
            // The memory tables live in connections.db together with saved connections,
            // preferences, and AI config. Keep this migration idempotent and memory-scoped:
            // rerunning v1 repairs half-applied upgrades without touching unrelated tables.
            applyMigration(conn, migrationResource);
            if (current < latestVersion) writeVersion(conn, schemaKey, latestVersion);
        }
    }

    /**
     * 增量 migration：仅当 {@code currentVersion < targetVersion} 时应用一次。
     * 用于 ALTER TABLE ADD COLUMN 这类不可重跑的语句。
     */
    public static void applyVersionedMigration(DataSource dataSource, String schemaKey, int targetVersion,
                                                String migrationResource) throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            ensureMemorySchemaVersionTable(conn);
            int current = readCurrentVersion(conn, schemaKey);
            if (current >= targetVersion) return;
            applyMigration(conn, migrationResource);
            writeVersion(conn, schemaKey, targetVersion);
        }
    }

    public static void applyMigration(Connection conn, String resourcePath) throws Exception {
        String sql;
        try (var input = SemanticFactSchemaInitializer.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (input == null) throw new java.io.FileNotFoundException(resourcePath);
            sql = new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        for (String statement : splitSql(sql)) {
            if (!statement.isBlank()) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(statement);
                }
            }
        }
    }

    private static void ensureMemorySchemaVersionTable(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + MEMORY_SCHEMA_VERSION_TABLE + " ("
                + "schema_key TEXT PRIMARY KEY, "
                + "version INTEGER NOT NULL, "
                + "updated_at INTEGER NOT NULL"
                + ")");
        }
    }

    private static int readCurrentVersion(Connection conn, String schemaKey) {
        try (var ps = conn.prepareStatement("SELECT version FROM " + MEMORY_SCHEMA_VERSION_TABLE + " WHERE schema_key = ?")) {
            ps.setString(1, schemaKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static void writeVersion(Connection conn, String schemaKey, int version) throws SQLException {
        try (var ps = conn.prepareStatement("INSERT OR REPLACE INTO " + MEMORY_SCHEMA_VERSION_TABLE
                + " (schema_key, version, updated_at) VALUES (?, ?, ?)")) {
            ps.setString(1, schemaKey);
            ps.setInt(2, version);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public static List<String> splitSql(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inTrigger = false;
        for (String rawLine : sql.split("\\R")) {
            String line = stripLineComment(rawLine);
            if (line.isBlank()) continue;
            String trimmed = line.trim();
            if (!inTrigger && trimmed.toUpperCase(Locale.ROOT).startsWith("CREATE TRIGGER")) inTrigger = true;
            current.append(line).append('\n');
            if (inTrigger) {
                if (trimmed.equalsIgnoreCase("END;") || trimmed.toUpperCase(Locale.ROOT).endsWith("END;")) {
                    statements.add(current.toString().trim());
                    current.setLength(0);
                    inTrigger = false;
                }
            } else if (trimmed.endsWith(";")) {
                statements.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) statements.add(current.toString().trim());
        return statements;
    }

    private static String stripLineComment(String line) {
        int idx = line.indexOf("--");
        return idx >= 0 ? line.substring(0, idx) : line;
    }
}
