package com.aliyun.odps.agentic.example.chat;

import com.aliyun.odps.agentic.HarnessEngine;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;
import com.aliyun.odps.agentic.permission.PermissionReply;
import com.aliyun.odps.agentic.permission.PermissionRequest;
import com.aliyun.odps.agentic.session.AgentEvent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST controller for the chat UI.
 * Manages sessions and provides both synchronous and SSE streaming endpoints.
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HarnessEngine engine;
    private final AgentDef agent;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /** In-memory session store: sessionId -> Session */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** Track message counts per session */
    private final ConcurrentHashMap<String, Integer> messageCounts = new ConcurrentHashMap<>();

    public ChatController(HarnessEngine engine, AgentDef agent) {
        this.engine = engine;
        this.agent = agent;
    }

    // -- Session management --------------------------------------------------

    @GetMapping("/sessions")
    public List<ChatMessage.SessionInfo> listSessions() {
        List<ChatMessage.SessionInfo> result = new ArrayList<>();
        for (var entry : sessions.entrySet()) {
            Session s = entry.getValue();
            int count = messageCounts.getOrDefault(entry.getKey(), 0);
            result.add(new ChatMessage.SessionInfo(
                s.id(),
                s.title() != null ? s.title() : "New Chat",
                s.createdAt(),
                count
            ));
        }
        result.sort(Comparator.comparing(ChatMessage.SessionInfo::getCreatedAt).reversed());
        return result;
    }

    @PostMapping("/sessions")
    public ChatMessage.SessionInfo createSession() {
        Session session = engine.createSession(agent);
        sessions.put(session.id(), session);
        messageCounts.put(session.id(), 0);
        return new ChatMessage.SessionInfo(
            session.id(),
            "New Chat",
            session.createdAt(),
            0
        );
    }

    // -- Messages endpoint -----------------------------------------------------

    @GetMapping("/sessions/{sessionId}/messages")
    public List<Map<String, Object>> getSessionMessages(@PathVariable String sessionId) {
        return engine.getMessages(sessionId).stream()
            .map(m -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", m.id());
                map.put("role", m.role().toString().toLowerCase());
                map.put("content", m.getTextContent() != null ? m.getTextContent() : "");
                map.put("createdAt", m.createdAt().toString());
                return map;
            })
            .toList();
    }

    // -- Permission endpoints (faithful to OpenCode GET/POST /permission) ----

    @GetMapping("/permissions")
    public List<Map<String, Object>> listPermissions(@RequestParam(required = false) String sessionId) {
        var pending = sessionId != null
            ? engine.getPermissionService().listPending(sessionId)
            : engine.getPermissionService().listPending();
        return pending.stream().map(r -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", r.id());
            map.put("sessionId", r.sessionId());
            map.put("tool", r.tool());
            map.put("permission", r.permission());
            map.put("target", r.target());
            map.put("description", r.description());
            return map;
        }).toList();
    }

    @PostMapping("/permissions/{requestId}/reply")
    public Map<String, Object> replyPermission(@PathVariable String requestId,
                                                @RequestBody Map<String, String> body) {
        String replyStr = body.getOrDefault("reply", "reject");
        PermissionReply reply = switch (replyStr.toLowerCase()) {
            case "once" -> PermissionReply.ONCE;
            case "always" -> PermissionReply.ALWAYS;
            default -> PermissionReply.REJECT;
        };
        engine.getPermissionService().reply(requestId, reply);
        return Map.of("status", "ok", "requestId", requestId, "reply", reply.name());
    }

    // -- Synchronous chat ----------------------------------------------------

    @PostMapping("/chat")
    public ChatMessage.Response chat(@RequestBody ChatMessage.Request request) {
        String sessionId = request.getSessionId();
        Session session = getOrCreateSession(sessionId);

        List<ChatMessage.ToolCallInfo> toolCalls = new ArrayList<>();

        Message result = engine.runStreaming(session, agent, request.getMessage(), event -> {
            if (event instanceof AgentEvent.ToolCallCompleted tc) {
                toolCalls.add(new ChatMessage.ToolCallInfo(
                    tc.tool(), tc.callId(), tc.output(), false));
            } else if (event instanceof AgentEvent.ToolCallFailed tc) {
                toolCalls.add(new ChatMessage.ToolCallInfo(
                    tc.tool(), tc.callId(), tc.error(), true));
            }
        });

        messageCounts.merge(session.id(), 2, Integer::sum); // user + assistant
        updateSessionTitle(session, request.getMessage());

        return new ChatMessage.Response(session.id(), result.getTextContent(), toolCalls);
    }

    // -- SSE streaming -------------------------------------------------------

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatMessage.Request request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        String sessionId = request.getSessionId();
        Session session = getOrCreateSession(sessionId);

        // Track emitter lifecycle to avoid sending to closed connections
        final boolean[] completed = {false};
        emitter.onCompletion(() -> completed[0] = true);
        emitter.onTimeout(() -> completed[0] = true);
        emitter.onError(e -> completed[0] = true);

        executor.submit(() -> {
            try {
                Message result = engine.runStreaming(session, agent, request.getMessage(), event -> {
                    if (completed[0]) return;
                    try {
                        switch (event) {
                            case AgentEvent.TextDelta td -> {
                                emitter.send(SseEmitter.event()
                                    .name("text-delta")
                                    .data(MAPPER.writeValueAsString(Map.of(
                                        "type", "text-delta", "delta", td.delta()))));
                            }
                            case AgentEvent.ToolCallStarted tc -> {
                                emitter.send(SseEmitter.event()
                                    .name("tool-call")
                                    .data(MAPPER.writeValueAsString(Map.of(
                                        "type", "tool-call", "tool", tc.tool(), "callId", tc.callId()))));
                            }
                            case AgentEvent.ToolCallCompleted tc -> {
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("type", "tool-result");
                                data.put("tool", tc.tool());
                                data.put("callId", tc.callId());
                                data.put("title", tc.title());
                                data.put("output", truncateOutput(tc.output()));
                                data.put("error", false);
                                emitter.send(SseEmitter.event()
                                    .name("tool-result")
                                    .data(MAPPER.writeValueAsString(data)));
                            }
                            case AgentEvent.ToolCallFailed tc -> {
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("type", "tool-result");
                                data.put("tool", tc.tool());
                                data.put("callId", tc.callId());
                                data.put("output", tc.error());
                                data.put("error", true);
                                emitter.send(SseEmitter.event()
                                    .name("tool-result")
                                    .data(MAPPER.writeValueAsString(data)));
                            }
                            case AgentEvent.PermissionAsked pa -> {
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("type", "permission-asked");
                                data.put("id", pa.request().id());
                                data.put("sessionId", pa.request().sessionId());
                                data.put("tool", pa.request().tool());
                                data.put("permission", pa.request().permission());
                                data.put("target", pa.request().target());
                                data.put("description", pa.request().description());
                                emitter.send(SseEmitter.event()
                                    .name("permission-asked")
                                    .data(MAPPER.writeValueAsString(data)));
                            }
                            case AgentEvent.ToolCallProgress tp -> {
                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("type", "tool-progress");
                                data.put("tool", tp.tool());
                                data.put("callId", tp.callId());
                                data.put("title", tp.title());
                                if (tp.metadata() != null) data.put("metadata", tp.metadata());
                                emitter.send(SseEmitter.event()
                                    .name("tool-progress")
                                    .data(MAPPER.writeValueAsString(data)));
                            }
                            case AgentEvent.Error err -> {
                                emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data(MAPPER.writeValueAsString(Map.of(
                                        "type", "error", "message", err.message()))));
                            }
                            default -> {}
                        }
                    } catch (Exception e) {
                        if (!completed[0]) {
                            log.warn("Failed to send SSE event: {}", e.getMessage());
                        }
                        completed[0] = true;
                    }
                });

                if (!completed[0]) {
                    Map<String, Object> doneData = new LinkedHashMap<>();
                    doneData.put("type", "done");
                    doneData.put("sessionId", session.id());
                    doneData.put("content", result.getTextContent());
                    emitter.send(SseEmitter.event()
                        .name("done")
                        .data(MAPPER.writeValueAsString(doneData)));
                    emitter.complete();
                }

                messageCounts.merge(session.id(), 2, Integer::sum);
                updateSessionTitle(session, request.getMessage());

            } catch (Exception e) {
                if (!completed[0]) {
                    log.error("Streaming error", e);
                    try {
                        emitter.send(SseEmitter.event()
                            .name("error")
                            .data(MAPPER.writeValueAsString(Map.of(
                                "type", "error",
                                "message", e.getMessage() != null ? e.getMessage() : "Unknown error"))));
                    } catch (Exception ignored) {}
                    emitter.completeWithError(e);
                }
            }
        });

        return emitter;
    }

    // -- Helpers -------------------------------------------------------------

    private Session getOrCreateSession(String sessionId) {
        if (sessionId != null && sessions.containsKey(sessionId)) {
            return sessions.get(sessionId);
        }
        Session session = engine.createSession(agent);
        sessions.put(session.id(), session);
        messageCounts.put(session.id(), 0);
        return session;
    }

    private void updateSessionTitle(Session session, String firstMessage) {
        if (session.title() == null || "New Chat".equals(session.title())) {
            String title = firstMessage.length() > 60
                ? firstMessage.substring(0, 57) + "..."
                : firstMessage;
            sessions.put(session.id(), session.withTitle(title));
        }
    }

    private String truncateOutput(String output) {
        if (output == null) return "";
        if (output.length() > 2000) {
            return output.substring(0, 2000) + "\n... (truncated)";
        }
        return output;
    }
}
