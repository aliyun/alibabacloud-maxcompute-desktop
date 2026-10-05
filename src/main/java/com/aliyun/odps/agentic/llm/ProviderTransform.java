package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.http.HttpRequest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 提供者转换器接口，负责在统一事件模型与提供者协议之间进行转换。
 *
 * <p>实现类通常负责构建 HTTP 请求、解析 SSE 事件，
 * 并在发送前对消息做提供者特定的预处理。
 */
public interface ProviderTransform {

    /**
     * 使用已解析的端点和鉴权信息构建 HTTP 请求。
     *
     * @param request LLM 请求
     * @param baseUrl 目标基础 URL
     * @param apiKey  API 密钥
     * @return HTTP 请求对象
     * @throws Exception 构建失败时抛出
     */
    default HttpRequest buildHttpRequest(LlmRequest request, String baseUrl, String apiKey) throws Exception {
        Map<String, String> keys = new HashMap<>();
        keys.put("default", apiKey);
        keys.put(request.model().providerId(), apiKey);
        keys.put(request.model().providerId() + "_base_url", baseUrl);
        return buildHttpRequest(request, keys);
    }

    /**
     * 根据提供者配置构建 HTTP 请求。
     *
     * @param request LLM 请求
     * @param apiKeys 提供者相关密钥与 URL 配置
     * @return HTTP 请求对象
     * @throws Exception 构建失败时抛出
     */
    HttpRequest buildHttpRequest(LlmRequest request, Map<String, String> apiKeys) throws Exception;

    /**
     * 将提供者 SSE 事件转换为统一的 {@link LLMEvent} 列表。
     *
     * @param eventType 事件类型
     * @param data      事件数据
     * @return 转换后的统一事件列表
     */
    List<LLMEvent> transformSseEvent(String eventType, JsonNode data);

    /**
     * 在发送前对消息进行提供者特定转换。
     *
     * @param messages 提供者格式的消息列表
     * @param model    模型元数据
     * @return 转换后的消息列表
     */
    default List<Map<String, Object>> transformMessages(List<Map<String, Object>> messages, Model model) {
        return ProviderMessageTransform.transformMessages(messages, model);
    }

    /**
     * 将工具输入 Schema 规范化为 OpenAI 函数调用接口需要的对象结构。
     *
     * @param schema 原始 Schema
     * @return 规范化后的 Schema
     */
    static JsonNode openAiToolInputSchema(JsonNode schema) {
        ObjectMapper mapper = new ObjectMapper();
        if (schema == null || schema.isNull() || schema.isMissingNode()) {
            return mapper.createObjectNode().put("type", "object");
        }

        ObjectNode result;
        if (schema.has("anyOf") && schema.get("anyOf").isArray()) {
            ArrayNode anyOf = (ArrayNode) schema.get("anyOf");
            ObjectNode mergedProperties = mapper.createObjectNode();
            for (JsonNode variant : anyOf) {
                if (variant.isObject() && variant.has("properties")) {
                    Iterator<Map.Entry<String, JsonNode>> fields = variant.get("properties").fields();
                    while (fields.hasNext()) {
                        Map.Entry<String, JsonNode> field = fields.next();
                        if (!mergedProperties.has(field.getKey())) {
                            mergedProperties.set(field.getKey(), field.getValue());
                        }
                    }
                }
            }
            result = mapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = schema.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!"anyOf".equals(field.getKey())) {
                    result.set(field.getKey(), field.getValue());
                }
            }
            result.put("type", "object");
            result.set("properties", mergedProperties);
            result.put("additionalProperties", false);
        } else {
            result = schema.deepCopy();
            result.put("type", "object");
        }
        removeNullSchemas(result);
        return result;
    }

    private static void removeNullSchemas(JsonNode node) {
        if (!node.isObject()) return;
        ObjectNode obj = (ObjectNode) node;
        if (obj.has("anyOf") && obj.get("anyOf").isArray()) {
            ArrayNode anyOf = (ArrayNode) obj.get("anyOf");
            ArrayNode filtered = new ObjectMapper().createArrayNode();
            for (JsonNode variant : anyOf) {
                if (variant.isObject() && "null".equals(variant.path("type").asText())) continue;
                removeNullSchemas(variant);
                filtered.add(variant);
            }
            if (filtered.size() == 1 && filtered.get(0).isObject()) {
                obj.remove("anyOf");
                Iterator<Map.Entry<String, JsonNode>> fields = filtered.get(0).fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    obj.set(field.getKey(), field.getValue());
                }
            } else {
                obj.set("anyOf", filtered);
            }
        }
        Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
        while (fields.hasNext()) {
            removeNullSchemas(fields.next().getValue());
        }
    }
}
