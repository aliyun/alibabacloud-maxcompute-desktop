package com.aliyun.odps.agentic.example.chat;

import java.time.Instant;
import java.util.List;

/**
 * DTOs for the chat REST API.
 */
public class ChatMessage {

    /** Inbound request to send a message. */
    public static class Request {
        private String message;
        private String sessionId;

        public Request() {}

        public Request(String message, String sessionId) {
            this.message = message;
            this.sessionId = sessionId;
        }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    }

    /** Response containing the assistant reply. */
    public static class Response {
        private String sessionId;
        private String content;
        private List<ToolCallInfo> toolCalls;
        private Instant timestamp;

        public Response() {}

        public Response(String sessionId, String content, List<ToolCallInfo> toolCalls) {
            this.sessionId = sessionId;
            this.content = content;
            this.toolCalls = toolCalls;
            this.timestamp = Instant.now();
        }

        public String getSessionId() { return sessionId; }
        public void setSessionId(String sessionId) { this.sessionId = sessionId; }
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        public List<ToolCallInfo> getToolCalls() { return toolCalls; }
        public void setToolCalls(List<ToolCallInfo> toolCalls) { this.toolCalls = toolCalls; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    }

    /** Information about a tool call during the conversation. */
    public static class ToolCallInfo {
        private String tool;
        private String callId;
        private String output;
        private boolean error;

        public ToolCallInfo() {}

        public ToolCallInfo(String tool, String callId, String output, boolean error) {
            this.tool = tool;
            this.callId = callId;
            this.output = output;
            this.error = error;
        }

        public String getTool() { return tool; }
        public void setTool(String tool) { this.tool = tool; }
        public String getCallId() { return callId; }
        public void setCallId(String callId) { this.callId = callId; }
        public String getOutput() { return output; }
        public void setOutput(String output) { this.output = output; }
        public boolean isError() { return error; }
        public void setError(boolean error) { this.error = error; }
    }

    /** Session summary for listing. */
    public static class SessionInfo {
        private String id;
        private String title;
        private Instant createdAt;
        private int messageCount;

        public SessionInfo() {}

        public SessionInfo(String id, String title, Instant createdAt, int messageCount) {
            this.id = id;
            this.title = title;
            this.createdAt = createdAt;
            this.messageCount = messageCount;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public Instant getCreatedAt() { return createdAt; }
        public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
        public int getMessageCount() { return messageCount; }
        public void setMessageCount(int messageCount) { this.messageCount = messageCount; }
    }
}
