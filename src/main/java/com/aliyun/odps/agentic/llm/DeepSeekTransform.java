package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;

/**
 * DeepSeek SSE 事件转换器，扩展 OpenAI 格式以支持推理内容。
 *
 * <p>与标准 OpenAI 格式的主要区别：
 * <ul>
 *   <li>所有助手消息必须包含推理内容</li>
 *   <li>流式响应中使用 {@code reasoning_content} 增量字段</li>
 * </ul>
 */
public class DeepSeekTransform implements ProviderTransform {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/v1/chat/completions";

    // 按索引跟踪工具调用的累积状态
    private record ToolCallAcc(String id, String name, StringBuilder args, boolean finished) {}
    private static final ThreadLocal<Map<Integer, ToolCallAcc>> toolCallAccumulators =
        ThreadLocal.withInitial(HashMap::new);

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, Map<String, String> apiKeys) throws Exception {
        String apiKey = apiKeys.getOrDefault("deepseek",
            apiKeys.getOrDefault("default", ""));
        String baseUrl = apiKeys.getOrDefault("deepseek_base_url", DEEPSEEK_API_URL);
        return buildHttpRequest(request, baseUrl, apiKey);
    }

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, String baseUrl, String apiKey) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model().apiId());
        body.put("stream", true);

        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        } else if (request.model().limit().output() != null) {
            body.put("max_tokens", request.model().limit().output());
        }

        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }

        // 系统提示词
        ArrayNode messagesArray = MAPPER.createArrayNode();
        if (request.system() != null) {
            for (String segment : request.system()) {
                ObjectNode sysMsg = MAPPER.createObjectNode();
                sysMsg.put("role", "system");
                sysMsg.put("content", segment);
                messagesArray.add(sysMsg);
            }
        }

        // 会话消息：应用完整转换管线（代理对冲符清理、空内容过滤、
        // 工具 ID 清理、DeepSeek 推理注入等）
        if (request.messages() != null) {
            List<Map<String, Object>> transformed =
                ProviderMessageTransform.transformMessages(new ArrayList<>(request.messages()), request.model());
            for (var msg : transformed) {
                messagesArray.add(MAPPER.valueToTree(msg));
            }
        }
        body.set("messages", messagesArray);

        // 工具定义（OpenAI 兼容格式）
        if (request.tools() != null && !request.tools().isEmpty()) {
            ArrayNode toolsArray = MAPPER.createArrayNode();
            for (var entry : request.tools().entrySet()) {
                if (entry.getValue() instanceof Map toolMap) {
                    ObjectNode toolObj = MAPPER.createObjectNode();
                    toolObj.put("type", "function");
                    ObjectNode funcObj = MAPPER.createObjectNode();
                    funcObj.put("name", entry.getKey());
                    if (toolMap.get("description") != null) {
                        funcObj.put("description", toolMap.get("description").toString());
                    }
                    if (toolMap.containsKey("inputSchema")) {
                        funcObj.set("parameters",
                            ProviderTransform.openAiToolInputSchema(MAPPER.valueToTree(toolMap.get("inputSchema"))));
                    }
                    toolObj.set("function", funcObj);
                    toolsArray.add(toolObj);
                }
            }
            body.set("tools", toolsArray);
        }

        String jsonBody = MAPPER.writeValueAsString(body);

        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .build();
    }

    @Override
    public List<LLMEvent> transformSseEvent(String eventType, JsonNode data) {
        List<LLMEvent> events = new ArrayList<>();

        if (data == null || !data.has("choices")) {
            return events;
        }

        JsonNode choices = data.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return events;
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        String finishReason = choice.path("finish_reason").asText("");

        // 推理内容
        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()
            && !delta.get("reasoning_content").asText("").isEmpty()) {
            String reasoningText = delta.get("reasoning_content").asText();
            events.add(new LLMEvent.ReasoningDelta(reasoningText));
        }

        // 文本增量
        if (delta.has("content") && !delta.get("content").isNull()
            && !delta.get("content").asText("").isEmpty()) {
            String text = delta.get("content").asText();
            events.add(new LLMEvent.TextDelta(text));
        }

        // 工具调用
        if (delta.has("tool_calls")) {
            JsonNode toolCalls = delta.path("tool_calls");
            if (toolCalls.isArray()) {
                for (JsonNode tc : toolCalls) {
                    int index = tc.path("index").asInt(0);
                    ToolCallAcc acc = toolCallAccumulators.get().get(index);

                    if (acc == null) {
                        String callId = tc.path("id").asText("");
                        String funcName = tc.path("function").path("name").asText("");
                        String initialArgs = tc.path("function").path("arguments").asText("");
                        acc = new ToolCallAcc(callId, funcName, new StringBuilder(initialArgs), false);
                        toolCallAccumulators.get().put(index, acc);
                        events.add(new LLMEvent.ToolInputStart(callId, funcName));
                        if (!initialArgs.isEmpty()) {
                            events.add(new LLMEvent.ToolInputDelta(initialArgs));
                        }
                    } else if (!acc.finished()) {
                        String argsDelta = tc.path("function").path("arguments").asText("");
                        if (!argsDelta.isEmpty()) {
                            acc.args().append(argsDelta);
                            events.add(new LLMEvent.ToolInputDelta(argsDelta));
                        }
                    }
                }
            }
        }

        // 结束原因
        if (!finishReason.isEmpty() && !"null".equals(finishReason)) {
            if ("tool_calls".equals(finishReason)) {
                Map<Integer, ToolCallAcc> accMap = toolCallAccumulators.get();
                for (var entry : accMap.entrySet()) {
                    ToolCallAcc acc = entry.getValue();
                    if (!acc.finished()) {
                        String finalArgs = acc.args().toString();
                        events.add(new LLMEvent.ToolCall(acc.id(), acc.name(), finalArgs.isEmpty() ? "{}" : finalArgs));
                        accMap.put(entry.getKey(),
                            new ToolCallAcc(acc.id(), acc.name(), acc.args(), true));
                    }
                }
                toolCallAccumulators.remove();
            } else {
                toolCallAccumulators.remove();
            }

            String mappedReason = switch (finishReason) {
                case "stop" -> "end-turn";
                case "tool_calls" -> "tool-use";
                case "length" -> "max-tokens";
                default -> finishReason;
            };
            events.add(new LLMEvent.Finish(mappedReason));
        }

        // 用量统计
        JsonNode usage = data.path("usage");
        if (!usage.isMissingNode()) {
            events.add(new LLMEvent.Usage(new com.aliyun.odps.agentic.llm.Usage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("prompt_tokens_details").path("cached_tokens").asInt(0),
                0
            )));
        }

        return events;
    }

}
