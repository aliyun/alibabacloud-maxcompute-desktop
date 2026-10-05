package com.aliyun.odps.agentic.llm.provider;

import java.util.List;
import java.util.Map;

/**
 * 提供者配置，描述一个已发现或已配置的模型提供者。
 *
 * @param id      提供者标识
 * @param name    提供者名称
 * @param source  配置来源，如环境变量、配置文件或自定义配置
 * @param env     API Key 对应的环境变量名列表
 * @param key     已解析出的 API Key，可为空
 * @param options 提供者特定选项
 * @param models  提供者下可用的模型映射
 */
public record ProviderConfig(
    String id,
    String name,
    String source,
    List<String> env,
    String key,
    Map<String, Object> options,
    Map<String, ModelInfo> models
) {

    /** 已知的 Anthropic 提供者标识。 */
    public static final String ANTHROPIC = "anthropic";
    /** 已知的 OpenAI 提供者标识。 */
    public static final String OPENAI = "openai";
    public static final String GOOGLE = "google";
    public static final String GOOGLE_VERTEX = "google-vertex";
    public static final String GITHUB_COPILOT = "github-copilot";
    public static final String AMAZON_BEDROCK = "amazon-bedrock";
    public static final String AZURE = "azure";
    public static final String OPENROUTER = "openrouter";
    public static final String MISTRAL = "mistral";
    public static final String GITLAB = "gitlab";
    public static final String OPENCODE = "opencode";
    public static final String DEEPSEEK = "deepseek";
    public static final String XAI = "xai";
    public static final String GROQ = "groq";

    /**
     * 判断当前提供者是否已解析出可用的 API Key。
     *
     * @return 已有可用 Key 时返回 {@code true}
     */
    public boolean hasKey() {
        return key != null && !key.isBlank();
    }

    /**
     * 判断当前提供者是否包含可用模型。
     *
     * @return 存在模型时返回 {@code true}
     */
    public boolean hasModels() {
        return models != null && !models.isEmpty();
    }
}
