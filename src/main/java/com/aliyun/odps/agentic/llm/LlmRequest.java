package com.aliyun.odps.agentic.llm;

import com.aliyun.odps.agentic.model.Message;

import java.util.List;
import java.util.Map;

/**
 * LLM 请求，表示一次模型调用的输入参数。
 *
 * @param model           要使用的模型
 * @param system          系统提示词片段列表
 * @param messages        提供者格式的会话消息
 * @param tools           可用工具定义映射，键为工具名，值为 Schema
 * @param toolChoice      工具选择模式，可为 {@code "auto"}、{@code "required"}、{@code "none"}
 * @param temperature     温度参数
 * @param maxTokens       最大输出 Token 数
 * @param providerOptions 提供者特定选项
 * @param sessionId       会话标识，用于路由或亲和性控制
 */
public record LlmRequest(
    Model model,
    List<String> system,
    List<Map<String, Object>> messages,
    Map<String, Object> tools,
    String toolChoice,
    Double temperature,
    Integer maxTokens,
    Map<String, Object> providerOptions,
    String sessionId,
    List<Message> sourceMessages,
    boolean finalStep,
    String finalStepHint
) {
    public LlmRequest(Model model, List<String> system, List<Map<String, Object>> messages,
                      Map<String, Object> tools, String toolChoice, Double temperature,
                      Integer maxTokens, Map<String, Object> providerOptions, String sessionId,
                      List<Message> sourceMessages, boolean finalStep) {
        this(model, system, messages, tools, toolChoice, temperature,
            maxTokens, providerOptions, sessionId, sourceMessages, finalStep, null);
    }
    /** Compatible constructor for host adapters introduced before final-step signaling. */
    public LlmRequest(Model model, List<String> system, List<Map<String, Object>> messages,
                      Map<String, Object> tools, String toolChoice, Double temperature,
                      Integer maxTokens, Map<String, Object> providerOptions, String sessionId,
                      List<Message> sourceMessages) {
        this(model, system, messages, tools, toolChoice, temperature,
            maxTokens, providerOptions, sessionId, sourceMessages, false, null);
    }
    /** Compatible constructor for clients that consume only provider-projected messages. */
    public LlmRequest(Model model, List<String> system, List<Map<String, Object>> messages,
                      Map<String, Object> tools, String toolChoice, Double temperature,
                      Integer maxTokens, Map<String, Object> providerOptions, String sessionId) {
        this(model, system, messages, tools, toolChoice, temperature,
            maxTokens, providerOptions, sessionId, null, false, null);
    }

    /**
     * 创建不带 {@code sessionId} 的兼容构造方法。
     */
    public LlmRequest(Model model, List<String> system, List<Map<String, Object>> messages,
                      Map<String, Object> tools, String toolChoice, Double temperature, Integer maxTokens,
                      Map<String, Object> providerOptions) {
        this(model, system, messages, tools, toolChoice, temperature, maxTokens, providerOptions, null);
    }

    /**
     * 创建不带 {@code providerOptions} 的兼容构造方法。
     */
    public LlmRequest(Model model, List<String> system, List<Map<String, Object>> messages,
                      Map<String, Object> tools, String toolChoice, Double temperature, Integer maxTokens) {
        this(model, system, messages, tools, toolChoice, temperature, maxTokens, null, null);
    }

    /**
     * 创建一个基础请求对象。
     *
     * @param model    模型
     * @param system   系统提示词片段
     * @param messages 会话消息
     * @return 基础 LLM 请求
     */
    public static LlmRequest of(Model model, List<String> system, List<Map<String, Object>> messages) {
        return new LlmRequest(model, system, messages, Map.of(), "auto", null, null, null);
    }
}
