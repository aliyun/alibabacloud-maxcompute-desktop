package com.aliyun.odps.agentic.memory.index;


import com.aliyun.odps.agentic.memory.context.MemorySessionSource.SessionView;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class SessionEpisodeIndexer {
    public static final String DEFAULT_USER_ID = "local-user";
    private final SessionEpisodeSource source;

    public SessionEpisodeIndexer(SessionEpisodeSource source) { this.source=source; }

    public Optional<SessionEpisodeDocument> extract(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        try {
            Map<String, Object> state = safeLoadState(sessionId);
            SessionView session = source.loadSession(sessionId);
            if (session == null && (state == null || state.isEmpty())) return Optional.empty();
            List<Map<String, Object>> messages = loadMessages(sessionId, state, session);
            Map<String, Object> executionView = loadExecutionView(sessionId, session);
            String resolvedSessionId = firstNonBlank(stringValue(state != null ? state.get("sessionId") : null),
                stringValue(state != null ? state.get("id") : null), session != null ? session.getId() : null, sessionId);
            String userId = firstNonBlank(stringValue(state != null ? state.get("userId") : null),
                stringValue(state != null ? state.get("user_id") : null), stringValue(nested(state, "metadata", "userId")), DEFAULT_USER_ID);
            String workspaceId = firstNonBlank(stringValue(state != null ? state.get("workspaceId") : null),
                stringValue(state != null ? state.get("workspace_id") : null), stringValue(state != null ? state.get("projectName") : null), "");
            long createdAt = firstPositive(longValue(state != null ? state.get("createdAt") : null),
                session != null ? session.getCreatedAt() : 0L, System.currentTimeMillis());
            long lastMessageAt = firstPositive(lastMessageTimestamp(messages), longValue(state != null ? state.get("updatedAt") : null),
                session != null ? session.getUpdatedAt() : 0L, createdAt);
            String goalText = truncate(firstNonBlank(stringValue(state != null ? state.get("goal") : null),
                session != null ? session.getGoal() : null, ""), 1024);
            String summaryText = truncate(firstNonBlank(stringValue(state != null ? state.get("lastFinishSummary") : null),
                finalText(executionView), sessionResult(session), lastAssistantMessage(messages), ""), 2048);
            return Optional.of(new SessionEpisodeDocument(resolvedSessionId, userId, workspaceId.isBlank() ? null : workspaceId,
                createdAt, lastMessageAt, goalText, summaryText, extractToolNames(messages, executionView),
                normalizeStatus(firstNonBlank(stringValue(state != null ? state.get("status") : null),
                    session != null ? session.getStatus() : null, "running")), System.currentTimeMillis()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to extract session episode " + sessionId, e);
        }
    }

    private Map<String, Object> safeLoadState(String sessionId) {
        try { return source.loadSessionState(sessionId); } catch (Exception e) { return Map.of(); }
    }

    private List<Map<String, Object>> loadMessages(String sessionId, Map<String, Object> state, SessionView session) {
        LinkedHashMap<String, Map<String, Object>> byId = new LinkedHashMap<>();
        appendMessages(byId, safeLoadArchivedMessages(sessionId));
        if (state != null && state.get("messages") instanceof List<?> stateMessages) appendMessages(byId, stateMessages);
        if (session != null) appendMessages(byId, session.getMessages());
        return new ArrayList<>(byId.values());
    }

    private List<Map<String, Object>> safeLoadArchivedMessages(String sessionId) {
        try { return source.loadSessionMessages(sessionId); } catch (Exception e) { return List.of(); }
    }

    private void appendMessages(LinkedHashMap<String, Map<String, Object>> byId, List<?> rawMessages) {
        if (rawMessages == null) return;
        int index = byId.size();
        for (Object raw : rawMessages) {
            if (!(raw instanceof Map<?, ?> rawMap)) continue;
            Map<String, Object> msg = new LinkedHashMap<>();
            rawMap.forEach((k, v) -> { if (k != null) msg.put(String.valueOf(k), v); });
            String id = firstNonBlank(stringValue(msg.get("id")), stringValue(msg.get("messageId")));
            if (id.isBlank()) id = "idx-" + index;
            byId.put(id, msg);
            index++;
        }
    }

    private Map<String, Object> loadExecutionView(String sessionId, SessionView session) {
        try {
            Map<String, Object> archived = source.loadSessionExecutionView(sessionId);
            if (archived != null && !archived.isEmpty()) return archived;
        } catch (Exception ignored) {}
        return session != null && session.getExecutionView() != null ? session.getExecutionView() : Map.of();
    }

    private static List<String> extractToolNames(List<Map<String, Object>> messages, Map<String, Object> executionView) {
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> msg : messages) {
            if ("tool".equalsIgnoreCase(stringValue(msg.get("role")))) {
                addIfPresent(names, stringValue(msg.get("toolName")));
                addIfPresent(names, stringValue(msg.get("name")));
                addIfPresent(names, stringValue(nested(msg, "metadata", "toolName")));
            }
        }
        Object toolCalls = executionView != null ? executionView.get("toolCalls") : null;
        if (toolCalls instanceof List<?> calls) {
            for (Object raw : calls) {
                if (raw instanceof Map<?, ?> call) {
                    addIfPresent(names, stringValue(call.get("toolName")));
                    addIfPresent(names, stringValue(call.get("name")));
                    addIfPresent(names, stringValue(call.get("action")));
                }
            }
        }
        return List.copyOf(names);
    }

    private static String finalText(Map<String, Object> executionView) {
        if (executionView == null) return "";
        return firstNonBlank(stringValue(executionView.get("finalAnswer")),
            stringValue(executionView.get("finalSummary")), stringValue(executionView.get("summary")));
    }
    private static String sessionResult(SessionView session) { return session == null || session.getResult() == null ? "" : String.valueOf(session.getResult()); }
    private static String lastAssistantMessage(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> msg = messages.get(i);
            if ("assistant".equalsIgnoreCase(stringValue(msg.get("role")))) return stringValue(msg.get("content"));
        }
        return "";
    }
    private static long lastMessageTimestamp(List<Map<String, Object>> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            long timestamp = firstPositive(longValue(messages.get(i).get("timestamp")),
                longValue(messages.get(i).get("createdAt")), longValue(messages.get(i).get("ts")));
            if (timestamp > 0) return timestamp;
        }
        return 0L;
    }
    private static String normalizeStatus(String status) {
        String s = status == null ? "" : status.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "completed", "complete", "success", "done", "finished" -> "finished";
            case "failed", "error" -> "failed";
            case "cancelled", "canceled", "aborted", "interrupted" -> "aborted";
            default -> "running";
        };
    }
    private static Object nested(Map<String, Object> map, String outer, String inner) {
        if (map == null || !(map.get(outer) instanceof Map<?, ?> nested)) return null;
        return nested.get(inner);
    }
    private static void addIfPresent(Set<String> names, String value) { if (value != null && !value.isBlank()) names.add(value.trim()); }
    private static String stringValue(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static long longValue(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            String trimmed = s.trim();
            if (trimmed.isEmpty()) return 0L;
            try { return Long.parseLong(trimmed); } catch (NumberFormatException ignored) {
                try { return Instant.parse(trimmed).toEpochMilli(); } catch (DateTimeParseException ignoredAgain) { return 0L; }
            }
        }
        return 0L;
    }
    private static long firstPositive(long... values) { for (long value : values) if (value > 0) return value; return 0L; }
    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }
    private static String truncate(String value, int max) { return value == null ? "" : (value.length() <= max ? value : value.substring(0, max)); }

    public record SessionEpisodeDocument(String sessionId, String userId, String workspaceId, long createdAt,
                                         long lastMessageAt, String goalText, String summaryText,
                                         List<String> toolNames, String status, long indexedAt) {
        public String toolNamesCsv() { return toolNames == null || toolNames.isEmpty() ? "" : String.join(",", toolNames); }
    }
}
