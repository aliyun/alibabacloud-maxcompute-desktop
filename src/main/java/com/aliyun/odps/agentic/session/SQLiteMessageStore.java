package com.aliyun.odps.agentic.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.aliyun.odps.agentic.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 基于 SQLite 的 {@link MessageStore} 实现。
 *
 * <p>该实现将每条 {@link Message} 作为 JSON 文本保存到单表中，并通过同步方法保证线程安全。
 */
public class SQLiteMessageStore implements MessageStore {

    private static final Logger log = LoggerFactory.getLogger(SQLiteMessageStore.class);
    private static final String UPSERT_MESSAGE_SQL = """
        INSERT INTO message (id, session_id, time_created, data) VALUES (?, ?, ?, ?)
        ON CONFLICT(id, session_id) DO UPDATE SET
            time_created = excluded.time_created, data = excluded.data
        """;

    private final Connection connection;
    private final ObjectMapper objectMapper;
    private final Set<String> ownedSessions = new HashSet<>();

    /**
     * 使用指定数据库文件创建 SQLite 消息存储。
     *
     * @param dbPath SQLite 数据库文件路径
     */
    public SQLiteMessageStore(String dbPath) {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());

        try {
            Path path = Path.of(dbPath);
            Files.createDirectories(path.getParent());

            String url = "jdbc:sqlite:" + dbPath;
            this.connection = DriverManager.getConnection(url);

            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
            }

            createTables();
            log.info("SQLiteMessageStore initialized at {}", dbPath);
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to initialize SQLiteMessageStore", e);
        }
    }

    /**
     * 创建所需数据表和索引。
     *
     * @throws SQLException 执行 SQL 失败时抛出
     */
    private void createTables() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS message (
                    id TEXT NOT NULL,
                    session_id TEXT NOT NULL,
                    time_created TEXT,
                    data TEXT NOT NULL,
                    PRIMARY KEY (id, session_id)
                )
                """);
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_message_session
                    ON message (session_id)
                """);
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS owned_session (
                    session_id TEXT PRIMARY KEY
                )
                """);
        }
    }

    /**
     * 更新或插入一条消息。
     *
     * @param sessionId 会话 ID
     * @param message 消息对象
     */
    @Override
    public synchronized void updateMessage(String sessionId, Message message) {
        // REPLACE deletes and reinserts the row, changing rowid every time a streamed
        // assistant message is updated. Preserve rowid as a stable tie-breaker when
        // two messages share createdAt; createdAt remains primary so mid-call steering
        // sorts after the assistant that was created before the steer arrived.
        try (PreparedStatement ps = connection.prepareStatement(UPSERT_MESSAGE_SQL)) {
            bindMessage(ps, sessionId, message);
            ps.executeUpdate();
            markOwned(sessionId);
            log.debug("Updated message id={} session={}", message.id(), sessionId);
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException("Failed to update message", e);
        }
    }

    /** Persist a complete batch and session ownership together, or leave both untouched. */
    @Override
    public synchronized void updateMessages(String sessionId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;
        persistBatch(sessionId, messages, false);
    }

    /** Atomically replace the full history, retaining ownership even for an empty history. */
    public synchronized void replaceMessages(String sessionId, List<Message> messages) {
        if (messages == null) throw new IllegalArgumentException("Replacement history cannot be null");
        persistBatch(sessionId, messages, true);
    }

    private void persistBatch(String sessionId, List<Message> messages, boolean replace) {
        try {
            connection.setAutoCommit(false);
            if (replace) {
                try (PreparedStatement ps = connection.prepareStatement(
                        "DELETE FROM message WHERE session_id = ?")) {
                    ps.setString(1, sessionId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = connection.prepareStatement(UPSERT_MESSAGE_SQL)) {
                for (Message message : messages) {
                    bindMessage(ps, sessionId, message);
                    ps.executeUpdate();
                }
            }
            markOwned(sessionId);
            connection.commit();
        } catch (SQLException | JsonProcessingException | RuntimeException e) {
            ownedSessions.remove(sessionId);
            rollback();
            throw new RuntimeException("Failed to update message batch for session " + sessionId, e);
        } finally {
            restoreAutoCommit();
        }
    }

    private void bindMessage(PreparedStatement ps, String sessionId, Message message)
            throws SQLException, JsonProcessingException {
        String json = objectMapper.writeValueAsString(message);
        ps.setString(1, message.id());
        ps.setString(2, sessionId);
        ps.setString(3, message.createdAt() != null ? message.createdAt().toString() : null);
        ps.setString(4, json);
    }

    /**
     * 获取指定会话的全部消息。
     *
     * @param sessionId 会话 ID
     * @return 按创建时间、首次写入顺序排列的消息列表；更新不改变同时间消息的相对位置
     */
    @Override
    public synchronized List<Message> getMessages(String sessionId) {
        String sql = "SELECT data FROM message WHERE session_id = ? ORDER BY rowid ASC";
        List<Message> messages = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String json = rs.getString("data");
                    Message msg = objectMapper.readValue(json, Message.class);
                    messages.add(msg);
                }
            }
        } catch (SQLException | IOException e) {
            throw new RuntimeException("Failed to get messages for session " + sessionId, e);
        }
        // Instant.toString() has variable fractional precision: .950001Z sorts
        // before .950Z as TEXT although it is later. Sort exact instants after
        // decoding; Java's stable sort retains rowid order for equal timestamps
        // and also fixes existing databases without rewriting message history.
        messages.sort(Comparator.comparing(Message::createdAt,
            Comparator.nullsFirst(Comparator.naturalOrder())));
        return messages;
    }

    /** An owned session stays owned when its last message is removed. */
    public synchronized boolean isSessionOwned(String sessionId) {
        if (ownedSessions.contains(sessionId)) return true;
        String sql = "SELECT 1 FROM owned_session WHERE session_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sessionId);
            try (ResultSet rs = ps.executeQuery()) {
                boolean owned = rs.next();
                if (owned) ownedSessions.add(sessionId);
                return owned;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Failed to check session ownership", e);
        }
    }

    private void markOwned(String sessionId) throws SQLException {
        if (ownedSessions.contains(sessionId)) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT OR IGNORE INTO owned_session (session_id) VALUES (?)")) {
            ps.setString(1, sessionId);
            ps.executeUpdate();
        }
        ownedSessions.add(sessionId);
    }

    /**
     * 删除指定消息。
     *
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     */
    @Override
    public synchronized void removeMessage(String sessionId, String messageId) {
        String sql = "DELETE FROM message WHERE id = ? AND session_id = ?";
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, messageId);
                ps.setString(2, sessionId);
                ps.executeUpdate();
            }
            markOwned(sessionId);
            connection.commit();
            log.debug("Removed message id={} session={}", messageId, sessionId);
        } catch (SQLException e) {
            ownedSessions.remove(sessionId);
            rollback();
            throw new RuntimeException("Failed to remove message", e);
        } finally {
            restoreAutoCommit();
        }
    }

    /**
     * 清空指定会话的全部消息。
     *
     * @param sessionId 会话 ID
     */
    @Override
    public synchronized void clear(String sessionId) {
        String sql = "DELETE FROM message WHERE session_id = ?";
        try {
            connection.setAutoCommit(false);
            int deleted;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, sessionId);
                deleted = ps.executeUpdate();
            }
            markOwned(sessionId);
            connection.commit();
            log.info("Cleared {} messages for session {}", deleted, sessionId);
        } catch (SQLException e) {
            ownedSessions.remove(sessionId);
            rollback();
            throw new RuntimeException("Failed to clear messages for session " + sessionId, e);
        } finally {
            restoreAutoCommit();
        }
    }

    private void rollback() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to roll back message mutation", e);
        }
    }

    private void restoreAutoCommit() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to restore SQLite auto-commit", e);
        }
    }
}
