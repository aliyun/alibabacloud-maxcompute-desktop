package com.aliyun.odps.agentic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 连接 -- 表示一个基于 stdio 传输的 MCP 服务器连接。
 * 通过 JSON-RPC 2.0 与服务器通信，并用后台读取线程分发响应。
 */
class McpConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpConnection.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final AtomicInteger idCounter = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    private final Process process;
    private final BufferedWriter writer;
    private final Thread readerThread;
    private volatile boolean closed = false;

    private McpConnection(Process process) {
        this.process = process;
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        this.readerThread = new Thread(() -> readLoop(reader), "mcp-stdio-reader");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /**
     * 后台读取循环。
     * 从服务器标准输出读取 JSON-RPC 消息，并按 {@code id} 分发到对应的 {@link CompletableFuture}。
     */
    private void readLoop(BufferedReader reader) {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonNode message = MAPPER.readTree(line);

                    if (!message.has("id") || message.get("id").isNull()) {
                        continue;
                    }

                    int responseId = message.get("id").asInt();
                    CompletableFuture<JsonNode> future = pendingRequests.remove(responseId);

                    if (future != null) {
                        if (message.has("error")) {
                            JsonNode error = message.get("error");
                            future.completeExceptionally(new McpException(
                                "MCP error " + error.get("code").asInt()
                                    + ": " + error.get("message").asText()));
                        } else {
                            future.complete(message.get("result"));
                        }
                    } else {
                        log.warn("Received MCP response for unknown request id={}, ignoring", responseId);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse MCP response line: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            if (!closed) {
                log.warn("MCP stdio reader error: {}", e.getMessage());
            }
        } finally {
            failAllPending("MCP server connection closed");
            try { reader.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * 根据配置启动一个 MCP 服务器进程。
     *
     * @param config 服务器配置
     * @return 新建的连接对象
     */
    static McpConnection spawn(McpServerConfig config) {
        try {
            String[] command;
            if (config.args() != null && !config.args().isEmpty()) {
                command = new String[config.args().size() + 1];
                command[0] = config.command();
                for (int i = 0; i < config.args().size(); i++) {
                    command[i + 1] = config.args().get(i);
                }
            } else {
                command = new String[]{config.command()};
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            if (config.env() != null) {
                pb.environment().putAll(config.env());
            }
            pb.redirectErrorStream(false);

            Process process = pb.start();
            return new McpConnection(process);
        } catch (IOException e) {
            throw new McpException("Failed to spawn MCP server: " + config.name(), e);
        }
    }

    /**
     * 发送一个 JSON-RPC 请求并等待响应。
     *
     * @param method 方法名
     * @param params 请求参数
     * @return 响应结果
     */
    JsonNode sendRequest(String method, Map<String, Object> params) {
        int id = idCounter.getAndIncrement();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        try {
            ObjectNode request = MAPPER.createObjectNode();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", method);
            if (params != null && !params.isEmpty()) {
                request.set("params", MAPPER.valueToTree(params));
            }

            String json = MAPPER.writeValueAsString(request);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }

            return future.get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        } catch (TimeoutException e) {
            pendingRequests.remove(id);
            future.cancel(false);
            throw new McpException("Timeout waiting for MCP response (method=" + method + ", id=" + id + ")");
        } catch (ExecutionException e) {
            pendingRequests.remove(id);
            Throwable cause = e.getCause();
            if (cause instanceof McpException) {
                throw (McpException) cause;
            }
            throw new McpException("MCP request failed: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            pendingRequests.remove(id);
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted waiting for MCP response", e);
        } catch (IOException e) {
            pendingRequests.remove(id);
            future.completeExceptionally(e);
            throw new McpException("MCP communication error", e);
        }
    }

    /**
     * 发送一个 JSON-RPC 通知，不等待响应。
     *
     * @param method 方法名
     * @param params 通知参数
     */
    void sendNotification(String method, Map<String, Object> params) {
        try {
            ObjectNode notification = MAPPER.createObjectNode();
            notification.put("jsonrpc", "2.0");
            notification.put("method", method);
            if (params != null && !params.isEmpty()) {
                notification.set("params", MAPPER.valueToTree(params));
            }

            String json = MAPPER.writeValueAsString(notification);
            synchronized (writer) {
                writer.write(json);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            throw new McpException("MCP notification error", e);
        }
    }

    /** 将所有待处理请求以异常方式完成。 */
    private void failAllPending(String reason) {
        McpException ex = new McpException(reason);
        pendingRequests.forEach((id, future) -> future.completeExceptionally(ex));
        pendingRequests.clear();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        failAllPending("MCP connection closed");

        if (readerThread != null && readerThread.isAlive()) {
            readerThread.interrupt();
        }

        try { writer.close(); } catch (IOException ignored) {}
        process.destroyForcibly();
    }
}
