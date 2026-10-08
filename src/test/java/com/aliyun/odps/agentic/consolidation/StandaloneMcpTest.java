package com.aliyun.odps.agentic.consolidation;
import com.aliyun.odps.agentic.mcp.*;
import com.aliyun.odps.agentic.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.*;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class StandaloneMcpTest {
    @Test @Timeout(30) void actualStdioHandshakeToolBridgeResourcesPromptsAndReconnect() throws Exception {
        var mapper=new ObjectMapper();
        var definitions=new HashMap<String,ExternalServerDef>();
        var repository=new ManagedMcpManager.ServerRepository() {
            public List<ExternalServerDef> findAll() { return new ArrayList<>(definitions.values()); }
            public List<ExternalServerDef> findAutoConnectEnabled() { return findAll(); }
            public Optional<ExternalServerDef> findByName(String name) { return Optional.ofNullable(definitions.get(name)); }
            public void save(ExternalServerDef value) { definitions.put(value.getName(),value); }
            public void delete(String name) { definitions.remove(name); }
            public void setEnabled(String name,boolean enabled) { definitions.get(name).setEnabled(enabled); }
        };
        try (var registry=new ContextToolRegistry<Void,SkillResult,ContextTool<Void,SkillResult>>()) {
            var bridge=new ManagedMcpToolBridge<Void>(registry::register,registry::unregister,mapper);
            try (var manager=new ManagedMcpManager(bridge,repository,name -> null,mapper,() -> System.getenv("PATH"))) {
                var definition=new ExternalServerDef(); definition.setName("fixture");
                definition.setCommand(Path.of(System.getProperty("java.home"),"bin","java").toString());
                definition.setArgs(List.of("-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),Fixture.class.getName()));
                definition.setTimeoutMs(5000); manager.connectServer(definition);
                assertTrue(manager.isConnected("fixture")); assertTrue(registry.hasSkill("mcp_fixture_echo"));
                var result=registry.executeSkill("mcp_fixture_echo",Map.of("text","SDK_MCP_OK"),null);
                assertEquals(true,result.get("success")); assertTrue(mapper.writeValueAsString(result).contains("SDK_MCP_OK"));
                assertEquals(1,manager.listResources("fixture").size());
                assertNotNull(manager.readResource("fixture","fixture://memory"));
                assertEquals(1,manager.listPrompts("fixture").size());
                assertNotNull(manager.getPrompt("fixture","verify",Map.of()));
                manager.reconnect("fixture"); assertTrue(manager.isConnected("fixture"));
                assertEquals(1,bridge.getBridgedSkillNames("fixture").size());
                manager.disconnectServer("fixture"); assertFalse(registry.hasSkill("mcp_fixture_echo"));
            }
        }
    }
    /** Real JSON-RPC subprocess: no network, mocks, or host framework. */
    public static class Fixture {
        public static void main(String[] args) throws Exception {
            var mapper=new ObjectMapper();
            var input=new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            for (String line;(line=input.readLine())!=null;) {
                var request=mapper.readTree(line); if (!request.has("id")) continue;
                Object result=switch (request.path("method").asText()) {
                    case "initialize" -> Map.of("protocolVersion","2024-11-05","capabilities",Map.of("tools",Map.of(),"resources",Map.of(),"prompts",Map.of()),"serverInfo",Map.of("name","fixture","version","1"));
                    case "tools/list" -> Map.of("tools",List.of(Map.of("name","echo","description","Echo text","inputSchema",Map.of("type","object","properties",Map.of("text",Map.of("type","string")),"required",List.of("text")))));
                    case "tools/call" -> Map.of("content",List.of(Map.of("type","text","text",request.path("params").path("arguments").path("text").asText())),"isError",false);
                    case "resources/list" -> Map.of("resources",List.of(Map.of("uri","fixture://memory","name","memory","mimeType","text/plain")));
                    case "resources/read" -> Map.of("contents",List.of(Map.of("uri","fixture://memory","mimeType","text/plain","text","SDK_RESOURCE_OK")));
                    case "prompts/list" -> Map.of("prompts",List.of(Map.of("name","verify","description","Verify SDK")));
                    case "prompts/get" -> Map.of("messages",List.of(Map.of("role","user","content",Map.of("type","text","text","Verify SDK"))));
                    default -> Map.of();
                };
                System.out.println(mapper.writeValueAsString(Map.of("jsonrpc","2.0","id",request.get("id"),"result",result))); System.out.flush();
            }
        }
    }
}
