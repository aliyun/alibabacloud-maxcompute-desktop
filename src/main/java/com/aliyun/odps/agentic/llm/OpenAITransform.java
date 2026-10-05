package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;

/**
 * OpenAI Chat Completions SSE 事件转换器。
 *
 * <p>将 OpenAI API 的流式事件转换为统一的 {@link LLMEvent}，
 * 支持文本增量、工具调用累积和推理内容解析。
 */
public class OpenAITransform implements ProviderTransform {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, Map<String, String> apiKeys) throws Exception {
        String apiKey = apiKeys.getOrDefault("openai",
            apiKeys.getOrDefault("default", ""));
        String baseUrl = apiKeys.getOrDefault("openai_base_url",
            System.getenv().getOrDefault("OPENAI_BASE_URL", OPENAI_API_URL));
        return buildHttpRequest(request, baseUrl, apiKey);
    }

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, String baseUrl, String apiKey) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model().apiId());
        body.put("stream", true);

        // 流式模式下需要显式请求使用量统计
        ObjectNode streamOptions = MAPPER.createObjectNode();
        streamOptions.put("include_usage", true);
        body.set("stream_options", streamOptions);

        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        } else if (request.model().limit().output() != null) {
            body.put("max_tokens", request.model().limit().output());
        }

        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }

        // 推理强度
        String reasoningEffort = extractReasoningEffort(request);
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }

        // 系统提示词
        var messagesArray = MAPPER.createArrayNode();
        if (request.system() != null) {
            for (String segment : request.system()) {
                var sysMsg = MAPPER.createObjectNode();
                sysMsg.put("role", "system");
                sysMsg.put("content", segment);
                messagesArray.add(sysMsg);
            }
        }

        // 会话消息
        if (request.messages() != null) {
            for (var msg : request.messages()) {
                messagesArray.add(MAPPER.valueToTree(msg));
            }
        }
        body.set("messages", messagesArray);

        // 工具定义
        if (request.tools() != null && !request.tools().isEmpty()) {
            var toolsArray = MAPPER.createArrayNode();
            for (var entry : request.tools().entrySet()) {
                if (entry.getValue() instanceof Map toolMap) {
                    var toolObj = MAPPER.createObjectNode();
                    toolObj.put("type", "function");
                    var funcObj = MAPPER.createObjectNode();
                    funcObj.put("name", entry.getKey());
                    if (toolMap.containsKey("description")) {
                        funcObj.put("description", String.valueOf(toolMap.get("description")));
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

        if (!baseUrl.endsWith("/chat/completions")) {
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl + "chat/completions";
            } else {
                baseUrl = baseUrl + "/chat/completions";
            }
        }

        return HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
    }

    // 按索引跟踪工具调用的累积状态
    private record ToolCallAcc(String id, String name, StringBuilder args, boolean finished) {}
    private static final ThreadLocal<Map<Integer, ToolCallAcc>> toolCallAccumulators =
        ThreadLocal.withInitial(HashMap::new);

    @Override
    public List<LLMEvent> transformSseEvent(String eventType, JsonNode data) {
        List<LLMEvent> events = new ArrayList<>();

        if (data == null || !data.has("choices")) {
            if (data != null && data.has("error")) {
                String message = data.path("error").path("message").asText("Unknown error");
                events.add(new LLMEvent.ProviderError(message));
                return events;
            }
            JsonNode usage = data != null ? data.path("usage") : null;
            if (usage != null && !usage.isMissingNode()) {
                events.add(new LLMEvent.Usage(mapOpenAIUsage(usage)));
            }
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
        if (delta.has("reasoning_content")) {
            String reasoning = delta.path("reasoning_content").asText("");
            if (!reasoning.isEmpty()) {
                events.add(new LLMEvent.ReasoningDelta(reasoning));
            }
        }

        // 文本增量
        if (delta.has("content")) {
            String content = delta.path("content").asText("");
            if (!content.isEmpty()) {
                events.add(new LLMEvent.TextDelta(content));
            }
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
            events.add(new LLMEvent.Usage(mapOpenAIUsage(usage)));
        }

        return events;
    }

    /**
     * 将 OpenAI 用量 JSON 映射为 {@link Usage} 对象。
     */
    private static com.aliyun.odps.agentic.llm.Usage mapOpenAIUsage(JsonNode usage) {
        int promptTokens = usage.path("prompt_tokens").asInt(0);
        int completionTokens = usage.path("completion_tokens").asInt(0);
        int cachedTokens = usage.path("prompt_tokens_details").path("cached_tokens").asInt(0);
        int reasoningTokens = usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0);
        return new com.aliyun.odps.agentic.llm.Usage(
            promptTokens,
            completionTokens,
            cachedTokens,
            0,
            reasoningTokens
        );
    }

    /**
     * 从提供者选项中提取推理强度参数。
     */
    @SuppressWarnings("unchecked")
    private static String extractReasoningEffort(LlmRequest request) {
        if (request.providerOptions() == null) return null;
        Object openai = request.providerOptions().get("openai");
        if (!(openai instanceof Map<?, ?> openaiMap)) return null;
        Object effort = openaiMap.get("reasoningEffort");
        if (effort instanceof String s) {
            return switch (s) {
                case "low", "medium", "high" -> s;
                default -> null;
            };
        }
        return null;
    }
}
