package com.aliyun.odps.agentic.llm.provider;

import com.aliyun.odps.agentic.llm.Auth;
import com.aliyun.odps.agentic.llm.MessageFormat;
import com.aliyun.odps.agentic.llm.OpenAITransform;
import com.aliyun.odps.agentic.llm.Route;

/**
 * OpenAI 提供者门面。
 *
 * <p>用于根据 API Key 与基础地址构建可直接使用的 OpenAI 路由实例。
 */
public final class OpenAI {

    private static final String DEFAULT_BASE_URL = "https://api.openai.com/v1/chat/completions";

    private OpenAI() {}

    /**
     * 使用 API Key 创建 OpenAI 提供者实例。
     *
     * @param apiKey API Key
     * @return 提供者实例
     */
    public static ProviderInstance configure(String apiKey) {
        return configure(apiKey, null);
    }

    /**
     * 使用显式 API Key 与基础地址创建 OpenAI 提供者实例。
     *
     * @param apiKey  API Key
     * @param baseUrl 基础地址
     * @return 提供者实例
     */
    public static ProviderInstance configure(String apiKey, String baseUrl) {
        String resolvedUrl = baseUrl != null ? baseUrl : envOrDefault("OPENAI_BASE_URL", DEFAULT_BASE_URL);

        Auth.AuthFn auth = Auth.optional(apiKey, "apiKey")
            .orElse(Auth.config("OPENAI_API_KEY"))
            .bearer();

        Route route = new Route(
            "openai",
            new OpenAITransform(),
            resolvedUrl,
            auth,
            MessageFormat.OPENAI
        );
        return new ProviderInstance("openai", route);
    }

    /**
     * 读取环境变量，若为空则回退到默认值。
     */
    private static String envOrDefault(String envVar, String defaultValue) {
        String env = System.getenv(envVar);
        return (env != null && !env.isBlank()) ? env : defaultValue;
    }
}
