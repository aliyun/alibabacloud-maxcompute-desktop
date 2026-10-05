package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.*;

/**
 * Anthropic SSE 事件转换器。
 *
 * <p>将 Anthropic API 的 SSE 事件转换为统一的 {@link LLMEvent}，
 * 并在构建请求时应用缓存控制标记以支持 Anthropic 的提示词缓存机制。
 */
public class AnthropicTransform implements ProviderTransform {

    private static final Logger log = LoggerFactory.getLogger(AnthropicTransform.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, Map<String, String> apiKeys) throws Exception {
        String apiKey = apiKeys.getOrDefault("anthropic",
            apiKeys.getOrDefault("default", ""));
        String baseUrl = apiKeys.getOrDefault("anthropic_base_url",
            System.getenv().getOrDefault("ANTHROPIC_BASE_URL", ANTHROPIC_API_URL));
        return buildHttpRequest(request, baseUrl, apiKey);
    }

    @Override
    public HttpRequest buildHttpRequest(LlmRequest request, String baseUrl, String apiKey) throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", request.model().apiId());
        body.put("stream", true);

        if (request.maxTokens() != null) {
            body.put("max_tokens", request.maxTokens());
        } else {
            body.put("max_tokens", request.model().limit().output() != null
                ? request.model().limit().output() : 4096);
        }

        // 扩展思考支持：启用思考时不发送温度参数
        Integer thinkingBudget = extractThinkingBudget(request);
        if (thinkingBudget != null) {
            ObjectNode thinking = MAPPER.createObjectNode();
            thinking.put("type", "enabled");
            thinking.put("budget_tokens", thinkingBudget);
            body.set("thinking", thinking);
        } else if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }

        // 系统提示词
        if (request.system() != null && !request.system().isEmpty()) {
            List<Map<String, Object>> systemBlocks = new ArrayList<>();
            for (String segment : request.system()) {
                Map<String, Object> systemObj = new LinkedHashMap<>();
                systemObj.put("type", "text");
                systemObj.put("text", segment);
                systemBlocks.add(systemObj);
            }

            // 对前 2 个系统块应用缓存控制标记
            ProviderMessageTransform.applyCacheControlToSystemBlocks(systemBlocks);

            body.set("system", MAPPER.valueToTree(systemBlocks));
        }

        // 会话消息
        List<Map<String, Object>> messages = request.messages();
        if (messages != null) {
            messages = transformMessages(new ArrayList<>(messages), request.model());

            // 对最近 2 条消息应用缓存控制标记
            List<Map<String, Object>> mutableMessages = new ArrayList<>();
            for (Map<String, Object> msg : messages) {
                mutableMessages.add(new LinkedHashMap<>(msg));
            }
            ProviderMessageTransform.applyCacheControlToRecentMessages(mutableMessages);

            body.set("messages", MAPPER.valueToTree(mutableMessages));
        } else {
            body.set("messages", MAPPER.createArrayNode());
        }

        // 工具定义
        if (request.tools() != null && !request.tools().isEmpty()) {
            var toolsArray = MAPPER.createArrayNode();
            for (var entry : request.tools().entrySet()) {
                if (entry.getValue() instanceof Map toolMap) {
                    var toolObj = MAPPER.createObjectNode();
                    toolObj.put("name", entry.getKey());
                    if (toolMap.containsKey("description")) {
                        toolObj.put("description", String.valueOf(toolMap.get("description")));
                    }
                    if (toolMap.containsKey("inputSchema")) {
                        toolObj.set("input_schema", MAPPER.valueToTree(toolMap.get("inputSchema")));
                    }
                    toolsArray.add(toolObj);
                }
            }
            body.set("tools", toolsArray);
        }

        // 工具选择
        if (request.toolChoice() != null) {
            ObjectNode toolChoiceObj = MAPPER.createObjectNode();
            toolChoiceObj.put("type", request.toolChoice());
            body.set("tool_choice", toolChoiceObj);
        }

        if (!baseUrl.endsWith("/messages") && !baseUrl.endsWith("/messages/")) {
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl + "v1/messages";
            } else {
                baseUrl = baseUrl + "/v1/messages";
            }
        }
        log.info("LLM request → {} model={}", baseUrl, request.model().apiId());

        var builder = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl))
            .header("Content-Type", "application/json")
            .header("anthropic-version", "2023-06-01");

        if (baseUrl.contains("anthropic.com")) {
            builder.header("x-api-key", apiKey);
        } else {
            builder.header("Authorization", "Bearer " + apiKey);
        }

        return builder
            .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
            .build();
    }

    @Override
    public List<Map<String, Object>> transformMessages(List<Map<String, Object>> messages, Model model) {
        return ProviderMessageTransform.transformMessages(messages, model);
    }

    /**
     * 从提供者选项中提取思考预算。
     */
    @SuppressWarnings("unchecked")
    private static Integer extractThinkingBudget(LlmRequest request) {
        if (request.providerOptions() == null) return null;
        Object anthropic = request.providerOptions().get("anthropic");
        if (!(anthropic instanceof Map<?, ?> anthropicMap)) return null;
        Object thinking = anthropicMap.get("thinking");
        if (!(thinking instanceof Map<?, ?> thinkingMap)) return null;
        if (!"enabled".equals(thinkingMap.get("type"))) return null;

        Object budget = thinkingMap.get("budgetTokens");
        if (budget == null) budget = thinkingMap.get("budget_tokens");
        if (budget instanceof Number n) return n.intValue();
        return null;
    }

    // 按内容块索引跟踪工具调用和思考生命周期
    private record ContentBlock(String type, String id, String name) {}
    private static final ThreadLocal<Map<Integer, ContentBlock>> contentBlocks =
        ThreadLocal.withInitial(HashMap::new);

    @Override
    public List<LLMEvent> transformSseEvent(String eventType, JsonNode data) {
        List<LLMEvent> events = new ArrayList<>();

        switch (eventType) {
            case "message_start" -> {
                JsonNode message = data.path("message");
                String modelId = message.path("model").asText("");
                events.add(new LLMEvent.TextStart(modelId));

                JsonNode usage = message.path("usage");
                if (!usage.isMissingNode()) {
                    events.add(new LLMEvent.Usage(
                        new com.aliyun.odps.agentic.llm.Usage(
                            usage.path("input_tokens").asInt(0),
                            0,
                            usage.path("cache_read_input_tokens").asInt(0),
                            usage.path("cache_creation_input_tokens").asInt(0)
                        )
                    ));
                }
            }

            case "content_block_start" -> {
                int index = data.path("index").asInt();
                JsonNode block = data.path("content_block");
                String type = block.path("type").asText("");

                if ("tool_use".equals(type)) {
                    String toolId = block.path("id").asText("");
                    String toolName = block.path("name").asText("");
                    contentBlocks.get().put(index, new ContentBlock("tool-call", toolId, toolName));
                    events.add(new LLMEvent.ToolInputStart(toolId, toolName));
                } else if ("thinking".equals(type)) {
                    String reasoningId = "reasoning-" + index;
                    contentBlocks.get().put(index, new ContentBlock("thinking", reasoningId, null));
                    events.add(new LLMEvent.ReasoningStart(reasoningId));

                    String initialThinking = block.path("thinking").asText("");
                    if (!initialThinking.isEmpty()) {
                        events.add(new LLMEvent.ReasoningDelta(initialThinking));
                    }
                } else if ("redacted_thinking".equals(type)) {
                    contentBlocks.get().put(index, new ContentBlock("redacted_thinking", "reasoning-" + index, null));
                    log.debug("Redacted thinking block at index {}", index);
                }
            }

            case "content_block_delta" -> {
                int index = data.path("index").asInt();
                JsonNode delta = data.path("delta");
                String type = delta.path("type").asText("");

                if ("text_delta".equals(type)) {
                    String text = delta.path("text").asText("");
                    if (!text.isEmpty()) {
                        events.add(new LLMEvent.TextDelta(text));
                    }
                } else if ("input_json_delta".equals(type)) {
                    String partialJson = delta.path("partial_json").asText("");
                    events.add(new LLMEvent.ToolInputDelta(partialJson));
                } else if ("thinking_delta".equals(type)) {
                    String thinking = delta.path("thinking").asText("");
                    if (!thinking.isEmpty()) {
                        events.add(new LLMEvent.ReasoningDelta(thinking));
                    }
                } else if ("signature_delta".equals(type)) {
                    String signature = delta.path("signature").asText("");
                    if (!signature.isEmpty()) {
                        String reasoningId = "reasoning-" + index;
                        events.add(new LLMEvent.ReasoningEnd(reasoningId, signature));
                        ContentBlock block = contentBlocks.get().get(index);
                        if (block != null && "thinking".equals(block.type())) {
                            contentBlocks.get().put(index, new ContentBlock("thinking-signed", block.id(), null));
                        }
                    }
                }
            }

            case "content_block_stop" -> {
                int index = data.path("index").asInt();
                ContentBlock block = contentBlocks.get().remove(index);
                if (block != null) {
                    if ("tool-call".equals(block.type())) {
                        events.add(new LLMEvent.ToolCall(block.id(), block.name()));
                    } else if ("thinking".equals(block.type())) {
                        events.add(new LLMEvent.ReasoningEnd(block.id(), null));
                    }
                }
            }

            case "message_delta" -> {
                JsonNode delta = data.path("delta");
                String stopReason = delta.path("stop_reason").asText("");

                if (!stopReason.isEmpty()) {
                    events.add(new LLMEvent.Finish(stopReason));
                }

                JsonNode usage = data.path("usage");
                if (!usage.isMissingNode()) {
                    events.add(new LLMEvent.Usage(
                        new com.aliyun.odps.agentic.llm.Usage(
                            0,
                            usage.path("output_tokens").asInt(0),
                            0, 0
                        )
                    ));
                }
            }

            case "message_stop" -> {
                events.add(new LLMEvent.TextEnd());
                contentBlocks.remove();
            }

            case "ping" -> {
                // 心跳事件，忽略
            }

            case "error" -> {
                String message = data.path("error").path("message").asText("Unknown error");
                events.add(new LLMEvent.ProviderError(message));
            }

            default -> {
                // 未知事件类型，忽略
            }
        }

        return events;
    }
}
