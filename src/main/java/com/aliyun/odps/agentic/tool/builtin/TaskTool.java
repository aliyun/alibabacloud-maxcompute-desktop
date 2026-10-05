package com.aliyun.odps.agentic.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.aliyun.odps.agentic.tool.ToolContext;
import com.aliyun.odps.agentic.tool.ToolDef;
import com.aliyun.odps.agentic.tool.ToolResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * 内置任务工具，用于启动子代理处理复杂任务。
 * 支持前台执行、后台执行和基于 {@code task_id} 的任务续跑。
 *
 * <p>工具通过可插拔的子代理执行器运行任务，并以统一的 XML 结构返回结果。
 */
public class TaskTool implements ToolDef {

    private static final Logger log = LoggerFactory.getLogger(TaskTool.class);

    private static final String ID = "task";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 后台模式描述。 */
    private static final String BACKGROUND_DESCRIPTION =
        "Background mode: background=true launches the subagent asynchronously and returns immediately. "
        + "Foreground is the default; use it when you need the result before continuing. "
        + "Use background only for independent work that can run while you continue elsewhere. "
        + "You will be notified automatically when it finishes.";

    /** 后台任务启动消息。 */
    private static final String BACKGROUND_STARTED =
        "The task is working in the background. You will be notified automatically when it finishes.\n"
        + "DO NOT sleep, poll for progress, ask the task for status, or duplicate this task's work "
        + "— avoid working with the same files or topics it is using.\n"
        + "Work on non-overlapping tasks, or briefly tell the user what you launched and end your response.";

    private volatile Function<SubAgentRequest, SubAgentResult> subAgentRunner;
    private final Map<String, SubAgentResult> completedTasks = new ConcurrentHashMap<>();
    private String availableAgentsDescription = "";
    private boolean backgroundSubagentsEnabled = false;

    /**
     * 创建任务工具实例。
     */
    public TaskTool() {}

    /**
     * 设置子代理执行器。
     *
     * @param runner 子代理执行器
     */
    public void setSubAgentRunner(Function<SubAgentRequest, SubAgentResult> runner) {
        this.subAgentRunner = runner;
    }

    /**
     * 设置可用代理说明文本。
     *
     * @param desc 说明文本
     */
    public void setAvailableAgentsDescription(String desc) {
        this.availableAgentsDescription = desc;
    }

    /**
     * 启用或禁用后台子代理能力。
     *
     * @param enabled 是否启用
     */
    public void setBackgroundSubagentsEnabled(boolean enabled) {
        this.backgroundSubagentsEnabled = enabled;
    }

    @Override
    public String getId() { return ID; }

    @Override
    public String getDescription() {
        // 如果启用了后台子代理，追加后台模式描述
        String base = ResourceLoader.load("tools/task.txt");
        if (backgroundSubagentsEnabled) {
            base = base + "\n\n" + BACKGROUND_DESCRIPTION;
        }
        if (availableAgentsDescription != null && !availableAgentsDescription.isEmpty()) {
            return base + "\n\n" + availableAgentsDescription;
        }
        return base;
    }

    /**
     * 返回 task 工具的参数 Schema。
     *
     * <p>对应参数示例：
     * <pre>{@code
     * {
     *   "description": "Fix auth bug",
     *   "prompt": "Find and fix the authentication bug in LoginService.java",
     *   "subagent_type": "general",
     *   "background": false
     * }
     * }</pre>
     */
    @Override
    public ObjectNode getParametersSchema() {
        // 参数 Schema 定义
        ObjectNode schema = MAPPER.createObjectNode();
        schema.put("type", "object");

        ObjectNode properties = MAPPER.createObjectNode();

        ObjectNode description = MAPPER.createObjectNode();
        description.put("type", "string");
        description.put("description", "A short (3-5 words) description of the task");
        properties.set("description", description);

        ObjectNode prompt = MAPPER.createObjectNode();
        prompt.put("type", "string");
        prompt.put("description", "The task for the agent to perform");
        properties.set("prompt", prompt);

        ObjectNode subagentType = MAPPER.createObjectNode();
        subagentType.put("type", "string");
        subagentType.put("description", "The type of specialized agent to use for this task");
        properties.set("subagent_type", subagentType);

        ObjectNode taskId = MAPPER.createObjectNode();
        taskId.put("type", "string");
        taskId.put("description",
            "This should only be set if you mean to resume a previous task "
            + "(you can pass a prior task_id and the task will continue the same "
            + "subagent session as before instead of creating a fresh one)");
        properties.set("task_id", taskId);

        ObjectNode command = MAPPER.createObjectNode();
        command.put("type", "string");
        command.put("description", "The command that triggered this task");
        properties.set("command", command);

        // 仅在启用时添加 background 参数
        if (backgroundSubagentsEnabled) {
            ObjectNode background = MAPPER.createObjectNode();
            background.put("type", "boolean");
            background.put("description",
                "Run the agent in the background. You will be notified when it completes. "
                + "DO NOT sleep, poll, or proactively check on its progress.");
            properties.set("background", background);
        }

        schema.set("properties", properties);
        ArrayNode required = MAPPER.createArrayNode();
        required.add("description");
        required.add("prompt");
        required.add("subagent_type");
        schema.set("required", required);

        return schema;
    }

    /**
     * 启动或续跑子代理任务。
     *
     * <p>执行流程：
     * <ol>
     *   <li>提取 {@code prompt}（子代理提示词）、{@code description}、{@code subagent_type}、
     *       {@code task_id}（续跑时指定）和 {@code background}（是否后台执行）</li>
     *   <li>后台模式需要通过 {@link #setBackgroundSubagentsEnabled(boolean)} 显式启用</li>
     *   <li>通过 {@link ToolContext#permissionAsker()} 请求任务执行权限</li>
     *   <li>调用已注册的 {@code subAgentRunner} 执行子代理，传入 {@link SubAgentRequest}</li>
     *   <li>前台模式等待子代理完成后返回结果；后台模式立即返回 "running" 状态</li>
     *   <li>结果以 XML 格式输出（{@code <task>} 包裹，含 {@code <task_result>} 或 {@code <task_error>}）</li>
     * </ol>
     */
    @Override
    public ToolResult execute(JsonNode args, ToolContext context) {
        if (!args.has("prompt") || args.get("prompt").isNull()) {
            return ToolResult.error("prompt is required");
        }
        String prompt = args.get("prompt").asText();
        String description = args.has("description") && !args.get("description").isNull()
            ? args.get("description").asText() : "Sub-task";
        String subagentType = args.has("subagent_type") && !args.get("subagent_type").isNull()
            ? args.get("subagent_type").asText() : "general";
        String taskId = args.has("task_id") && !args.get("task_id").isNull()
            ? args.get("task_id").asText() : UUID.randomUUID().toString();
        boolean runInBackground = args.has("background")
            && args.get("background").asBoolean(false);

        // 后台子代理需要显式启用
        if (runInBackground && !backgroundSubagentsEnabled) {
            return ToolResult.error(
                "Background subagents require OPENCODE_EXPERIMENTAL_BACKGROUND_SUBAGENTS=true");
        }

        // 权限检查
        if (context.permissionAsker() != null) {
            boolean allowed = context.permissionAsker().ask(
                ID, subagentType, description);
            if (!allowed) {
                return ToolResult.error("Permission denied for task: " + description);
            }
        }

        // 上报任务元数据
        if (context.progressReporter() != null) {
            context.progressReporter().report(description, Map.of(
                "parentSessionId", context.sessionId(),
                "sessionId", taskId,
                "background", runInBackground
            ));
        }

        if (subAgentRunner != null) {
            try {
                SubAgentResult result = subAgentRunner.apply(
                    new SubAgentRequest(taskId, prompt, description, subagentType,
                        runInBackground, context));

                completedTasks.put(taskId, result);

                // 渲染任务输出
                String state = result.state();
                if (runInBackground && "running".equals(state)) {
                    return ToolResult.of(
                        description,
                        renderOutput(taskId, "running",
                            "Background task started", BACKGROUND_STARTED)
                    );
                }

                String tag = "error".equals(state) ? "task_error" : "task_result";
                return ToolResult.of(
                    description,
                    renderOutput(taskId, state, description, result.output())
                );
            } catch (Exception e) {
                return ToolResult.of(
                    description,
                    renderOutput(taskId, "error", description,
                        "Error: " + e.getMessage())
                );
            }
        }

        return ToolResult.of(
            description,
            renderOutput(taskId, "completed", description,
                "Sub-agent execution not configured. Prompt: " + prompt)
        );
    }

    /**
     * 将任务结果渲染为 XML 格式输出。
     */
    private String renderOutput(String sessionId, String state, String summary, String text) {
        String tag = "error".equals(state) ? "task_error" : "task_result";
        StringBuilder sb = new StringBuilder();
        sb.append("<task id=\"").append(sessionId).append("\" state=\"").append(state).append("\">\n");
        if (summary != null && !summary.isEmpty()) {
            sb.append("<summary>").append(summary).append("</summary>\n");
        }
        sb.append("<").append(tag).append(">\n");
        sb.append(text).append("\n");
        sb.append("</").append(tag).append(">\n");
        sb.append("</task>");
        return sb.toString();
    }

    /**
     * 子代理请求。
     *
     * @param taskId        任务 ID
     * @param prompt        子代理要执行的提示词
     * @param description   任务简述
     * @param subagentType  子代理类型
     * @param background    是否后台执行
     * @param parentContext 父工具上下文
     */
    public record SubAgentRequest(
        String taskId,
        String prompt,
        String description,
        String subagentType,
        boolean background,
        ToolContext parentContext
    ) {}

    /**
     * 子代理结果。
     *
     * @param output 任务输出内容
     * @param state  任务状态
     */
    public record SubAgentResult(String output, String state) {
        public SubAgentResult(String output) {
            this(output, "completed");
        }
    }
}
