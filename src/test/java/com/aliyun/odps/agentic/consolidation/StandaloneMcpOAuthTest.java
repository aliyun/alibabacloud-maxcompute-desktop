package com.aliyun.odps.agentic.consolidation;
import com.aliyun.odps.agentic.mcp.*;
import com.aliyun.odps.agentic.storage.JdbcSqlDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import java.net.*;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class StandaloneMcpOAuthTest {
    @TempDir Path directory;
    private Map<String,String> params(String encoded) {
        var values=new HashMap<String,String>();
        for (String pair:encoded.split("&")) {
            var parts=pair.split("=",2); values.put(URLDecoder.decode(parts[0],StandardCharsets.UTF_8),URLDecoder.decode(parts[1],StandardCharsets.UTF_8));
        }
        return values;
    }
    @Test void pkceExchangeRefreshAndConfigurationPersistThroughPlainJdbc() throws Exception {
        var mapper=new ObjectMapper(); var body=new AtomicReference<Map<String,String>>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/token",exchange -> {
            var request=params(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)); body.set(request);
            String token="refresh_token".equals(request.get("grant_type")) ? "fixture-refreshed" : "fixture-access";
            byte[] response=mapper.writeValueAsBytes(Map.of("access_token",token,"refresh_token","fixture-refresh","expires_in",3600,"token_type","Bearer"));
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,response.length);
            try (var output=exchange.getResponseBody()) { output.write(response); }
        }); server.start();
        try {
            var datasource=new SQLiteDataSource(); datasource.setUrl("jdbc:sqlite:"+directory.resolve("mcp.db"));
            var sql=new JdbcSqlDatabase(datasource);
            String origin="http://127.0.0.1:"+server.getAddress().getPort();
            var auth=new McpOAuthService(sql,mapper,origin+"/callback"); auth.init();
            var oauth=new ExternalServerDef.OAuthConfig(); oauth.setClientId("fixture-client"); oauth.setAuthorizationEndpoint(origin+"/authorize"); oauth.setTokenEndpoint(origin+"/token");
            var flow=auth.startAuthFlow("fixture",oauth,origin);
            var query=params(URI.create(flow.authorizationUrl).getRawQuery());
            assertEquals("S256",query.get("code_challenge_method"));
            assertEquals(origin+"/callback",query.get("redirect_uri"));
            auth.completeAuthFlow(flow.state,"fixture-code");
            byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest(body.get().get("code_verifier").getBytes(StandardCharsets.US_ASCII));
            assertEquals(query.get("code_challenge"),Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
            assertThrows(IllegalStateException.class,() -> auth.completeAuthFlow(flow.state,"replay"));
            var cold=new McpOAuthService(new JdbcSqlDatabase(datasource),mapper,origin+"/callback"); cold.init();
            assertEquals("fixture-access",cold.getAccessToken("fixture"));
            sql.update("UPDATE mcp_oauth_tokens SET expires_at=0 WHERE server_name=?","fixture");
            assertEquals("fixture-refreshed",cold.getAccessToken("fixture"));
            assertEquals("refresh_token",body.get().get("grant_type"));
            var repository=new SqliteMcpServerRepository(sql,mapper); repository.init();
            var definition=new ExternalServerDef(); definition.setName("fixture"); definition.setCommand("fixture-command"); definition.setOauth(oauth); repository.save(definition);
            var reopened=new SqliteMcpServerRepository(new JdbcSqlDatabase(datasource),mapper); reopened.init();
            assertEquals("fixture-client",reopened.findByName("fixture").orElseThrow().getOauth().getClientId());
            reopened.setEnabled("fixture",false); assertTrue(reopened.findAutoConnectEnabled().isEmpty());
            cold.removeTokens("fixture"); assertEquals("not_authenticated",cold.getAuthStatus("fixture"));
        } finally { server.stop(0); }
    }
}
