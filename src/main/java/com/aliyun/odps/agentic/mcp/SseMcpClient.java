package com.aliyun.odps.agentic.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于 SSE 传输的 MCP 客户端实现。
 * 用于连接远程 MCP 服务器，并通过事件流接收工具调用响应。
 */
public class SseMcpClient implements McpClient {

    private static final Logger log = LoggerFactory.getLogger(SseMcpClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_TIMEOUT_MS = 30_000;

    private final HttpClient httpClient;
    private final Map<String, SseConnection> connections = new ConcurrentHashMap<>();

    public SseMcpClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    /**
     * 使用指定的 {@link HttpClient} 创建客户端。
     *
     * @param httpClient HTTP 客户端
     */
    public SseMcpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public List<McpToolDef> connect(McpServerConfig config) {
        String baseUrl = config.url();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new McpException("SSE transport requires McpServerConfig.url() — use McpServerConfig.remote() to create");
        }

        try {
            SseConnection conn = establishSseConnection(config.name(), baseUrl, config.headers());
            connections.put(config.name(), conn);

            JsonNode initResult = conn.sendRequest("initialize", Map.of(
                "protocolVersion", McpClient.PREFERRED_PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "agentic-sdk", "version", "1.0.0")
            ));

            // 协议版本协商：服务端回它实际使用的版本（可能更低）。
            if (initResult != null && initResult.has("protocolVersion")) {
                String negotiated = initResult.get("protocolVersion").asText();
                if (!McpClient.PREFERRED_PROTOCOL_VERSION.equals(negotiated)) {
                    log.info("MCP server '{}' negotiated protocol version {} (client preferred {})",
                        config.name(), negotiated, McpClient.PREFERRED_PROTOCOL_VERSION);
                }
            }

            conn.sendNotification("notifications/initialized", Map.of());

            JsonNode toolsResult = conn.sendRequest("tools/list", Map.of());
            List<McpToolDef> tools = new ArrayList<>();
            if (toolsResult != null && toolsResult.has("tools")) {
                for (JsonNode tool : toolsResult.get("tools")) {
                    String toolName = tool.get("name").asText();
                    String description = tool.has("description") ? tool.get("description").asText() : "";
                    JsonNode schema = tool.has("inputSchema") ? tool.get("inputSchema") : null;
                    tools.add(new McpToolDef(config.name(), toolName, description, schema, this));
                }
            }

            log.info("Connected to MCP server '{}' via SSE, found {} tools", config.name(), tools.size());
            return tools;
        } catch (Exception e) {
            throw new McpException("Failed to connect to MCP server '" + config.name() + "' via SSE: " + e.getMessage(), e);
        }
    }

    @Override
    public void disconnect(String serverName) {
        SseConnection conn = connections.remove(serverName);
        if (conn != null) {
            conn.close();
            log.info("Disconnected from MCP server '{}'", serverName);
        }
    }

    @Override
    public List<String> listServers() {
        return List.copyOf(connections.keySet());
    }

    @Override
    public boolean isConnected(String serverName) {
        return connections.containsKey(serverName);
    }

    @Override
    public ToolResult callTool(String serverName, String toolName, Map<String, Object> arguments) {
        SseConnection conn = connections.get(serverName);
        if (conn == null) {
            return ToolResult.error("MCP server not connected: " + serverName);
        }

        try {
            JsonNode result = conn.sendRequest("tools/call", Map.of(
                "name", toolName,
                "arguments", arguments != null ? arguments : Map.of()
            ));

            return extractToolResult(result);
        } catch (Exception e) {
            return ToolResult.error("MCP tool call failed: " + e.getMessage());
        }
    }

    // ── 内部：SSE 连接管理 ──────────────────

    /**
     * 按照 MCP SSE 传输规范建立连接。
     * 先打开 {@code /sse} 事件流，再等待服务端下发请求端点 URL。
     */
    private SseConnection establishSseConnection(String name, String baseUrl, Map<String, String> headers) {
        String sseUrl = baseUrl.endsWith("/") ? baseUrl + "sse" : baseUrl + "/sse";

        Map<String, String> effectiveHeaders = new HashMap<>();
        effectiveHeaders.put("Accept", "text/event-stream");
        if (headers != null) effectiveHeaders.putAll(headers);

        try {
            ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

            CountDownLatch endpointLatch = new CountDownLatch(1);
            String[] endpointUrl = new String[1];

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(sseUrl))
                .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_MS / 1000));

            effectiveHeaders.forEach(reqBuilder::header);
            HttpRequest request = reqBuilder.GET().build();

            Thread sseThread = new Thread(() -> {
                try {
                    HttpResponse<java.util.stream.Stream<String>> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

                    response.body().forEach(line -> {
                        if (line.startsWith("event: endpoint")) {
                            // 下一条 data 事件中包含端点 URL
                        } else if (line.startsWith("data: ") && endpointUrl[0] == null) {
                            String data = line.substring(6).trim();
                            if (!data.startsWith("http")) {
                                String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
                                data = base + data;
                            }
                            endpointUrl[0] = data;
                            endpointLatch.countDown();
                        } else if (line.startsWith("data: ")) {
                            String eventData = line.substring(6).trim();
                            try {
                                JsonNode message = MAPPER.readTree(eventData);
                                if (message.has("id") && !message.get("id").isNull()) {
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
                                        log.warn("SSE '{}': received response for unknown request id={}, ignoring",
                                            name, responseId);
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("SSE '{}': failed to parse event data: {}", name, e.getMessage());
                            }
                        }
                    });
                } catch (Exception e) {
                    log.warn("SSE stream error for '{}': {}", name, e.getMessage());
                    endpointLatch.countDown();
                    McpException ex = new McpException("SSE stream closed: " + e.getMessage());
                    pendingRequests.forEach((id, future) -> future.completeExceptionally(ex));
                    pendingRequests.clear();
                }
            }, "sse-" + name);
            sseThread.setDaemon(true);
            sseThread.start();

            if (!endpointLatch.await(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                sseThread.interrupt();
                throw new McpException("Timeout waiting for SSE endpoint from: " + sseUrl);
            }

            if (endpointUrl[0] == null) {
                throw new McpException("SSE endpoint not received from: " + sseUrl);
            }

            return new SseConnection(name, endpointUrl[0], pendingRequests, headers, httpClient, sseThread);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("Interrupted while connecting to SSE: " + name, e);
        }
    }

    /**
     * 从 MCP {@code tools/call} 响应中提取 {@link ToolResult}。
     *
     * @param result MCP 响应结果
     * @return 工具执行结果
     */
    private ToolResult extractToolResult(JsonNode result) {
        if (result == null) {
            return ToolResult.error("Empty result from MCP server");
        }

        if (result.has("isError") && result.get("isError").asBoolean()) {
            StringBuilder sb = new StringBuilder("MCP tool error");
            if (result.has("content")) {
                for (JsonNode content : result.get("content")) {
                    if (content.has("text")) sb.append(": ").append(content.get("text").asText());
                }
            }
            return ToolResult.error(sb.toString());
        }

        StringBuilder output = new StringBuilder();
        if (result.has("content")) {
            for (JsonNode content : result.get("content")) {
                if (content.has("text")) {
                    output.append(content.get("text").asText()).append("\n");
                }
            }
        }

        return ToolResult.success(output.toString().trim());
    }

    // ── 内部：SSE 连接状态 ───────────────────────

    /**
     * SSE 连接状态对象 -- 管理单个远程 MCP SSE 连接。
     * 通过 POST 发送 JSON-RPC 请求，通过事件流按 {@code id} 接收响应。
     */
    static class SseConnection implements AutoCloseable {
        private static final AtomicInteger ID_COUNTER = new AtomicInteger(1);

        private final String name;
        private final String endpointUrl;
        private final ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests;
        private final Map<String, String> headers;
        private final HttpClient httpClient;
        private final Thread sseThread;
        private volatile boolean closed = false;

        SseConnection(String name, String endpointUrl,
                      ConcurrentHashMap<Integer, CompletableFuture<JsonNode>> pendingRequests,
                      Map<String, String> headers, HttpClient httpClient, Thread sseThread) {
            this.name = name;
            this.endpointUrl = endpointUrl;
            this.pendingRequests = pendingRequests;
            this.headers = headers;
            this.httpClient = httpClient;
            this.sseThread = sseThread;
        }

        /**
         * 发送 JSON-RPC 请求并等待通过 SSE 返回的响应。
         *
         * @param method 方法名
         * @param params 请求参数
         * @return 响应结果
         */
        JsonNode sendRequest(String method, Map<String, Object> params) {
            int id = ID_COUNTER.getAndIncrement();
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

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(DEFAULT_TIMEOUT_MS / 1000));

                if (headers != null) {
                    headers.forEach((k, v) -> {
                        if (!k.equalsIgnoreCase("content-type") && !k.equalsIgnoreCase("accept")) {
                            reqBuilder.header(k, v);
                        }
                    });
                }

                HttpRequest httpRequest = reqBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)))
                    .build();

                httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());

                return future.get(DEFAULT_TIMEOUT_MS / 1000, TimeUnit.SECONDS);

            } catch (TimeoutException e) {
                pendingRequests.remove(id);
                future.cancel(false);
                throw new McpException("Timeout waiting for MCP response via SSE (method=" + method + ", id=" + id + ")");
            } catch (ExecutionException e) {
                pendingRequests.remove(id);
                Throwable cause = e.getCause();
                if (cause instanceof McpException) {
                    throw (McpException) cause;
                }
                throw new McpException("MCP SSE request failed: " + cause.getMessage(), cause);
            } catch (InterruptedException e) {
                pendingRequests.remove(id);
                Thread.currentThread().interrupt();
                throw new McpException("Interrupted waiting for MCP SSE response", e);
            } catch (IOException e) {
                pendingRequests.remove(id);
                future.completeExceptionally(e);
                throw new McpException("MCP SSE communication error: " + e.getMessage(), e);
            }
        }

        /**
         * 发送 JSON-RPC 通知，不等待响应。
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

                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpointUrl))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10));

                if (headers != null) {
                    headers.forEach((k, v) -> {
                        if (!k.equalsIgnoreCase("content-type") && !k.equalsIgnoreCase("accept")) {
                            reqBuilder.header(k, v);
                        }
                    });
                }

                HttpRequest httpRequest = reqBuilder
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(notification)))
                    .build();

                httpClient.send(httpRequest, HttpResponse.BodyHandlers.discarding());
            } catch (IOException | InterruptedException e) {
                throw new McpException("MCP SSE notification error", e);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;

            McpException ex = new McpException("SSE connection closed");
            pendingRequests.forEach((id, future) -> future.completeExceptionally(ex));
            pendingRequests.clear();

            if (sseThread != null && sseThread.isAlive()) {
                sseThread.interrupt();
            }
        }
    }
}
