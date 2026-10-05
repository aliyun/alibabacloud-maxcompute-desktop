package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;

/**
 * OpenAI 兼容提供者的 SSE 事件转换器。
 *
 * <p>适用于任何遵循 OpenAI Chat Completions SSE 格式的 API，包括
 * Groq、Together AI、Mistral、Ollama、OpenRouter 等兼容端点。
 */
public class OpenAICompatibleTransform implements ProviderTransform {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String providerId;

    // 按索引跟踪工具调用的累积状态
    private record ToolCallAcc(String id, String name, StringBuilder args, boolean finished) {}
    private static final ThreadLocal<Map<Integer, ToolCallAcc>> toolCallAccumulators =
        ThreadLocal.withInitial(HashMap::new);

    /**
     * 为指定提供者创建转换器实例。
     *
     * @param providerId 提供者标识
     */
    public OpenAICompatibleTransform(String providerId) {
        this.providerId = providerId;
    }

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, Map<String, String> apiKeys) throws Exception {
        String apiKey = apiKeys.getOrDefault(providerId,
            apiKeys.getOrDefault("default", ""));
        String baseUrl = apiKeys.get(providerId + "_base_url");
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                "OpenAI-compatible provider '" + providerId + "' requires '" + providerId + "_base_url' in apiKeys");
        }
        return buildHttpRequest(request, baseUrl, apiKey);
    }

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, String baseUrl, String apiKey) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model().apiId());
        body.put("stream", true);

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

        String reasoningEffort = extractReasoningEffort(request);
        if (reasoningEffort != null) {
            body.put("reasoning_effort", reasoningEffort);
        }
        Boolean enableThinking = extractEnableThinking(request);
        if (enableThinking != null) {
            body.put("enable_thinking", enableThinking);
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

        // 会话消息
        if (request.messages() != null) {
            for (var msg : request.messages()) {
                messagesArray.add(MAPPER.valueToTree(msg));
            }
        }
        body.set("messages", messagesArray);

        // 工具定义
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

        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody));

        // 仅在提供 API Key 时附加 Authorization 头
        if (!apiKey.isEmpty()) {
            reqBuilder.header("Authorization", "Bearer " + apiKey);
        }

        return reqBuilder.build();
    }

    @Override
    public List<LLMEvent> transformSseEvent(String eventType, JsonNode data) {
        List<LLMEvent> events = new ArrayList<>();
        if (data == null) {
            return events;
        }

        // 错误载荷优先，且必须判 null：网关常在正常分片里带一个 "error": null，
        // 只看 has("error") 会把正常内容当成错误吞掉。
        JsonNode error = data.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            events.add(new LLMEvent.ProviderError(error.path("message").asText("Unknown error")));
            return events;
        }

        // 用量必须先于 choices 分派处理。OpenAI 兼容接口的用量尾包有两种形态：
        //   ① 不带 choices 键
        //   ② 带一个**空** choices 数组 —— DashScope / 通义走的是这种
        // 旧实现只认 ①：② 会先通过 has("choices") 的判空，再撞上下面的 isEmpty() 提前返回，
        // 用量在解析侧被静默丢弃 —— 而请求侧其实一直在发 stream_options.include_usage，
        // 于是宿主看到的 token 计数恒为 0，误以为是请求没开启用量上报。
        // 放在这里对两种形态都成立；StreamProcessor 归并用量用的是 Math.max，
        // 因此即便某些提供者在带 choices 的末尾分片里重复带上 usage 也不会翻倍。
        JsonNode usage = data.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            events.add(new LLMEvent.Usage(mapUsage(usage)));
        }

        JsonNode choices = data.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            return events;
        }

        JsonNode choice = choices.get(0);
        JsonNode delta = choice.path("delta");
        String finishReason = choice.path("finish_reason").asText("");

        if (delta.has("reasoning_content") && !delta.get("reasoning_content").isNull()
            && !delta.get("reasoning_content").asText("").isEmpty()) {
            String reasoning = delta.get("reasoning_content").asText();
            events.add(new LLMEvent.ReasoningDelta(reasoning));
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

        // 用量已在方法开头统一处理（两种尾包形态都覆盖），此处不再重复取。

        return events;
    }

    private static com.aliyun.odps.agentic.llm.Usage mapUsage(JsonNode usage) {
        return new com.aliyun.odps.agentic.llm.Usage(
            usage.path("prompt_tokens").asInt(0),
            usage.path("completion_tokens").asInt(0),
            usage.path("prompt_tokens_details").path("cached_tokens").asInt(0),
            0,
            usage.path("completion_tokens_details").path("reasoning_tokens").asInt(0)
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractCompatibleOptions(LlmRequest request) {
        if (request.providerOptions() == null) {
            return Map.of();
        }
        Object options = request.providerOptions().get("openaiCompatible");
        if (options instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static String extractReasoningEffort(LlmRequest request) {
        Object effort = extractCompatibleOptions(request).get("reasoningEffort");
        return effort instanceof String s && !s.isBlank() ? s : null;
    }

    /**
     * 思考链开关是**三态**的：{@code null} 表示调用方没有表态（不下发该字段，随提供者默认值），
     * {@code TRUE}/{@code FALSE} 一律照发。此前折叠成 boolean，显式关闭与"没表态"无法区分，
     * 于是关不掉那些服务端默认开启思考的模型。
     */
    private static Boolean extractEnableThinking(LlmRequest request) {
        Object enabled = extractCompatibleOptions(request).get("enableThinking");
        return enabled instanceof Boolean b ? b : null;
    }

    /**
     * 返回该转换器对应的提供者标识。
     *
     * @return 提供者标识
     */
    public String getProviderId() {
        return providerId;
    }
}
