package com.aliyun.odps.agentic.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * Configuration for an external MCP server connection.
 * Supports both local (stdio) and remote (HTTP/SSE) transports.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExternalServerDef {

    public enum TransportType {
        STDIO, HTTP, SSE, WEBSOCKET
    }

    private String name;
    private TransportType transportType = TransportType.STDIO;

    // --- Stdio fields ---
    private String command;
    private List<String> args;
    private Map<String, String> env;

    // --- Remote fields (HTTP/SSE) ---
    private String url;
    private Map<String, String> headers;

    // --- OAuth fields ---
    private OAuthConfig oauth;

    // --- Common config ---
    private boolean enabled = true;
    private Integer timeoutMs;
    private boolean autoConnect = true;

    public ExternalServerDef() {}

    public ExternalServerDef(String name, String command, List<String> args, Map<String, String> env) {
        this.name = name;
        this.command = command;
        this.args = args;
        this.env = env;
        this.transportType = TransportType.STDIO;
    }

    public static ExternalServerDef stdio(String name, String command, List<String> args, Map<String, String> env) {
        ExternalServerDef def = new ExternalServerDef(name, command, args, env);
        def.setTransportType(TransportType.STDIO);
        return def;
    }

    public static ExternalServerDef remote(String name, String url, TransportType type) {
        ExternalServerDef def = new ExternalServerDef();
        def.setName(name);
        def.setUrl(url);
        def.setTransportType(type);
        return def;
    }

    // --- Getters/Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public TransportType getTransportType() { return transportType; }
    public void setTransportType(TransportType transportType) { this.transportType = transportType; }

    public String getCommand() { return command; }
    public void setCommand(String command) { this.command = command; }

    public List<String> getArgs() { return args; }
    public void setArgs(List<String> args) { this.args = args; }

    public Map<String, String> getEnv() { return env; }
    public void setEnv(Map<String, String> env) { this.env = env; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public OAuthConfig getOauth() { return oauth; }
    public void setOauth(OAuthConfig oauth) { this.oauth = oauth; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Integer getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Integer timeoutMs) { this.timeoutMs = timeoutMs; }

    public boolean isAutoConnect() { return autoConnect; }
    public void setAutoConnect(boolean autoConnect) { this.autoConnect = autoConnect; }

    public boolean isRemote() {
        return transportType == TransportType.HTTP
            || transportType == TransportType.SSE
            || transportType == TransportType.WEBSOCKET;
    }

    /**
     * OAuth configuration for remote MCP servers.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OAuthConfig {
        private String clientId;
        private String clientSecret;
        private String scope;
        private String redirectUri;
        private String tokenEndpoint;
        private String authorizationEndpoint;

        public OAuthConfig() {}

        public String getClientId() { return clientId; }
        public void setClientId(String clientId) { this.clientId = clientId; }

        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

        public String getScope() { return scope; }
        public void setScope(String scope) { this.scope = scope; }

        public String getRedirectUri() { return redirectUri; }
        public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

        public String getTokenEndpoint() { return tokenEndpoint; }
        public void setTokenEndpoint(String tokenEndpoint) { this.tokenEndpoint = tokenEndpoint; }

        public String getAuthorizationEndpoint() { return authorizationEndpoint; }
        public void setAuthorizationEndpoint(String authorizationEndpoint) { this.authorizationEndpoint = authorizationEndpoint; }
    }
}
