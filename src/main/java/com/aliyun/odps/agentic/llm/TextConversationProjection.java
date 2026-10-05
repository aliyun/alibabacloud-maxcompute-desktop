package com.aliyun.odps.agentic.llm;

import com.aliyun.odps.agentic.model.Message;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Projects recent SDK user/assistant text for a provider's plain chat endpoint. */
public final class TextConversationProjection {
    private static final int MAX_MESSAGES = 12;
    private static final int MAX_CHARS = 16_000;

    private TextConversationProjection() {}

    public static List<Map<String, String>> recent(LlmRequest request) {
        if (request.sourceMessages() == null) {
            throw new IllegalStateException("SDK text request has no source history");
        }
        List<Map<String, String>> messages = new ArrayList<>();
        for (Message message : request.sourceMessages()) {
            String role = switch (message.role()) {
                case USER -> "user";
                case ASSISTANT -> "assistant";
                default -> null;
            };
            if (role == null) continue;
            String content = message.getTextContent();
            if (content != null && !content.isBlank()) {
                messages.add(Map.of("role", role, "content", content));
            }
        }
        if (messages.isEmpty() || !"user".equals(messages.getLast().get("role"))) {
            throw new IllegalStateException("SDK text request is missing the current user message");
        }
        // Preserve whole recent messages. Always retain the current user input,
        // even when it alone exceeds the conservative provider text budget.
        int from = Math.max(0, messages.size() - MAX_MESSAGES);
        int chars = 0;
        for (int i = messages.size() - 1; i >= from; i--) {
            int next = messages.get(i).get("content").length();
            if (i < messages.size() - 1 && chars + next > MAX_CHARS) {
                from = i + 1;
                break;
            }
            chars += next;
        }
        while (from < messages.size() - 1 && "assistant".equals(messages.get(from).get("role"))) {
            from++;
        }
        return List.copyOf(messages.subList(from, messages.size()));
    }
}
