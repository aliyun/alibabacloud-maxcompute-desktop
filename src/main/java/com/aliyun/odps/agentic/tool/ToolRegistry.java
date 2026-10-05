package com.aliyun.odps.agentic.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.agent.AgentDef;
import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.Session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表，负责解析和管理可用工具。
 * 支持内置工具与自定义工具的注册、查找、筛选和参数校验。
 *
 * <p>工具解析顺序为：内置工具、代理定义工具、自定义工具以及按条件启用的工具。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    /**
     * 内置工具集合，在启动时初始化，且不可移除。
     */
    private final Map<String, ToolDef> builtinTools = new LinkedHashMap<>();

    /**
     * 自定义工具集合，可在运行时动态增删。
     */
    private final Map<String, ToolDef> customTools = new ConcurrentHashMap<>();

    /**
     * 创建一个空的工具注册表。
     */
    public ToolRegistry() {}

    /**
     * 注册一个内置工具。
     *
     * @param tool 待注册的工具
     */
    public void register(ToolDef tool) {
        builtinTools.put(tool.getId(), tool);
        log.debug("Registered built-in tool: {}", tool.getId());
    }

    /**
     * 注册一个自定义工具，可在运行时动态添加。
     */
    public void registerCustom(ToolDef tool) {
        customTools.put(tool.getId(), tool);
        log.debug("Registered custom tool: {}", tool.getId());
    }

    /**
     * 按 ID 注销工具。
     * 仅自定义工具可被注销，内置工具会被永久保留。
     *
     * @return 如果找到并移除了工具则返回 {@code true}
     */
    public boolean unregister(String toolId) {
        ToolDef removed = customTools.remove(toolId);
        if (removed != null) {
            log.debug("Unregistered custom tool: {}", toolId);
            return true;
        }
        log.debug("Tool not found for unregistration (or is built-in): {}", toolId);
        return false;
    }

    /**
     * 按 ID 获取工具，同时搜索内置工具和自定义工具。
     */
    public Optional<ToolDef> get(String toolId) {
        ToolDef tool = builtinTools.get(toolId);
        if (tool != null) return Optional.of(tool);
        return Optional.ofNullable(customTools.get(toolId));
    }

    /**
     * 按 ID 解析工具，未找到时返回 {@code null}。
     */
    public ToolDef resolve(String toolId) {
        ToolDef tool = builtinTools.get(toolId);
        return tool != null ? tool : customTools.get(toolId);
    }

    /**
     * 获取所有已注册工具的 ID 列表。
     */
    public List<String> ids() {
        List<String> result = new ArrayList<>(builtinTools.keySet());
        result.addAll(customTools.keySet());
        return result;
    }

    /**
     * 获取所有已注册工具的只读集合，包含内置工具和自定义工具。
     */
    public Collection<ToolDef> getAll() {
        List<ToolDef> all = new ArrayList<>(builtinTools.values());
        all.addAll(customTools.values());
        return Collections.unmodifiableCollection(all);
    }

    /**
     * 以列表形式获取所有已注册工具。
     */
    public List<ToolDef> all() {
        List<ToolDef> all = new ArrayList<>(builtinTools.values());
        all.addAll(customTools.values());
        return List.copyOf(all);
    }

    /**
     * 根据给定的代理、模型和会话信息解析可用工具集合。
     * 该过程会结合模型特性、代理声明和包含列表进行筛选。
     *
     * @param agent    代理定义
     * @param model    当前模型
     * @param session  当前会话
     * @param messages 当前消息列表
     * @return 工具 ID 到工具定义的映射
     */
    public Map<String, ToolDef> resolveTools(
        AgentDef agent,
        Model model,
        Session session,
        List<Message> messages
    ) {
        Map<String, ToolDef> resolved = new LinkedHashMap<>();

        // 按模型特性筛选工具
        String modelId = model != null ? model.apiId() : "";
        boolean usePatch = modelId.contains("gpt-") && !modelId.contains("oss") && !modelId.contains("gpt-4");

        for (ToolDef tool : all()) {
            String id = tool.getId();

            // 仅为特定 GPT 模型启用 apply_patch
            if ("apply_patch".equals(id) && !usePatch) continue;
            if (("edit".equals(id) || "write".equals(id)) && usePatch) continue;

            resolved.put(id, tool);
        }

        // 添加代理定义的工具
        for (ToolDef tool : agent.getTools()) {
            resolved.put(tool.getId(), tool);
        }

        // 如果代理声明了 included tools，则进一步过滤
        Set<String> included = agent.getIncludedTools();
        if (included != null && !included.isEmpty()) {
            resolved.keySet().retainAll(included);
        }

        return resolved;
    }

    /**
     * 使用工具的 JSON Schema 校验工具调用参数。
     * 当前会检查必填字段和字段类型是否匹配。
     *
     * @param tool 工具定义
     * @param args 待校验的参数
     * @return 校验错误列表；为空表示通过校验
     */
    public List<String> validateArgs(ToolDef tool, JsonNode args) {
        List<String> errors = new ArrayList<>();
        ObjectNode schema = tool.getParametersSchema();

        if (schema == null) return errors;
        if (args == null || args.isNull()) {
            // 检查是否存在必填字段
            if (schema.has("required") && schema.get("required").isArray()) {
                for (JsonNode req : schema.get("required")) {
                    errors.add("Missing required field: " + req.asText());
                }
            }
            return errors;
        }

        // 检查必填字段
        if (schema.has("required") && schema.get("required").isArray()) {
            for (JsonNode req : schema.get("required")) {
                String fieldName = req.asText();
                if (!args.has(fieldName) || args.get(fieldName).isNull()) {
                    errors.add("Missing required field: " + fieldName);
                }
            }
        }

        // 检查字段类型
        if (schema.has("properties") && schema.get("properties").isObject()) {
            ObjectNode properties = (ObjectNode) schema.get("properties");
            Iterator<Map.Entry<String, JsonNode>> fields = args.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                JsonNode value = entry.getValue();

                if (!properties.has(fieldName)) {
                    // 未知字段当前不视为错误，直接跳过
                    continue;
                }

                JsonNode propSchema = properties.get(fieldName);
                if (propSchema.has("type") && !value.isNull()) {
                    String expectedType = propSchema.get("type").asText();
                    boolean typeValid = switch (expectedType) {
                        case "string" -> value.isTextual();
                        case "number", "integer" -> value.isNumber();
                        case "boolean" -> value.isBoolean();
                        case "array" -> value.isArray();
                        case "object" -> value.isObject();
                        default -> true;
                    };
                    if (!typeValid) {
                        errors.add("Field '" + fieldName + "' expected type '" + expectedType
                            + "' but got '" + value.getNodeType().toString().toLowerCase() + "'");
                    }
                }
            }
        }

        return errors;
    }

    /**
     * 将工具定义转换为供 LLM 使用的 JSON Schema 映射。
     */
    public Map<String, Object> toToolsSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        for (ToolDef tool : all()) {
            Map<String, Object> toolSchema = new LinkedHashMap<>();
            toolSchema.put("name", tool.getId());
            toolSchema.put("description", tool.getDescription());
            toolSchema.put("inputSchema", tool.getParametersSchema());
            schema.put(tool.getId(), toolSchema);
        }
        return schema;
    }
}
