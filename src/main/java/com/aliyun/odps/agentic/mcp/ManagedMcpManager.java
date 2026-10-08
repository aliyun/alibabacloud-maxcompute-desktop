package com.aliyun.odps.agentic.mcp;
import com.aliyun.odps.agentic.mcp.ExternalServerDef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.McpGetPromptResult;
import dev.langchain4j.mcp.client.McpPrompt;
import dev.langchain4j.mcp.client.McpReadResourceResult;
import dev.langchain4j.mcp.client.McpResource;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP Client Manager — connects to external MCP servers via langchain4j MCP Client
 * and bridges their tools into the host context tool registry.
 *
 * Uses langchain4j DefaultMcpClient with:
 * - StdioMcpTransport for local processes
 * - StreamableHttpMcpTransport for remote servers and SSE event channels
 * - Auto health check, auto reconnect, tool list change notifications
 */
public class ManagedMcpManager implements AutoCloseable {
    public interface ToolBridge {
        int bridgeTools(String name, List<ToolSpecification> tools, McpClient client);
        void unbridgeTools(String name);
    }
    public interface ServerRepository {
        List<ExternalServerDef> findAutoConnectEnabled(); List<ExternalServerDef> findAll();
        Optional<ExternalServerDef> findByName(String name);
        void save(ExternalServerDef definition); void delete(String name); void setEnabled(String name,boolean enabled);
    }
    @FunctionalInterface public interface TokenProvider { String getAccessToken(String name); }
    @FunctionalInterface public interface PathResolver { String resolvePath(); }


    private static final Logger log = LoggerFactory.getLogger(ManagedMcpManager.class);

    private String clientName="Agentic SDK";
    private String clientVersion="1.0";
    public void setClientIdentity(String name,String version) {
        this.clientName=Objects.requireNonNull(name); this.clientVersion=Objects.requireNonNull(version);
    }

    private final Map<String, McpClient> clients = new ConcurrentHashMap<>();
    private final Map<String, McpServerEntry> serverEntries = new ConcurrentHashMap<>();
    private final ToolBridge toolBridge;
    private final ServerRepository repository;
    private final TokenProvider oauthService;
    private final ObjectMapper objectMapper;
    private final PathResolver pathResolver;

    public ManagedMcpManager(ToolBridge toolBridge, ServerRepository repository,
                            TokenProvider oauthService, ObjectMapper objectMapper,
                            PathResolver pathResolver) {
        this.toolBridge = toolBridge;
        this.repository = repository;
        this.oauthService = oauthService;
        this.objectMapper = objectMapper;
        this.pathResolver = pathResolver;
    }

    public void autoConnect() {
        List<ExternalServerDef> servers = repository.findAutoConnectEnabled();
        if (servers.isEmpty()) {
            log.info("[MCP Client] No auto-connect servers configured");
            return;
        }

        log.info("[MCP Client] Auto-connecting {} server(s)...", servers.size());
        for (ExternalServerDef def : servers) {
            try {
                connectServer(def, false);
            } catch (Exception e) {
                log.warn("[MCP Client] Auto-connect failed for '{}': {}", def.getName(), e.getMessage());
                serverEntries.put(def.getName(), new McpServerEntry(
                    def.getName(), def, "error:" + e.getMessage(), List.of()));
            }
        }
    }

    public synchronized void connectServer(ExternalServerDef def, boolean persist) {
        String name = def.getName();
        if (clients.containsKey(name)) {
            log.info("[MCP Client] Server '{}' already connected, disconnecting first", name);
            disconnectServer(name, false);
        }

        if (!def.isEnabled()) {
            serverEntries.put(name, new McpServerEntry(name, def, "disabled", List.of()));
            if (persist) repository.save(def);
            return;
        }

        try {
            log.info("[MCP Client] Connecting to server '{}': type={}", name, def.getTransportType());

            McpClient client;
            if (def.isRemote()) {
                client = createRemoteClient(name, def);
            } else {
                client = createStdioClient(name, def);
            }

            List<ToolSpecification> tools;
            try {
                tools = client.listTools();
            } catch (Exception toolsErr) {
                try { client.close(); } catch (Exception ignored) {}
                throw new RuntimeException("listTools failed: " + toolsErr.getMessage(), toolsErr);
            }
            clients.put(name, client);
            serverEntries.put(name, new McpServerEntry(name, def, "connected", tools));
            int bridged = toolBridge.bridgeTools(name, tools, client);
            log.info("[MCP Client] Connected to '{}' — {} tools bridged", name, bridged);

            if (persist) repository.save(def);
        } catch (RuntimeException e) {
            if (isAuthError(e)) {
                serverEntries.put(name, new McpServerEntry(name, def, "needs_auth", List.of()));
                if (persist) repository.save(def);
                log.info("[MCP Client] Server '{}' requires OAuth authentication", name);
            } else {
                log.error("[MCP Client] Failed to connect to '{}'", name, e);
                serverEntries.put(name, new McpServerEntry(name, def, "error:" + e.getMessage(), List.of()));
                if (persist) repository.save(def);
                throw new RuntimeException("Failed to connect MCP server: " + name, e);
            }
        }
    }

    public synchronized void connectServer(ExternalServerDef def) {
        connectServer(def, true);
    }

    public synchronized void disconnectServer(String name, boolean removeConfig) {
        McpClient client = clients.remove(name);
        if (client != null) {
            try {
                toolBridge.unbridgeTools(name);
                client.close();
                log.info("[MCP Client] Disconnected from '{}'", name);
            } catch (Exception e) {
                log.warn("[MCP Client] Error disconnecting '{}': {}", name, e.getMessage());
            }
        }
        serverEntries.remove(name);
        if (removeConfig) {
            repository.delete(name);
        }
    }

    public synchronized void disconnectServer(String name) {
        disconnectServer(name, true);
    }

    public synchronized void setEnabled(String name, boolean enabled) {
        repository.setEnabled(name, enabled);
        if (!enabled && clients.containsKey(name)) {
            disconnectServer(name, false);
            repository.findByName(name).ifPresent(def ->
                serverEntries.put(name, new McpServerEntry(name, def, "disabled", List.of())));
        } else if (enabled) {
            repository.findByName(name).ifPresent(def -> connectServer(def, false));
        }
    }

    public synchronized void reconnect(String name) {
        repository.findByName(name).ifPresent(def -> connectServer(def, false));
    }

    public boolean isConnected(String name) {
        McpClient client = clients.get(name);
        if (client == null) return false;
        try {
            client.checkHealth();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void checkHealth(String name) {
        McpClient client = clients.get(name);
        if (client == null) {
            throw new IllegalStateException("MCP server not connected: " + name);
        }
        client.checkHealth();
    }

    public List<McpServerEntry> listServers() {
        List<McpServerEntry> result = new ArrayList<>(serverEntries.values());
        for (ExternalServerDef def : repository.findAll()) {
            if (!serverEntries.containsKey(def.getName())) {
                result.add(new McpServerEntry(def.getName(), def,
                    def.isEnabled() ? "disconnected" : "disabled", List.of()));
            }
        }
        return result;
    }

    public McpServerEntry getServerEntry(String name) {
        McpServerEntry entry = serverEntries.get(name);
        if (entry != null) return entry;
        return repository.findByName(name)
            .map(def -> new McpServerEntry(name, def,
                def.isEnabled() ? "disconnected" : "disabled", List.of()))
            .orElse(null);
    }

    public JsonNode callTool(String serverName, String toolName, Map<String, Object> args) throws Exception {
        McpClient client = clients.get(serverName);
        if (client == null) {
            throw new IllegalStateException("MCP server not connected: " + serverName);
        }

        var request = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
            .name(toolName)
            .arguments(objectMapper.writeValueAsString(args != null ? args : Map.of()))
            .build();
        var result = client.executeTool(request);
        String text = result.resultText();
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            return objectMapper.getNodeFactory().textNode(text);
        }
    }

    public List<McpResource> listResources(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null) throw new IllegalStateException("MCP server not connected: " + serverName);
        return client.listResources();
    }

    public McpReadResourceResult readResource(String serverName, String uri) {
        McpClient client = clients.get(serverName);
        if (client == null) throw new IllegalStateException("MCP server not connected: " + serverName);
        return client.readResource(uri);
    }

    public List<McpPrompt> listPrompts(String serverName) {
        McpClient client = clients.get(serverName);
        if (client == null) throw new IllegalStateException("MCP server not connected: " + serverName);
        return client.listPrompts();
    }

    public McpGetPromptResult getPrompt(String serverName, String name, Map<String, Object> args) {
        McpClient client = clients.get(serverName);
        if (client == null) throw new IllegalStateException("MCP server not connected: " + serverName);
        return client.getPrompt(name, args);
    }

    /**
     * Parse JSON config for batch import (supports Claude Desktop and .mcp.json formats).
     */
    public List<ExternalServerDef> parseImportConfig(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        JsonNode servers = root.has("mcpServers") ? root.get("mcpServers") : root;
        if (!servers.isObject()) {
            throw new IllegalArgumentException("Expected 'mcpServers' object in JSON config");
        }

        List<ExternalServerDef> result = new ArrayList<>();
        var fields = servers.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String name = entry.getKey();
            JsonNode cfg = entry.getValue();

            ExternalServerDef def = new ExternalServerDef();
            def.setName(name);

            if (cfg.has("command")) {
                // stdio transport
                def.setTransportType(ExternalServerDef.TransportType.STDIO);
                def.setCommand(cfg.get("command").asText());
                if (cfg.has("args") && cfg.get("args").isArray()) {
                    List<String> args = new ArrayList<>();
                    for (JsonNode arg : cfg.get("args")) args.add(arg.asText());
                    def.setArgs(args);
                }
                if (cfg.has("env") && cfg.get("env").isObject()) {
                    Map<String, String> env = new LinkedHashMap<>();
                    cfg.get("env").fields().forEachRemaining(e -> env.put(e.getKey(), e.getValue().asText()));
                    def.setEnv(env);
                }
            } else if (cfg.has("url")) {
                // remote transport
                String typeStr = cfg.has("type") ? cfg.get("type").asText().toUpperCase() : "HTTP";
                try {
                    def.setTransportType(ExternalServerDef.TransportType.valueOf(typeStr));
                } catch (IllegalArgumentException e) {
                    def.setTransportType(ExternalServerDef.TransportType.HTTP);
                }
                def.setUrl(cfg.get("url").asText());
                if (cfg.has("headers") && cfg.get("headers").isObject()) {
                    Map<String, String> headers = new LinkedHashMap<>();
                    cfg.get("headers").fields().forEachRemaining(e -> headers.put(e.getKey(), e.getValue().asText()));
                    def.setHeaders(headers);
                }
            } else {
                log.warn("[MCP Import] Skipping '{}': no 'command' or 'url' field", name);
                continue;
            }

            if (cfg.has("timeout")) {
                def.setTimeoutMs(cfg.get("timeout").asInt(30000));
            }
            if (cfg.has("enabled")) {
                def.setEnabled(cfg.get("enabled").asBoolean(true));
            }
            if (cfg.has("autoConnect")) {
                def.setAutoConnect(cfg.get("autoConnect").asBoolean(true));
            }

            result.add(def);
        }
        return result;
    }

    @Override public void close() { shutdown(); }

    public void shutdown() {
        log.info("[MCP Client] Shutting down {} connections", clients.size());
        for (String name : new ArrayList<>(clients.keySet())) {
            disconnectServer(name, false);
        }
    }

    // --- Private connection methods ---

    // package-private for testing with Mockito spy
    protected McpClient createStdioClient(String name, ExternalServerDef def) {
        List<String> command = new ArrayList<>();
        command.add(def.getCommand());
        if (def.getArgs() != null) command.addAll(def.getArgs());

        // 用 login-shell 解析的真实 PATH 铺底（打包态 launchd PATH 极简，nvm/fnm 的 node/npx 不可达），
        // 再叠加用户在 def 里显式声明的 env（用户覆盖仍优先）。
        Map<String, String> env = new LinkedHashMap<>();
        String resolvedPath = pathResolver != null ? pathResolver.resolvePath() : null;
        if (resolvedPath != null && !resolvedPath.isBlank()) {
            env.put("PATH", resolvedPath);
        }
        if (def.getEnv() != null) {
            env.putAll(def.getEnv());
        }

        StdioMcpTransport transport = StdioMcpTransport.builder()
            .command(command)
            .environment(env)
            .logEvents(log.isDebugEnabled())
            .build();

        return DefaultMcpClient.builder()
            .transport(transport)
            .key(name)
            .clientName(clientName)
            .clientVersion(clientVersion)
            .toolExecutionTimeout(Duration.ofMillis(resolveTimeout(def)))
            .autoHealthCheck(true)
            .autoHealthCheckInterval(Duration.ofSeconds(30))
            .addListener(new McpClientListenerAdapter(name, this::handleToolsChanged))
            .build();
    }

    // package-private for testing with Mockito spy
    protected McpClient createRemoteClient(String name, ExternalServerDef def) {
        StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
            .url(def.getUrl())
            .customHeaders(() -> buildHeaders(def, name))
            .timeout(Duration.ofMillis(resolveTimeout(def)))
            .followRedirects(true)
            .subsidiaryChannel(true)
            .build();

        return DefaultMcpClient.builder()
            .transport(transport)
            .key(name)
            .clientName(clientName)
            .clientVersion(clientVersion)
            .toolExecutionTimeout(Duration.ofMillis(resolveTimeout(def)))
            .reconnectInterval(Duration.ofSeconds(5))
            .autoHealthCheck(true)
            .autoHealthCheckInterval(Duration.ofMinutes(1))
            .addListener(new McpClientListenerAdapter(name, this::handleToolsChanged))
            .build();
    }

    private Map<String, String> buildHeaders(ExternalServerDef def, String serverName) {
        Map<String, String> headers = new HashMap<>();
        if (def.getHeaders() != null) headers.putAll(def.getHeaders());
        String token = oauthService.getAccessToken(serverName);
        if (token != null) headers.put("Authorization", "Bearer " + token);
        return headers;
    }

    private long resolveTimeout(ExternalServerDef def) {
        return def.getTimeoutMs() != null ? def.getTimeoutMs() : 30_000L;
    }

    private void handleToolsChanged(String serverName) {
        try {
            log.info("[MCP Client] Tools changed for '{}', refreshing...", serverName);
            McpClient client = clients.get(serverName);
            if (client == null) return;

            List<ToolSpecification> tools = client.listTools();
            toolBridge.unbridgeTools(serverName);
            toolBridge.bridgeTools(serverName, tools, client);

            McpServerEntry entry = serverEntries.get(serverName);
            if (entry != null) {
                serverEntries.put(serverName, new McpServerEntry(
                    serverName, entry.getConfig(), "connected", tools));
            }
            log.info("[MCP Client] Refreshed {} tools for '{}'", tools.size(), serverName);
        } catch (Exception e) {
            log.error("[MCP Client] Failed to refresh tools for '{}': {}", serverName, e.getMessage());
        }
    }

    private static boolean isAuthError(Exception e) {
        String msg = e.getMessage();
        return msg != null && (msg.contains("401") || msg.contains("403") || msg.contains("Unauthorized"));
    }

    // --- Entry DTO ---

    public static class McpServerEntry {
        private final String name;
        private final ExternalServerDef config;
        private final String status;
        private final List<ToolSpecification> tools;

        public McpServerEntry(String name, ExternalServerDef config,
                              String status, List<ToolSpecification> tools) {
            this.name = name;
            this.config = config;
            this.status = status;
            this.tools = tools;
        }

        public String getName() { return name; }
        public ExternalServerDef getConfig() { return config; }
        public String getStatus() { return status; }
        public List<ToolSpecification> getTools() { return tools; }

        public Map<String, Object> toView() {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("name", name);
            view.put("status", status);
            view.put("transportType", config.getTransportType().name());
            view.put("enabled", config.isEnabled());

            if (config.getTransportType() == ExternalServerDef.TransportType.STDIO) {
                view.put("command", config.getCommand());
                view.put("args", config.getArgs());
            } else {
                view.put("url", config.getUrl());
            }

            if (config.getEnv() != null && !config.getEnv().isEmpty()) {
                view.put("env", config.getEnv());
            }
            if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
                view.put("headers", config.getHeaders());
            }
            if (config.getTimeoutMs() != null) {
                view.put("timeoutMs", config.getTimeoutMs());
            }
            view.put("autoConnect", config.isAutoConnect());
            view.put("hasOAuth", config.getOauth() != null);
            view.put("toolCount", tools.size());

            List<Map<String, Object>> toolViews = new ArrayList<>();
            for (ToolSpecification t : tools) {
                Map<String, Object> tv = new LinkedHashMap<>();
                tv.put("name", t.name());
                tv.put("description", t.description() != null ? t.description() : "");
                toolViews.add(tv);
            }
            view.put("tools", toolViews);
            return view;
        }
    }
}
