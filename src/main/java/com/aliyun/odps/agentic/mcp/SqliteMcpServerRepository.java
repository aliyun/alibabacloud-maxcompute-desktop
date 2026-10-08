package com.aliyun.odps.agentic.mcp;
import com.aliyun.odps.agentic.mcp.ExternalServerDef;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aliyun.odps.agentic.storage.SqlDatabase;
import com.aliyun.odps.agentic.storage.SqlDatabase.RowMapper;



import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persists MCP server configurations to SQLite.
 * Enables auto-reconnect on application restart.
 */
public class SqliteMcpServerRepository implements ManagedMcpManager.ServerRepository {

    private static final Logger log = LoggerFactory.getLogger(SqliteMcpServerRepository.class);

    private final SqlDatabase jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SqliteMcpServerRepository(SqlDatabase jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void init() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS mcp_servers (
                name TEXT PRIMARY KEY,
                transport_type TEXT NOT NULL DEFAULT 'STDIO',
                command TEXT,
                args_json TEXT,
                env_json TEXT,
                url TEXT,
                headers_json TEXT,
                oauth_json TEXT,
                enabled INTEGER NOT NULL DEFAULT 1,
                timeout_ms INTEGER,
                auto_connect INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """);
        log.info("[SqliteMcpServerRepository] Table mcp_servers initialized");
    }

    public void save(ExternalServerDef def) {
        String existing = null;
        try {
            List<String> names = jdbcTemplate.query(
                "SELECT name FROM mcp_servers WHERE name = ?", (rs,index) -> rs.getString(1), def.getName());
            if (!names.isEmpty()) existing = names.get(0);
        } catch (Exception ignored) {}

        if (existing != null) {
            update(def);
            return;
        }

        jdbcTemplate.update("""
            INSERT INTO mcp_servers (name, transport_type, command, args_json, env_json, url, headers_json, oauth_json, enabled, timeout_ms, auto_connect)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            def.getName(),
            def.getTransportType().name(),
            def.getCommand(),
            toJson(def.getArgs()),
            toJson(def.getEnv()),
            def.getUrl(),
            toJson(def.getHeaders()),
            toJson(def.getOauth()),
            def.isEnabled() ? 1 : 0,
            def.getTimeoutMs(),
            def.isAutoConnect() ? 1 : 0
        );
        log.info("[SqliteMcpServerRepository] Saved MCP server config: {}", def.getName());
    }

    public void update(ExternalServerDef def) {
        jdbcTemplate.update("""
            UPDATE mcp_servers SET transport_type = ?, command = ?, args_json = ?, env_json = ?,
                url = ?, headers_json = ?, oauth_json = ?, enabled = ?, timeout_ms = ?, auto_connect = ?,
                updated_at = strftime('%s', 'now')
            WHERE name = ?
            """,
            def.getTransportType().name(),
            def.getCommand(),
            toJson(def.getArgs()),
            toJson(def.getEnv()),
            def.getUrl(),
            toJson(def.getHeaders()),
            toJson(def.getOauth()),
            def.isEnabled() ? 1 : 0,
            def.getTimeoutMs(),
            def.isAutoConnect() ? 1 : 0,
            def.getName()
        );
        log.info("[SqliteMcpServerRepository] Updated MCP server config: {}", def.getName());
    }

    public void delete(String name) {
        jdbcTemplate.update("DELETE FROM mcp_servers WHERE name = ?", name);
        log.info("[SqliteMcpServerRepository] Deleted MCP server config: {}", name);
    }

    public Optional<ExternalServerDef> findByName(String name) {
        List<ExternalServerDef> results = jdbcTemplate.query(
            "SELECT * FROM mcp_servers WHERE name = ?", ROW_MAPPER, name);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public List<ExternalServerDef> findAll() {
        return jdbcTemplate.query("SELECT * FROM mcp_servers ORDER BY created_at", ROW_MAPPER);
    }

    public List<ExternalServerDef> findAutoConnectEnabled() {
        return jdbcTemplate.query(
            "SELECT * FROM mcp_servers WHERE enabled = 1 AND auto_connect = 1 ORDER BY created_at",
            ROW_MAPPER);
    }

    public void setEnabled(String name, boolean enabled) {
        jdbcTemplate.update(
            "UPDATE mcp_servers SET enabled = ?, updated_at = strftime('%s', 'now') WHERE name = ?",
            enabled ? 1 : 0, name);
    }

    private String toJson(Object obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("[SqliteMcpServerRepository] Failed to serialize: {}", e.getMessage());
            return null;
        }
    }

    private final RowMapper<ExternalServerDef> ROW_MAPPER = (rs, rowNum) -> {
        ExternalServerDef def = new ExternalServerDef();
        def.setName(rs.getString("name"));
        def.setTransportType(ExternalServerDef.TransportType.valueOf(
            rs.getString("transport_type")));
        def.setCommand(rs.getString("command"));
        def.setArgs(fromJson(rs.getString("args_json"), new TypeReference<List<String>>() {}));
        def.setEnv(fromJson(rs.getString("env_json"), new TypeReference<Map<String, String>>() {}));
        def.setUrl(rs.getString("url"));
        def.setHeaders(fromJson(rs.getString("headers_json"), new TypeReference<Map<String, String>>() {}));
        def.setOauth(fromJson(rs.getString("oauth_json"), new TypeReference<ExternalServerDef.OAuthConfig>() {}));
        def.setEnabled(rs.getInt("enabled") == 1);
        Integer timeout = rs.getObject("timeout_ms") != null ? rs.getInt("timeout_ms") : null;
        if (timeout != null && timeout > 0) def.setTimeoutMs(timeout);
        def.setAutoConnect(rs.getInt("auto_connect") == 1);
        return def;
    };

    private <T> T fromJson(String json, TypeReference<T> typeRef) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("[SqliteMcpServerRepository] Failed to deserialize: {}", e.getMessage());
            return null;
        }
    }
}
