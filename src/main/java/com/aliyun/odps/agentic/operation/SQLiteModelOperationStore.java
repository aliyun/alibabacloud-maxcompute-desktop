package com.aliyun.odps.agentic.operation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Durable, payload-free lifecycle events for non-conversational model operations. */
public final class SQLiteModelOperationStore implements Consumer<ModelOperationRunner.Event>, AutoCloseable {
    public record StoredEvent(long sequence, long occurredAtMs, ModelOperationRunner.Event event) {}

    private final Connection connection;

    public SQLiteModelOperationStore(Path dbPath) {
        Objects.requireNonNull(dbPath, "dbPath");
        try {
            Path parent = dbPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("""
                    CREATE TABLE IF NOT EXISTS model_operation_event (
                        sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                        operation_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        modality TEXT NOT NULL,
                        phase TEXT NOT NULL,
                        elapsed_ms INTEGER NOT NULL,
                        occurred_at_ms INTEGER NOT NULL,
                        detail TEXT
                    )
                    """);
                statement.execute("""
                    CREATE INDEX IF NOT EXISTS idx_model_operation_event_id
                        ON model_operation_event (operation_id, sequence)
                    """);
            }
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to initialize model operation store", e);
        }
    }

    @Override
    public synchronized void accept(ModelOperationRunner.Event event) {
        Objects.requireNonNull(event, "event");
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO model_operation_event
                (operation_id, name, modality, phase, elapsed_ms, occurred_at_ms, detail)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """)) {
            statement.setString(1, event.operationId());
            statement.setString(2, safeOperationName(event.name()));
            statement.setString(3, event.modality().name());
            statement.setString(4, event.phase().name());
            statement.setLong(5, event.elapsedMs());
            statement.setLong(6, System.currentTimeMillis());
            statement.setString(7, safeDetail(event.detail()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save model operation event", e);
        }
    }

    /** All events for one operation, in lifecycle order. */
    public synchronized List<StoredEvent> eventsFor(String operationId) {
        Objects.requireNonNull(operationId, "operationId");
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT sequence, occurred_at_ms, operation_id, name, modality, phase, elapsed_ms, detail
            FROM model_operation_event WHERE operation_id = ? ORDER BY sequence
            """)) {
            statement.setString(1, operationId);
            try (ResultSet rows = statement.executeQuery()) {
                return read(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read model operation events", e);
        }
    }

    /** Most recently updated operations, one last event per operation. */
    public synchronized List<StoredEvent> latest(int limit) {
        if (limit <= 0) return List.of();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT e.sequence, e.occurred_at_ms, e.operation_id, e.name, e.modality,
                   e.phase, e.elapsed_ms, e.detail
            FROM model_operation_event e
            WHERE e.sequence IN (
                SELECT MAX(sequence) FROM model_operation_event GROUP BY operation_id
            )
            ORDER BY e.sequence DESC LIMIT ?
            """)) {
            statement.setInt(1, limit);
            try (ResultSet rows = statement.executeQuery()) {
                return read(rows);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read latest model operations", e);
        }
    }

    private static List<StoredEvent> read(ResultSet rows) throws SQLException {
        List<StoredEvent> events = new ArrayList<>();
        while (rows.next()) {
            events.add(new StoredEvent(rows.getLong("sequence"), rows.getLong("occurred_at_ms"),
                new ModelOperationRunner.Event(rows.getString("operation_id"), rows.getString("name"),
                    ModelOperationRunner.Modality.valueOf(rows.getString("modality")),
                    ModelOperationRunner.Phase.valueOf(rows.getString("phase")),
                    rows.getLong("elapsed_ms"), rows.getString("detail"))));
        }
        return events;
    }

    private static String safeOperationName(String name) {
        if (name == null || name.length() > 128 || !name.matches("[A-Za-z0-9_.-]+")) {
            return "unknown";
        }
        return name;
    }

    /** Only SDK-owned stage markers are safe to persist; live progress remains unchanged. */
    private static String safeDetail(String detail) {
        if (detail == null) return null;
        if (detail.equals("RUNNING") || detail.equals("session.updated")) return detail;
        if (detail.matches("slides=[0-9]{1,4}")) return detail;
        return null;
    }

    @Override
    public synchronized void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close model operation store", e);
        }
    }
}
