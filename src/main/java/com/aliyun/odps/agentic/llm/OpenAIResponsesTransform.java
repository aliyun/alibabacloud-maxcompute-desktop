package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;

/**
 * OpenAI Responses API 转换器 — 在标准 {@link LLMEvent} 与 OpenAI Responses API 格式之间进行转换。
 *
 * <p>Responses API 与 Chat Completions API 存在根本性差异：
 * <ul>
 *   <li>端点：{@code /v1/responses}（而非 {@code /v1/chat/completions}）</li>
 *   <li>请求：使用 {@code input} 数组（而非 {@code messages}），扁平化工具格式</li>
 *   <li>工具：{@code function_call}/{@code function_call_output} 项（而非 delta 中的 {@code tool_calls}）</li>
 *   <li>流式传输：类型化 SSE 事件（{@code response.output_text.delta} 等）</li>
 *   <li>用量统计：{@code input_tokens}/{@code output_tokens}（而非 {@code prompt_tokens}/{@code completion_tokens}）</li>
 *   <li>托管工具：内置 web_search、file_search、code_interpreter、computer_use 等</li>
 *   <li>推理：推理项包含摘要部分和加密内容</li>
 * </ul>
 *
 * <p>SSE 事件流程（Responses API）：
 * <pre>
 * response.created -> response.in_progress ->
 *   response.output_item.added (reasoning) -> reasoning_summary_part.added ->
 *     reasoning_summary_text.delta* -> reasoning_summary_part.done ->
 *   response.output_item.done (reasoning) ->
 *   response.output_item.added (function_call) ->
 *     response.function_call_arguments.delta* ->
 *   response.output_item.done (function_call) ->
 *   response.content_part.added -> response.output_text.delta* ->
 *     response.content_part.done ->
 *   response.output_item.done (message) ->
 * response.completed / response.incomplete / response.failed
 * </pre>
 */
public class OpenAIResponsesTransform implements ProviderTransform {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    private static final String PATH = "/responses";

    // ── 托管工具定义 ─────────────────────────────────────────────────
    // 将项类型映射到工具显示名称。
    private static final Map<String, String> HOSTED_TOOL_NAMES = Map.of(
        "web_search_call", "web_search",
        "web_search_preview_call", "web_search_preview",
        "file_search_call", "file_search",
        "code_interpreter_call", "code_interpreter",
        "computer_use_call", "computer_use",
        "image_generation_call", "image_generation",
        "mcp_call", "mcp",
        "local_shell_call", "local_shell"
    );

    // ── 解析器状态 ─────────────────────────────────────────────────
    // 跨 SSE 事件跟踪工具调用和 function_call 标志。
    // 使用 ThreadLocal 保证并发流的线程安全性。
    private static final class ParserState {
        /** 按 item_id 跟踪的工具调用 — Responses API 通过 item ID 而非索引标识工具。 */
        final Map<String, ToolCallAcc> toolCalls = new HashMap<>();
        /** 是否有 function_call 已完成 — 用于决定结束原因。 */
        boolean hasFunctionCall = false;
    }

    private record ToolCallAcc(String callId, String name, StringBuilder args, boolean finished) {}

    private static final ThreadLocal<ParserState> parserState =
        ThreadLocal.withInitial(ParserState::new);

    // ── 请求构建 ─────────────────────────────────────────────────────

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, Map<String, String> apiKeys) throws Exception {
        String apiKey = apiKeys.getOrDefault("openai",
            apiKeys.getOrDefault("default", ""));
        String baseUrl = apiKeys.getOrDefault("openai_base_url",
            System.getenv().getOrDefault("OPENAI_BASE_URL", DEFAULT_BASE_URL));
        return buildHttpRequest(request, baseUrl, apiKey);
    }

    /**
     * 为 Responses API 构建 HTTP 请求。
     *
     * <p>构建请求体包含 model、input、tools、tool_choice、stream、
     * max_output_tokens、temperature 等字段。
     */
    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, String baseUrl, String apiKey) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model().apiId());
        body.put("stream", true);

        // max_output_tokens（而非 max_tokens）— Responses API 的命名方式
        if (request.maxTokens() != null) {
            body.put("max_output_tokens", request.maxTokens());
        } else if (request.model().limit().output() != null) {
            body.put("max_output_tokens", request.model().limit().output());
        }

        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }

        // 构建 input 数组 — 从 OpenAI Chat Completions 格式转换为 Responses API 输入项
        ArrayNode input = lowerMessages(request);
        body.set("input", input);

        // 工具 — Responses API 使用扁平格式：
        //   {type: "function", name: "...", description: "...", parameters: {...}}
        // 而非 Chat Completions 的嵌套格式：
        //   {type: "function", function: {name: "...", ...}}
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsArray = MAPPER.createArrayNode();
            for (var entry : request.tools().entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> toolMap) {
                    ObjectNode toolObj = MAPPER.createObjectNode();
                    toolObj.put("type", "function");
                    toolObj.put("name", entry.getKey());
                    if (toolMap.containsKey("description")) {
                        toolObj.put("description", String.valueOf(toolMap.get("description")));
                    }
                    if (toolMap.containsKey("inputSchema")) {
                        toolObj.set("parameters",
                            ProviderTransform.openAiToolInputSchema(MAPPER.valueToTree(toolMap.get("inputSchema"))));
                    }
                    toolsArray.add(toolObj);
                }
            }
            body.set("tools", toolsArray);
        }

        // 工具选择策略
        if (request.toolChoice() != null) {
            String tc = request.toolChoice();
            if ("auto".equals(tc) || "none".equals(tc) || "required".equals(tc)) {
                body.put("tool_choice", tc);
            }
        }

        // 确保 URL 以 /responses 结尾
        if (!baseUrl.endsWith(PATH)) {
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl + "responses";
            } else {
                baseUrl = baseUrl + PATH;
            }
        }

        // 重置解析器状态
        parserState.remove();

        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
    }

    // ── 消息降级 ─────────────────────────────────────────────────────
    // 将 Chat Completions 格式转换为 Responses API input 项：
    //   system  -> {role: "system", content: "..."}
    //   user    -> {role: "user", content: [{type: "input_text", text: "..."}]}
    //   assistant text -> {role: "assistant", content: [{type: "output_text", text: "..."}]}
    //   assistant tool_calls -> {type: "function_call", call_id, name, arguments}
    //   tool result -> {type: "function_call_output", call_id, output}

    /**
     * 将请求中的消息降级为 Responses API 的 input 数组。
     */
    private ArrayNode lowerMessages(LlmRequest request) {
        ArrayNode input = MAPPER.createArrayNode();

        // 系统提示词 — 合并为单个 system 输入项
        if (request.system() != null && !request.system().isEmpty()) {
            ObjectNode sysItem = MAPPER.createObjectNode();
            sysItem.put("role", "system");
            sysItem.put("content", String.join("\n\n", request.system()));
            input.add(sysItem);
        }

        // 转换会话消息
        if (request.messages() != null) {
            for (var msg : request.messages()) {
                String role = String.valueOf(msg.get("role"));
                switch (role) {
                    case "system" -> lowerSystemMessage(input, msg);
                    case "user" -> lowerUserMessage(input, msg);
                    case "assistant" -> lowerAssistantMessage(input, msg);
                    case "tool" -> lowerToolMessage(input, msg);
                    default -> { /* 忽略未知角色 */ }
                }
            }
        }

        return input;
    }

    /**
     * 将会话中的额外系统消息降级为用户 {@code input_text} 项。
     */
    private void lowerSystemMessage(ArrayNode input, Map<String, Object> msg) {
        String content = extractTextContent(msg);
        if (content.isEmpty()) return;

        ObjectNode userItem = MAPPER.createObjectNode();
        userItem.put("role", "user");
        ArrayNode contentArr = MAPPER.createArrayNode();
        ObjectNode textPart = MAPPER.createObjectNode();
        textPart.put("type", "input_text");
        textPart.put("text", content);
        contentArr.add(textPart);
        userItem.set("content", contentArr);
        input.add(userItem);
    }

    /**
     * 将用户消息降级为包含 {@code input_text}/{@code input_image} 内容的用户输入项。
     */
    private void lowerUserMessage(ArrayNode input, Map<String, Object> msg) {
        ObjectNode userItem = MAPPER.createObjectNode();
        userItem.put("role", "user");
        ArrayNode contentArr = MAPPER.createArrayNode();

        Object content = msg.get("content");
        if (content instanceof String text) {
            ObjectNode textPart = MAPPER.createObjectNode();
            textPart.put("type", "input_text");
            textPart.put("text", text);
            contentArr.add(textPart);
        } else if (content instanceof List<?> parts) {
            // 多部分内容（文本 + 图片）
            for (Object part : parts) {
                if (part instanceof Map<?, ?> partMap) {
                    String type = String.valueOf(partMap.get("type"));
                    if ("text".equals(type)) {
                        ObjectNode textPart = MAPPER.createObjectNode();
                        textPart.put("type", "input_text");
                        textPart.put("text", String.valueOf(partMap.get("text")));
                        contentArr.add(textPart);
                    } else if ("image_url".equals(type)) {
                        ObjectNode imgPart = MAPPER.createObjectNode();
                        imgPart.put("type", "input_image");
                        Object urlObj = partMap.get("image_url");
                        if (urlObj instanceof Map<?, ?> urlMap) {
                            imgPart.put("image_url", String.valueOf(urlMap.get("url")));
                        } else {
                            imgPart.put("image_url", String.valueOf(urlObj));
                        }
                        contentArr.add(imgPart);
                    }
                }
            }
        }

        userItem.set("content", contentArr);
        input.add(userItem);
    }

    /**
     * 将助手消息降级为 {@code output_text} 项和 {@code function_call} 项。
     */
    private void lowerAssistantMessage(ArrayNode input, Map<String, Object> msg) {
        // 文本内容 -> output_text
        String content = extractTextContent(msg);
        if (!content.isEmpty()) {
            ObjectNode assistantItem = MAPPER.createObjectNode();
            assistantItem.put("role", "assistant");
            ArrayNode contentArr = MAPPER.createArrayNode();
            ObjectNode textPart = MAPPER.createObjectNode();
            textPart.put("type", "output_text");
            textPart.put("text", content);
            contentArr.add(textPart);
            assistantItem.set("content", contentArr);
            input.add(assistantItem);
        }

        // 工具调用 -> function_call 项
        Object toolCallsObj = msg.get("tool_calls");
        if (toolCallsObj instanceof List<?> toolCalls) {
            for (Object tc : toolCalls) {
                if (tc instanceof Map<?, ?> toolCall) {
                    ObjectNode funcCallItem = MAPPER.createObjectNode();
                    funcCallItem.put("type", "function_call");
                    funcCallItem.put("call_id", String.valueOf(toolCall.get("id")));
                    Object funcObj = toolCall.get("function");
                    if (funcObj instanceof Map<?, ?> func) {
                        funcCallItem.put("name", String.valueOf(func.get("name")));
                        Object argsObj = func.get("arguments");
                        funcCallItem.put("arguments",
                            argsObj != null ? String.valueOf(argsObj) : "{}");
                    }
                    input.add(funcCallItem);
                }
            }
        }
    }

    /**
     * 将工具结果消息降级为 {@code function_call_output} 项。
     */
    private void lowerToolMessage(ArrayNode input, Map<String, Object> msg) {
        ObjectNode outputItem = MAPPER.createObjectNode();
        outputItem.put("type", "function_call_output");
        outputItem.put("call_id", String.valueOf(msg.get("tool_call_id")));
        outputItem.put("output", extractTextContent(msg));
        input.add(outputItem);
    }

    /**
     * 从消息 Map 中提取文本内容 — 同时处理字符串和数组形式的 content。
     */
    private String extractTextContent(Map<String, Object> msg) {
        Object content = msg.get("content");
        if (content == null) return "";
        if (content instanceof String text) return text;
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> partMap) {
                    String type = String.valueOf(partMap.get("type"));
                    if ("text".equals(type) && partMap.containsKey("text")) {
                        if (!sb.isEmpty()) sb.append("\n");
                        sb.append(partMap.get("text"));
                    }
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    // ── SSE 事件转换 ─────────────────────────────────────────────────
    //
    // 按 event.type 分发，生成标准 LLMEvent 记录。
    //
    // 已处理事件类型：
    //   response.output_text.delta              -> TextDelta
    //   response.reasoning_text.delta           -> ReasoningDelta
    //   response.reasoning_summary.delta        -> ReasoningDelta
    //   response.reasoning_summary_text.delta   -> ReasoningDelta
    //   response.output_item.added              -> ToolInputStart / ReasoningStart
    //   response.function_call_arguments.delta  -> ToolInputDelta
    //   response.output_item.done               -> ToolCall / ToolResult / ReasoningEnd
    //   response.completed / response.incomplete -> Finish + Usage
    //   response.failed                         -> ProviderError
    //   error                                   -> ProviderError
    //
    // 其他事件（response.created、response.in_progress、
    // response.output_text.done 等）静默忽略。

    @Override
    public List<LLMEvent> transformSseEvent(String eventType, JsonNode data) {
        if (data == null) return List.of();

        // Responses API 的 eventType 来自 JSON 的 "type" 字段
        String type = eventType;
        if ((type == null || type.isEmpty()) && data.has("type")) {
            type = data.get("type").asText("");
        }
        if (type == null || type.isEmpty()) return List.of();

        return switch (type) {
            // --- 文本 ---
            case "response.output_text.delta" -> onOutputTextDelta(data);

            // --- 推理增量 ---
            case "response.reasoning_text.delta",
                 "response.reasoning_summary.delta",
                 "response.reasoning_summary_text.delta" -> onReasoningDelta(data);

            // --- 推理完成（无操作） ---
            case "response.reasoning_text.done",
                 "response.reasoning_summary.done",
                 "response.reasoning_summary_text.done" -> List.of();

            // --- 推理摘要部分生命周期（简化处理） ---
            case "response.reasoning_summary_part.added",
                 "response.reasoning_summary_part.done" -> List.of();

            // --- 输出项生命周期 ---
            case "response.output_item.added" -> onOutputItemAdded(data);
            case "response.function_call_arguments.delta" -> onFunctionCallArgumentsDelta(data);
            case "response.output_item.done" -> onOutputItemDone(data);

            // --- 流结束 ---
            case "response.completed", "response.incomplete" -> onResponseFinish(data);
            case "response.failed" -> onResponseFailed(data);

            // --- 流错误 ---
            case "error" -> onError(data);

            // 其他事件静默忽略
            default -> List.of();
        };
    }

    // ── 各事件处理器 ─────────────────────────────────────────────────

    /**
     * 处理 {@code response.output_text.delta} — 生成文本增量。
     */
    private List<LLMEvent> onOutputTextDelta(JsonNode data) {
        String delta = data.path("delta").asText("");
        if (delta.isEmpty()) return List.of();
        return List.of(new LLMEvent.TextDelta(delta));
    }

    /**
     * 处理 {@code response.reasoning_*.delta} — 生成推理增量。
     */
    private List<LLMEvent> onReasoningDelta(JsonNode data) {
        String delta = data.path("delta").asText("");
        if (delta.isEmpty()) return List.of();
        return List.of(new LLMEvent.ReasoningDelta(delta));
    }

    /**
     * 处理 {@code response.output_item.added} — 处理新增输出项。
     *
     * <p>推理项触发 {@link LLMEvent.ReasoningStart}，
     * function_call 项触发 {@link LLMEvent.ToolInputStart} 并开始跟踪。
     */
    private List<LLMEvent> onOutputItemAdded(JsonNode data) {
        JsonNode item = data.path("item");
        if (item.isMissingNode()) return List.of();

        String itemType = item.path("type").asText("");
        String itemId = item.path("id").asText("");

        // 推理项 — 生成 ReasoningStart
        if ("reasoning".equals(itemType) && !itemId.isEmpty()) {
            return List.of(new LLMEvent.ReasoningStart(itemId));
        }

        // function_call 项 — 开始跟踪工具调用
        if ("function_call".equals(itemType) && !itemId.isEmpty()) {
            String callId = item.path("call_id").asText(itemId);
            String name = item.path("name").asText("");
            String initialArgs = item.path("arguments").asText("");

            ParserState state = parserState.get();
            state.toolCalls.put(itemId,
                new ToolCallAcc(callId, name, new StringBuilder(initialArgs), false));

            List<LLMEvent> events = new ArrayList<>();
            events.add(new LLMEvent.ToolInputStart(callId, name));
            if (!initialArgs.isEmpty()) {
                events.add(new LLMEvent.ToolInputDelta(initialArgs));
            }
            return events;
        }

        return List.of();
    }

    /**
     * 处理 {@code response.function_call_arguments.delta} — 累积工具调用参数。
     */
    private List<LLMEvent> onFunctionCallArgumentsDelta(JsonNode data) {
        String itemId = data.path("item_id").asText("");
        String delta = data.path("delta").asText("");
        if (itemId.isEmpty() || delta.isEmpty()) return List.of();

        ParserState state = parserState.get();
        ToolCallAcc acc = state.toolCalls.get(itemId);
        if (acc == null || acc.finished()) return List.of();

        acc.args().append(delta);
        return List.of(new LLMEvent.ToolInputDelta(delta));
    }

    /**
     * 处理 {@code response.output_item.done} — 终结输出项。
     *
     * <p>function_call 项生成 {@link LLMEvent.ToolCall}（终结累积的参数），
     * 托管工具生成 ToolCall + ToolResult 对，推理项生成 ReasoningEnd。
     */
    private List<LLMEvent> onOutputItemDone(JsonNode data) {
        JsonNode item = data.path("item");
        if (item.isMissingNode()) return List.of();

        String itemType = item.path("type").asText("");
        String itemId = item.path("id").asText("");

        // function_call 完成 — 终结工具调用
        if ("function_call".equals(itemType)) {
            return finalizeFunctionCall(item, itemId);
        }

        // 托管工具完成 — 生成 tool-call + tool-result 对
        if (HOSTED_TOOL_NAMES.containsKey(itemType) && !itemId.isEmpty()) {
            return hostedToolEvents(item, itemType, itemId);
        }

        // 推理项完成 — 生成 ReasoningEnd
        if ("reasoning".equals(itemType) && !itemId.isEmpty()) {
            return List.of(new LLMEvent.ReasoningEnd(itemId, null));
        }

        return List.of();
    }

    /**
     * 终结一个 function_call 项。
     *
     * <p>已跟踪的工具调用使用累积参数（或被 item.arguments 覆盖），
     * 未跟踪的工具调用一次性完成启动和终结。
     */
    private List<LLMEvent> finalizeFunctionCall(JsonNode item, String itemId) {
        String callId = item.path("call_id").asText(itemId);
        String name = item.path("name").asText("");

        ParserState state = parserState.get();
        ToolCallAcc acc = state.toolCalls.get(itemId);

        if (acc != null && !acc.finished()) {
            // 已跟踪 — 使用累积参数或覆盖参数
            String finalArgs;
            if (item.has("arguments") && !item.path("arguments").isNull()) {
                // 使用 done 事件中的完整参数覆盖
                finalArgs = item.path("arguments").asText("");
            } else {
                // 使用累积参数
                finalArgs = acc.args().toString();
            }
            if (finalArgs.isEmpty()) finalArgs = "{}";

            state.toolCalls.put(itemId,
                new ToolCallAcc(acc.callId(), acc.name(), acc.args(), true));
            state.hasFunctionCall = true;

            return List.of(new LLMEvent.ToolCall(acc.callId(), acc.name(), finalArgs));
        } else {
            // 未跟踪（output_item.added 被跳过或非流式调用）
            if (callId.isEmpty() || name.isEmpty()) return List.of();
            String args = item.path("arguments").asText("{}");
            if (args.isEmpty()) args = "{}";
            state.hasFunctionCall = true;

            List<LLMEvent> events = new ArrayList<>();
            events.add(new LLMEvent.ToolInputStart(callId, name));
            events.add(new LLMEvent.ToolCall(callId, name, args));
            return events;
        }
    }

    /**
     * 为托管（提供者执行的）工具生成 tool-call + tool-result 事件对。
     */
    private List<LLMEvent> hostedToolEvents(JsonNode item, String itemType, String itemId) {
        String toolName = HOSTED_TOOL_NAMES.getOrDefault(itemType, itemType);

        // 根据工具类型提取输入
        String input = extractHostedToolInput(item, itemType);

        // 将完整项作为结果返回
        boolean isError = item.has("error") && !item.get("error").isNull();
        String resultOutput = isError ? item.get("error").toString() : item.toString();

        List<LLMEvent> events = new ArrayList<>();
        events.add(new LLMEvent.ToolCall(itemId, toolName, input));
        events.add(new LLMEvent.ToolResult(itemId, resultOutput, isError));
        return events;
    }

    /**
     * 提取托管工具的输入，根据工具类型使用不同的提取策略。
     */
    private String extractHostedToolInput(JsonNode item, String itemType) {
        return switch (itemType) {
            case "web_search_call", "web_search_preview_call" ->
                item.has("action") ? item.get("action").toString() : "{}";

            case "file_search_call" -> {
                ObjectNode inputObj = MAPPER.createObjectNode();
                inputObj.set("queries", item.has("queries") ? item.get("queries") : MAPPER.createArrayNode());
                yield inputObj.toString();
            }

            case "code_interpreter_call" -> {
                ObjectNode inputObj = MAPPER.createObjectNode();
                if (item.has("code")) inputObj.put("code", item.path("code").asText(""));
                if (item.has("container_id")) inputObj.put("container_id", item.path("container_id").asText(""));
                yield inputObj.toString();
            }

            case "computer_use_call", "local_shell_call" ->
                item.has("action") ? item.get("action").toString() : "{}";

            case "image_generation_call" -> "{}";

            case "mcp_call" -> {
                ObjectNode inputObj = MAPPER.createObjectNode();
                if (item.has("server_label")) inputObj.put("server_label", item.path("server_label").asText(""));
                if (item.has("name")) inputObj.put("name", item.path("name").asText(""));
                if (item.has("arguments")) inputObj.put("arguments", item.path("arguments").asText(""));
                yield inputObj.toString();
            }

            default -> "{}";
        };
    }

    // ── 终结事件 ─────────────────────────────────────────────────────
    // 终结类型：response.completed、response.incomplete、response.failed

    /**
     * 处理 {@code response.completed} / {@code response.incomplete} — 生成 Finish + Usage。
     */
    private List<LLMEvent> onResponseFinish(JsonNode data) {
        List<LLMEvent> events = new ArrayList<>();
        ParserState state = parserState.get();

        // 映射结束原因：
        //   无 incomplete_details.reason -> hasFunctionCall ? "tool-use" : "end-turn"
        //   "max_output_tokens" -> "max-tokens"
        //   "content_filter" -> "content-filter"
        //   其他 -> hasFunctionCall ? "tool-use" : "unknown"
        JsonNode response = data.path("response");
        JsonNode incompleteDetails = response.path("incomplete_details");
        String incompleteReason = incompleteDetails.isMissingNode() || incompleteDetails.isNull()
            ? null
            : incompleteDetails.path("reason").asText(null);

        String reason;
        if (incompleteReason == null) {
            reason = state.hasFunctionCall ? "tool-use" : "end-turn";
        } else {
            reason = switch (incompleteReason) {
                case "max_output_tokens" -> "max-tokens";
                case "content_filter" -> "content-filter";
                default -> state.hasFunctionCall ? "tool-use" : "unknown";
            };
        }
        events.add(new LLMEvent.Finish(reason));

        // 映射用量统计
        JsonNode usage = response.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            int inputTokens = usage.path("input_tokens").asInt(0);
            int outputTokens = usage.path("output_tokens").asInt(0);
            int cachedTokens = usage.path("input_tokens_details").path("cached_tokens").asInt(0);
            events.add(new LLMEvent.Usage(new com.aliyun.odps.agentic.llm.Usage(
                inputTokens, outputTokens, cachedTokens, 0
            )));
        }

        // 清理 ThreadLocal 状态以防止内存泄漏
        parserState.remove();

        return events;
    }

    /**
     * 处理 {@code response.failed} — 生成 {@link LLMEvent.ProviderError}。
     */
    private List<LLMEvent> onResponseFailed(JsonNode data) {
        String message = providerErrorMessage(data, "OpenAI Responses response failed");
        parserState.remove();
        return List.of(new LLMEvent.ProviderError(message));
    }

    /**
     * 处理 {@code error} 事件 — 生成 {@link LLMEvent.ProviderError}。
     */
    private List<LLMEvent> onError(JsonNode data) {
        String message = providerErrorMessage(data, "OpenAI Responses stream error");
        parserState.remove();
        return List.of(new LLMEvent.ProviderError(message));
    }

    /**
     * 从提供者返回的字段中构建可读的错误消息。
     *
     * <p>检查顶层 code/message（error 事件格式）和嵌套的 response.error（response.failed 格式），
     * 当 code 和 message 同时存在时以 code 为前缀。
     */
    private String providerErrorMessage(JsonNode data, String fallback) {
        // 顶层字段（error 事件格式）
        String message = nonEmpty(data.path("message").asText(""));
        String code = nonEmpty(data.path("code").asText(""));

        // 嵌套的 response.error（response.failed 格式）
        JsonNode nested = data.path("response").path("error");
        if (!nested.isMissingNode() && !nested.isNull()) {
            if (message == null) message = nonEmpty(nested.path("message").asText(""));
            if (code == null) code = nonEmpty(nested.path("code").asText(""));
        }

        if (message != null && code != null) return code + ": " + message;
        if (message != null) return message;
        if (code != null) return code;
        return fallback;
    }

    /** 空字符串返回 null，否则返回原字符串。 */
    private static String nonEmpty(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }
}
