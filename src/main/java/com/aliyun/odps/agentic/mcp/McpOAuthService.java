package com.aliyun.odps.agentic.mcp;
import com.aliyun.odps.agentic.mcp.ExternalServerDef;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.aliyun.odps.agentic.storage.SqlDatabase;



import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/**
 * Handles OAuth 2.0 + PKCE for remote MCP servers.
 * Manages token storage, refresh, and authorization flow.
 */
public class McpOAuthService implements ManagedMcpManager.TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(McpOAuthService.class);
    private final String defaultRedirectUri;

    private final SqlDatabase jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Pending auth flows: state → PendingAuth
    private final Map<String, PendingAuth> pendingFlows = new LinkedHashMap<>();

    public McpOAuthService(SqlDatabase jdbcTemplate, ObjectMapper objectMapper, String defaultRedirectUri) {
        this.jdbcTemplate = jdbcTemplate;
        this.defaultRedirectUri = java.util.Objects.requireNonNull(defaultRedirectUri);
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    }

    public void init() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS mcp_oauth_tokens (
                server_name TEXT PRIMARY KEY,
                access_token TEXT NOT NULL,
                refresh_token TEXT,
                token_type TEXT DEFAULT 'Bearer',
                expires_at INTEGER,
                scope TEXT,
                client_id TEXT,
                client_secret TEXT,
                token_endpoint TEXT,
                authorization_endpoint TEXT,
                created_at INTEGER DEFAULT (strftime('%s', 'now')),
                updated_at INTEGER DEFAULT (strftime('%s', 'now'))
            )
        """);
        log.info("[McpOAuthService] Token table initialized");
    }

    /**
     * Start an OAuth authorization flow. Returns the authorization URL to open in a browser.
     */
    public AuthFlowResult startAuthFlow(String serverName, ExternalServerDef.OAuthConfig config, String serverUrl) throws Exception {
        String state = generateRandomString(32);
        String codeVerifier = generateRandomString(64);
        String codeChallenge = generateCodeChallenge(codeVerifier);

        String authEndpoint = config.getAuthorizationEndpoint();
        String tokenEndpoint = config.getTokenEndpoint();

        // Auto-discover OAuth endpoints if not configured
        if (authEndpoint == null || tokenEndpoint == null) {
            var discovered = discoverOAuthEndpoints(serverUrl);
            if (discovered != null) {
                if (authEndpoint == null) authEndpoint = discovered.authorizationEndpoint;
                if (tokenEndpoint == null) tokenEndpoint = discovered.tokenEndpoint;
            }
        }

        if (authEndpoint == null) {
            throw new IllegalStateException("OAuth authorization endpoint not configured and auto-discovery failed for: " + serverName);
        }

        String redirectUri = config.getRedirectUri() != null
            ? config.getRedirectUri()
            : defaultRedirectUri;

        StringBuilder authUrl = new StringBuilder(authEndpoint);
        authUrl.append("?response_type=code");
        authUrl.append("&client_id=").append(URLEncoder.encode(config.getClientId(), StandardCharsets.UTF_8));
        authUrl.append("&redirect_uri=").append(URLEncoder.encode(redirectUri, StandardCharsets.UTF_8));
        authUrl.append("&state=").append(state);
        authUrl.append("&code_challenge=").append(codeChallenge);
        authUrl.append("&code_challenge_method=S256");
        if (config.getScope() != null) {
            authUrl.append("&scope=").append(URLEncoder.encode(config.getScope(), StandardCharsets.UTF_8));
        }

        PendingAuth pending = new PendingAuth(
            serverName, state, codeVerifier, redirectUri,
            tokenEndpoint, config.getClientId(), config.getClientSecret());
        pendingFlows.put(state, pending);

        return new AuthFlowResult(authUrl.toString(), state);
    }

    /**
     * Complete the OAuth flow with the authorization code received from the callback.
     */
    public TokenInfo completeAuthFlow(String state, String code) throws Exception {
        PendingAuth pending = pendingFlows.remove(state);
        if (pending == null) {
            throw new IllegalStateException("No pending OAuth flow for state: " + state);
        }

        // Exchange code for token
        StringBuilder body = new StringBuilder();
        body.append("grant_type=authorization_code");
        body.append("&code=").append(URLEncoder.encode(code, StandardCharsets.UTF_8));
        body.append("&redirect_uri=").append(URLEncoder.encode(pending.redirectUri, StandardCharsets.UTF_8));
        body.append("&client_id=").append(URLEncoder.encode(pending.clientId, StandardCharsets.UTF_8));
        body.append("&code_verifier=").append(URLEncoder.encode(pending.codeVerifier, StandardCharsets.UTF_8));
        if (pending.clientSecret != null) {
            body.append("&client_secret=").append(URLEncoder.encode(pending.clientSecret, StandardCharsets.UTF_8));
        }

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(pending.tokenEndpoint))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Token exchange failed: " + response.statusCode() + " " + response.body());
        }

        JsonNode tokenJson = objectMapper.readTree(response.body());
        TokenInfo token = parseTokenResponse(tokenJson, pending);

        // Store token
        storeToken(pending.serverName, token);

        return token;
    }

    /**
     * Get a valid access token for the server, refreshing if necessary.
     */
    public String getAccessToken(String serverName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM mcp_oauth_tokens WHERE server_name = ?", serverName);
            if (rows.isEmpty()) return null;

            Map<String, Object> row = rows.get(0);
            String accessToken = (String) row.get("access_token");
            Long expiresAt = row.get("expires_at") != null ? ((Number) row.get("expires_at")).longValue() : null;
            String refreshToken = (String) row.get("refresh_token");

            // Check if token is expired
            if (expiresAt != null && System.currentTimeMillis() / 1000 > expiresAt - 60) {
                if (refreshToken != null) {
                    return refreshAccessToken(serverName, row);
                }
                return null;
            }
            return accessToken;
        } catch (Exception e) {
            log.warn("[McpOAuthService] Failed to get token for {}: {}", serverName, e.getMessage());
            return null;
        }
    }

    /**
     * Check if a server has stored OAuth tokens.
     */
    public boolean hasStoredTokens(String serverName) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mcp_oauth_tokens WHERE server_name = ?",
                Integer.class, serverName);
            return count != null && count > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Remove stored tokens for a server.
     */
    public void removeTokens(String serverName) {
        jdbcTemplate.update("DELETE FROM mcp_oauth_tokens WHERE server_name = ?", serverName);
        log.info("[McpOAuthService] Removed tokens for {}", serverName);
    }

    /**
     * Get the auth status for a server.
     */
    public String getAuthStatus(String serverName) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT expires_at FROM mcp_oauth_tokens WHERE server_name = ?", serverName);
            if (rows.isEmpty()) return "not_authenticated";
            Long expiresAt = rows.get(0).get("expires_at") != null
                ? ((Number) rows.get(0).get("expires_at")).longValue() : null;
            if (expiresAt != null && System.currentTimeMillis() / 1000 > expiresAt) {
                return "expired";
            }
            return "authenticated";
        } catch (Exception e) {
            return "not_authenticated";
        }
    }

    private String refreshAccessToken(String serverName, Map<String, Object> storedRow) {
        try {
            String tokenEndpoint = (String) storedRow.get("token_endpoint");
            String refreshToken = (String) storedRow.get("refresh_token");
            String clientId = (String) storedRow.get("client_id");
            String clientSecret = (String) storedRow.get("client_secret");

            if (tokenEndpoint == null || refreshToken == null || clientId == null) return null;

            StringBuilder body = new StringBuilder();
            body.append("grant_type=refresh_token");
            body.append("&refresh_token=").append(URLEncoder.encode(refreshToken, StandardCharsets.UTF_8));
            body.append("&client_id=").append(URLEncoder.encode(clientId, StandardCharsets.UTF_8));
            if (clientSecret != null) {
                body.append("&client_secret=").append(URLEncoder.encode(clientSecret, StandardCharsets.UTF_8));
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenEndpoint))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("[McpOAuthService] Refresh failed for {}: {}", serverName, response.statusCode());
                return null;
            }

            JsonNode tokenJson = objectMapper.readTree(response.body());
            String newAccessToken = tokenJson.get("access_token").asText();
            String newRefreshToken = tokenJson.has("refresh_token")
                ? tokenJson.get("refresh_token").asText() : refreshToken;
            Long expiresIn = tokenJson.has("expires_in") ? tokenJson.get("expires_in").asLong() : null;
            Long expiresAt = expiresIn != null ? System.currentTimeMillis() / 1000 + expiresIn : null;

            jdbcTemplate.update("""
                UPDATE mcp_oauth_tokens SET access_token = ?, refresh_token = ?, expires_at = ?,
                    updated_at = strftime('%s', 'now') WHERE server_name = ?
                """, newAccessToken, newRefreshToken, expiresAt, serverName);

            log.info("[McpOAuthService] Refreshed token for {}", serverName);
            return newAccessToken;
        } catch (Exception e) {
            log.error("[McpOAuthService] Token refresh failed for {}: {}", serverName, e.getMessage());
            return null;
        }
    }

    private void storeToken(String serverName, TokenInfo token) {
        jdbcTemplate.update("DELETE FROM mcp_oauth_tokens WHERE server_name = ?", serverName);
        jdbcTemplate.update("""
            INSERT INTO mcp_oauth_tokens (server_name, access_token, refresh_token, token_type, expires_at, scope, client_id, client_secret, token_endpoint)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            serverName, token.accessToken, token.refreshToken, token.tokenType,
            token.expiresAt, token.scope, token.clientId, token.clientSecret, token.tokenEndpoint);
        log.info("[McpOAuthService] Stored token for {}", serverName);
    }

    private TokenInfo parseTokenResponse(JsonNode json, PendingAuth pending) {
        TokenInfo info = new TokenInfo();
        info.accessToken = json.get("access_token").asText();
        info.refreshToken = json.has("refresh_token") ? json.get("refresh_token").asText() : null;
        info.tokenType = json.has("token_type") ? json.get("token_type").asText() : "Bearer";
        info.scope = json.has("scope") ? json.get("scope").asText() : null;
        if (json.has("expires_in")) {
            info.expiresAt = System.currentTimeMillis() / 1000 + json.get("expires_in").asLong();
        }
        info.clientId = pending.clientId;
        info.clientSecret = pending.clientSecret;
        info.tokenEndpoint = pending.tokenEndpoint;
        return info;
    }

    private OAuthEndpoints discoverOAuthEndpoints(String serverUrl) {
        try {
            URI baseUri = URI.create(serverUrl);
            String wellKnownUrl = baseUri.getScheme() + "://" + baseUri.getHost()
                + (baseUri.getPort() > 0 ? ":" + baseUri.getPort() : "")
                + "/.well-known/oauth-authorization-server";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(wellKnownUrl))
                .header("Accept", "application/json")
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode meta = objectMapper.readTree(response.body());
                OAuthEndpoints endpoints = new OAuthEndpoints();
                endpoints.authorizationEndpoint = meta.has("authorization_endpoint")
                    ? meta.get("authorization_endpoint").asText() : null;
                endpoints.tokenEndpoint = meta.has("token_endpoint")
                    ? meta.get("token_endpoint").asText() : null;
                return endpoints;
            }
        } catch (Exception e) {
            log.debug("[McpOAuthService] OAuth discovery failed for {}: {}", serverUrl, e.getMessage());
        }
        return null;
    }

    private static String generateRandomString(int length) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).substring(0, length);
    }

    private static String generateCodeChallenge(String verifier) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    // --- DTOs ---

    public static class AuthFlowResult {
        public final String authorizationUrl;
        public final String state;

        public AuthFlowResult(String authorizationUrl, String state) {
            this.authorizationUrl = authorizationUrl;
            this.state = state;
        }
    }

    public static class TokenInfo {
        public String accessToken;
        public String refreshToken;
        public String tokenType;
        public Long expiresAt;
        public String scope;
        public String clientId;
        public String clientSecret;
        public String tokenEndpoint;
    }

    private static class PendingAuth {
        final String serverName;
        final String state;
        final String codeVerifier;
        final String redirectUri;
        final String tokenEndpoint;
        final String clientId;
        final String clientSecret;

        PendingAuth(String serverName, String state, String codeVerifier,
                    String redirectUri, String tokenEndpoint, String clientId, String clientSecret) {
            this.serverName = serverName;
            this.state = state;
            this.codeVerifier = codeVerifier;
            this.redirectUri = redirectUri;
            this.tokenEndpoint = tokenEndpoint;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }
    }

    private static class OAuthEndpoints {
        String authorizationEndpoint;
        String tokenEndpoint;
    }
}
