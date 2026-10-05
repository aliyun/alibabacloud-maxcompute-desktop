package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

/**
 * 计划工具，用于在只读规划模式与执行模式之间切换。
 * 进入计划模式后只能观察和分析，退出后才允许修改文件和执行变更。
 */
public class PlanTool implements ToolDef {

    private final String id;

    /**
     * 创建计划工具实例。
     */
    public PlanTool() {
        this.id = "plan";
    }

    /**
     * 返回工具 ID。
     */
    @Override
    public String getId() { return id; }

    @Override
    public String getDescription() {
        return "Switch between plan (read-only) and build (execution) modes. " +
               "Use plan_enter to start planning (no edits allowed), plan_exit to return to building.";
    }

    /**
     * 返回 plan 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "action": "plan_enter"
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");

        ObjectNode actionProp = props.putObject("action");
        actionProp.put("type", "string");
        actionProp.put("description", "The mode transition: 'plan_enter' to enter plan mode, 'plan_exit' to exit plan mode");
        actionProp.putPOJO("enum", java.util.List.of("plan_enter", "plan_exit"));

        schema.putArray("required").add("action");
        return schema;
    }

    /**
     * 切换规划模式。
     *
     * <p>执行逻辑很简单：读取 {@code action} 参数，
     * {@code plan_enter} 返回只读规划提示，{@code plan_exit} 返回可执行提示。
     * 该工具本身不直接修改任何全局状态，真正的模式切换由上层运行时根据返回结果解释和执行。
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        String action = args.path("action").asText("");
        return switch (action) {
            case "plan_enter" -> ToolResult.of("plan_enter",
                "Entered plan mode. You are now in READ-ONLY phase. " +
                "STRICTLY FORBIDDEN: ANY file edits, modifications, or system changes. " +
                "You may ONLY observe, analyze, and plan.");
            case "plan_exit" -> ToolResult.of("plan_exit",
                "Exited plan mode. You are now in BUILD mode. " +
                "You may make file changes, run shell commands, and use tools.");
            default -> ToolResult.error("Invalid action: " + action + ". Use 'plan_enter' or 'plan_exit'.");
        };
    }
}
