package com.aliyun.odps.agentic.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 JSON 文件的会话存储。
 *
 * <p>该实现将会话和消息分别写入磁盘文件，适合轻量本地持久化场景。
 */
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path baseDir;
    private final Map<String, Session> sessionCache = new ConcurrentHashMap<>();

    /**
     * 使用基础目录创建存储实例。
     *
     * @param baseDir 数据根目录
     */
    public SessionStore(Path baseDir) {
        this.baseDir = baseDir;
    }

    /**
     * 初始化存储目录结构。
     *
     * @throws IOException 创建目录失败时抛出
     */
    public void init() throws IOException {
        Files.createDirectories(baseDir.resolve("sessions"));
        Files.createDirectories(baseDir.resolve("messages"));
    }

    /**
     * 保存或更新会话。
     *
     * @param session 会话对象
     */
    public void saveSession(Session session) {
        try {
            Path sessionFile = baseDir.resolve("sessions").resolve(session.id() + ".json");
            MAPPER.writeValue(sessionFile.toFile(), session);
            sessionCache.put(session.id(), session);
        } catch (IOException e) {
            log.error("Failed to save session: {}", session.id(), e);
        }
    }

    /**
     * 按 ID 加载会话。
     *
     * @param sessionId 会话 ID
     * @return 会话查询结果
     */
    public Optional<Session> loadSession(String sessionId) {
        if (sessionCache.containsKey(sessionId)) {
            return Optional.of(sessionCache.get(sessionId));
        }

        try {
            Path sessionFile = baseDir.resolve("sessions").resolve(sessionId + ".json");
            if (!Files.exists(sessionFile)) {
                return Optional.empty();
            }
            Session session = MAPPER.readValue(sessionFile.toFile(), Session.class);
            sessionCache.put(sessionId, session);
            return Optional.of(session);
        } catch (IOException e) {
            log.error("Failed to load session: {}", sessionId, e);
            return Optional.empty();
        }
    }

    /**
     * 列出全部会话，并按更新时间倒序排列。
     *
     * @return 会话列表
     */
    public List<Session> listSessions() {
        List<Session> sessions = new ArrayList<>();
        Path sessionsDir = baseDir.resolve("sessions");

        if (!Files.exists(sessionsDir)) {
            return sessions;
        }

        try {
            Files.list(sessionsDir)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        Session session = MAPPER.readValue(p.toFile(), Session.class);
                        sessions.add(session);
                    } catch (IOException e) {
                        log.warn("Failed to read session file: {}", p, e);
                    }
                });
        } catch (IOException e) {
            log.error("Failed to list sessions", e);
        }

        sessions.sort((a, b) -> b.updatedAt().compareTo(a.updatedAt()));
        return sessions;
    }

    /**
     * 保存单条消息。
     *
     * @param message 消息对象
     */
    public void saveMessage(Message message) {
        try {
            Path msgDir = baseDir.resolve("messages").resolve(message.sessionId());
            Files.createDirectories(msgDir);
            Path msgFile = msgDir.resolve(message.id() + ".json");
            MAPPER.writeValue(msgFile.toFile(), message);
        } catch (IOException e) {
            log.error("Failed to save message: {}", message.id(), e);
        }
    }

    /**
     * 加载指定会话的全部消息。
     *
     * @param sessionId 会话 ID
     * @return 按创建时间排序的消息列表
     */
    public List<Message> loadMessages(String sessionId) {
        List<Message> messages = new ArrayList<>();
        Path msgDir = baseDir.resolve("messages").resolve(sessionId);

        if (!Files.exists(msgDir)) {
            return messages;
        }

        try {
            Files.list(msgDir)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(p -> {
                    try {
                        Message msg = MAPPER.readValue(p.toFile(), Message.class);
                        messages.add(msg);
                    } catch (IOException e) {
                        log.warn("Failed to read message file: {}", p, e);
                    }
                });
        } catch (IOException e) {
            log.error("Failed to load messages for session: {}", sessionId, e);
        }

        messages.sort(Comparator.comparing(Message::createdAt));
        return messages;
    }

    /**
     * 删除会话及其全部消息。
     *
     * @param sessionId 会话 ID
     * @return 删除成功返回 {@code true}
     */
    public boolean deleteSession(String sessionId) {
        try {
            Path sessionFile = baseDir.resolve("sessions").resolve(sessionId + ".json");
            Files.deleteIfExists(sessionFile);

            Path msgDir = baseDir.resolve("messages").resolve(sessionId);
            if (Files.exists(msgDir)) {
                Files.walk(msgDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                    });
            }

            sessionCache.remove(sessionId);
            return true;
        } catch (IOException e) {
            log.error("Failed to delete session: {}", sessionId, e);
            return false;
        }
    }
}
