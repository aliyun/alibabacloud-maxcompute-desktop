package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内置待办工具，用于维护结构化任务列表。
 * 每次调用都会用新的待办项列表整体替换当前状态。
 */
public class TodoTool implements ToolDef {

    private static final String ID = "todowrite";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<TodoItem> todos = new CopyOnWriteArrayList<>();

    @Override
    public String getId() { return ID; }

    @Override
    public String getDescription() {
        return ResourceLoader.load("tools/todowrite.txt");
    }

    /**
     * 返回 todowrite 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "todos": [
     *     {"content": "Fix login bug",    "status": "in_progress", "priority": "high"},
     *     {"content": "Write unit tests", "status": "pending",     "priority": "medium"}
     *   ]
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode todosSchema = MAPPER.createObjectNode();
        todosSchema.put("type", "array");
        todosSchema.put("description", "The updated todo list");

        ObjectNode todoItem = MAPPER.createObjectNode();
        todoItem.put("type", "object");
        ObjectNode itemProps = MAPPER.createObjectNode();

        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "string");
        content.put("description", "Brief description of the task");
        itemProps.set("content", content);

        ObjectNode status = MAPPER.createObjectNode();
        status.put("type", "string");
        status.put("description", "Current status of the task: pending, in_progress, completed, cancelled");
        itemProps.set("status", status);

        ObjectNode priority = MAPPER.createObjectNode();
        priority.put("type", "string");
        priority.put("description", "Priority level of the task: high, medium, low");
        itemProps.set("priority", priority);

        todoItem.set("properties", itemProps);
        ArrayNode req = MAPPER.createArrayNode();
        req.add("content");
        req.add("status");
        req.add("priority");
        todoItem.set("required", req);
        todosSchema.set("items", todoItem);
        properties.set("todos", todosSchema);

        schema.set("properties", properties);
        ArrayNode required = MAPPER.createArrayNode();
        required.add("todos");
        schema.set("required", required);

        return schema;
    }

    /**
     * 整体替换待办列表。
     *
     * <p>执行流程：
     * <ol>
     *   <li>从 {@code args.todos} 数组中解析每个待办项的 {@code content}、{@code status} 和 {@code priority}</li>
     *   <li>用新列表整体替换当前内存中的待办项（非增量更新）</li>
     *   <li>统计未完成项数量，连同序列化后的完整列表作为结果返回</li>
     * </ol>
     *
     * <p>注意：每次调用都会完全覆盖之前的状态，调用方需在新列表中包含所有需要保留的待办项。
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        JsonNode todosNode = args.get("todos");
        if (todosNode == null || !todosNode.isArray()) {
            return ToolResult.error("Invalid todos format — expected an array");
        }

        List<TodoItem> newTodos = new ArrayList<>();
        for (JsonNode item : todosNode) {
            String content = item.has("content") ? item.get("content").asText() : "";
            String status = item.has("status") ? item.get("status").asText() : "pending";
            String priority = item.has("priority") ? item.get("priority").asText() : "medium";
            newTodos.add(new TodoItem(content, status, priority));
        }

        todos.clear();
        todos.addAll(newTodos);

        long remaining = newTodos.stream().filter(t -> !"completed".equals(t.status())).count();
        try {
            return ToolResult.of(remaining + " todos", MAPPER.writeValueAsString(newTodos));
        } catch (Exception e) {
            return ToolResult.of(remaining + " todos", newTodos.toString());
        }
    }

    /**
     * 获取当前待办项快照。
     *
     * @return 当前待办项的只读副本
     */
    public List<TodoItem> getTodos() {
        return List.copyOf(todos);
    }

    /**
     * 待办项定义。
     *
     * @param content  任务内容
     * @param status   任务状态
     * @param priority 任务优先级
     */
    public record TodoItem(String content, String status, String priority) {}
}
