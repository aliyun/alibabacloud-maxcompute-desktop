package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.llm.ModelLimit;
import com.aliyun.odps.agentic.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SessionStore — JSON file-based session persistence.
 */
class SessionStoreTest {

    @TempDir
    Path tempDir;

    private SessionStore store;
    private Model model;

    @BeforeEach
    void setUp() throws Exception {
        store = new SessionStore(tempDir);
        store.init();
        model = Model.of("test", "test-model", new ModelLimit(100000, null, 4096));
    }

    @Test
    void saveAndLoadSession() {
        Session session = new Session(
            "s1", "Test Session", SessionStatus.IDLE, List.of(),
            "test-agent", model, 0.0, null, Instant.now(), Instant.now(), List.of()
        );

        store.saveSession(session);

        Session loaded = store.loadSession("s1").orElse(null);
        assertNotNull(loaded);
        assertEquals("s1", loaded.id());
        assertEquals("Test Session", loaded.title());
    }

    @Test
    void loadNonexistentSession() {
        assertFalse(store.loadSession("nonexistent").isPresent());
    }

    @Test
    void listSessions() {
        Session s1 = new Session("s1", "First", SessionStatus.IDLE, List.of(),
            "agent", model, 0.0, null, Instant.now(), Instant.now(), List.of());
        Session s2 = new Session("s2", "Second", SessionStatus.IDLE, List.of(),
            "agent", model, 0.0, null, Instant.now(), Instant.now(), List.of());

        store.saveSession(s1);
        store.saveSession(s2);

        List<Session> sessions = store.listSessions();
        assertEquals(2, sessions.size());
    }

    @Test
    void saveAndLoadMessages() {
        Message msg1 = new Message("m1", "s1", Role.USER, null, null,
            List.of(new MessagePart.TextPart("Hello")), "agent", null, null, null, null, null, null, Instant.now());
        Message msg2 = new Message("m2", "s1", Role.ASSISTANT, null, null,
            List.of(new MessagePart.TextPart("Hi there!")), "agent", null, "end-turn", null, null, null, null, Instant.now());

        store.saveMessage(msg1);
        store.saveMessage(msg2);

        List<Message> messages = store.loadMessages("s1");
        assertEquals(2, messages.size());
        assertEquals("Hello", messages.get(0).getTextContent());
        assertEquals("Hi there!", messages.get(1).getTextContent());
    }

    @Test
    void deleteSession() {
        Session session = new Session("s1", "Test", SessionStatus.IDLE, List.of(),
            "agent", model, 0.0, null, Instant.now(), Instant.now(), List.of());
        Message msg = new Message("m1", "s1", Role.USER, null, null,
            List.of(new MessagePart.TextPart("Hello")), "agent", null, null, null, null, null, null, Instant.now());

        store.saveSession(session);
        store.saveMessage(msg);

        assertTrue(store.deleteSession("s1"));
        assertFalse(store.loadSession("s1").isPresent());
        assertTrue(store.loadMessages("s1").isEmpty());
    }

    @Test
    void sessionCaching() {
        Session session = new Session("s1", "Cached", SessionStatus.IDLE, List.of(),
            "agent", model, 0.0, null, Instant.now(), Instant.now(), List.of());

        // First save goes to cache
        store.saveSession(session);

        // Load should hit cache
        Session loaded = store.loadSession("s1").orElse(null);
        assertNotNull(loaded);
        assertEquals("Cached", loaded.title());
    }
}
