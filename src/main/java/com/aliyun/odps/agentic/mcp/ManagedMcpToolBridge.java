package com.aliyun.odps.agentic.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.aliyun.odps.agentic.tool.SkillParameter;
import com.aliyun.odps.agentic.tool.SkillResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aliyun.odps.agentic.tool.ContextTool;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges tools from external MCP servers into a context tool registry,
 * so the agent can call them as regular skills.
 *
 * Uses langchain4j McpClient for tool discovery and execution.
 */
public class ManagedMcpToolBridge<C> implements com.aliyun.odps.agentic.mcp.ManagedMcpManager.ToolBridge {

    private static final Logger log = LoggerFactory.getLogger(ManagedMcpToolBridge.class);

    private final java.util.function.Consumer<ContextTool<C,SkillResult>> register;
    private final java.util.function.Consumer<String> unregister;
    private final ObjectMapper objectMapper;
    private final Map<String, List<String>> bridgedSkillNames = new ConcurrentHashMap<>();

    public ManagedMcpToolBridge(java.util.function.Consumer<ContextTool<C,SkillResult>> register,
                                java.util.function.Consumer<String> unregister,ObjectMapper mapper) {
        this.register=register; this.unregister=unregister; this.objectMapper=mapper;
    }

    /**
     * Bridge tools from an MCP server into the SkillRegistry.
     */
    public int bridgeTools(String serverName, List<ToolSpecification> tools, McpClient mcpClient) {
        List<String> names = new ArrayList<>();
        for (ToolSpecification spec : tools) {
            String skillName = "mcp_" + serverName + "_" + spec.name();
            ContextTool<C,SkillResult> skill = new McpBridgedSkill<>(skillName, spec, serverName, mcpClient, objectMapper);
            register.accept(skill);
            names.add(skillName);
        }
        bridgedSkillNames.put(serverName, names);
        log.info("[McpToolBridge] Bridged {} tools from MCP server '{}'", names.size(), serverName);
        return names.size();
    }

    public void unbridgeTools(String serverName) {
        List<String> names = bridgedSkillNames.remove(serverName);
        if (names != null) {
            for (String name : names) {
                unregister.accept(name);
            }
            log.info("[McpToolBridge] Unbridged {} tools from MCP server '{}'", names.size(), serverName);
        }
    }

    public List<String> getBridgedSkillNames(String serverName) {
        return bridgedSkillNames.getOrDefault(serverName, List.of());
    }

    /**
     * A Skill implementation that delegates execution to an MCP server via langchain4j McpClient.
     */
    private static class McpBridgedSkill<C> implements ContextTool<C,SkillResult> {

        private final String skillName;
        private final ToolSpecification spec;
        private final String serverName;
        private final McpClient mcpClient;
        private final ObjectMapper objectMapper;
        private final SkillParameter[] parameters;

        McpBridgedSkill(String skillName, ToolSpecification spec,
                        String serverName, McpClient mcpClient, ObjectMapper objectMapper) {
            this.skillName = skillName;
            this.spec = spec;
            this.serverName = serverName;
            this.mcpClient = mcpClient;
            this.objectMapper = objectMapper;
            this.parameters = extractParameters(spec);
        }

        @Override
        public String getName() { return skillName; }

        @Override
        public String getDescription() {
            return "[MCP:" + serverName + "] " + (spec.description() != null ? spec.description() : "");
        }

        @Override
        public SkillParameter[] getParameters() { return parameters; }

        @Override
        public SkillResult execute(Map<String, Object> args, C context) {
            try {
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .name(spec.name())
                    .arguments(objectMapper.writeValueAsString(args != null ? args : Map.of()))
                    .build();
                ToolExecutionResult result = mcpClient.executeTool(request);
                String text = result.resultText();
                if (result.isError()) {
                    return SkillResult.failure(text);
                }
                return SkillResult.success(text);
            } catch (Exception e) {
                log.error("[McpBridgedSkill] Failed to call MCP tool {}/{}: {}",
                        serverName, spec.name(), e.getMessage());
                return SkillResult.failure("MCP tool call failed: " + e.getMessage());
            }
        }

        private static SkillParameter[] extractParameters(ToolSpecification spec) {
            JsonObjectSchema params = spec.parameters();
            if (params == null || params.properties() == null) {
                return new SkillParameter[0];
            }

            Set<String> required = params.required() != null
                ? new HashSet<>(params.required()) : Set.of();

            List<SkillParameter> result = new ArrayList<>();
            for (Map.Entry<String, JsonSchemaElement> entry : params.properties().entrySet()) {
                String name = entry.getKey();
                JsonSchemaElement schema = entry.getValue();
                String type = schemaTypeName(schema);
                String desc = schema.description() != null ? schema.description() : "";
                boolean isRequired = required.contains(name);

                if (schema instanceof JsonEnumSchema enumSchema && enumSchema.enumValues() != null) {
                    result.add(new SkillParameter(name, type, desc, isRequired, enumSchema.enumValues()));
                } else {
                    result.add(new SkillParameter(name, type, desc, isRequired));
                }
            }
            return result.toArray(new SkillParameter[0]);
        }

        private static String schemaTypeName(JsonSchemaElement schema) {
            String className = schema.getClass().getSimpleName();
            return switch (className) {
                case "JsonStringSchema" -> "string";
                case "JsonNumberSchema" -> "number";
                case "JsonIntegerSchema" -> "integer";
                case "JsonBooleanSchema" -> "boolean";
                case "JsonArraySchema" -> "array";
                case "JsonObjectSchema" -> "object";
                case "JsonEnumSchema" -> "string";
                default -> "string";
            };
        }
    }
}
