package com.aliyun.odps.agentic.llm.provider;

import com.aliyun.odps.agentic.llm.AnthropicTransform;
import com.aliyun.odps.agentic.llm.Auth;
import com.aliyun.odps.agentic.llm.MessageFormat;
import com.aliyun.odps.agentic.llm.Route;

/**
 * Anthropic 提供者门面。
 *
 * <p>用于根据 API Key 与基础地址构建可直接使用的 Anthropic 路由实例。
 */
public final class Anthropic {

    private static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";

    private Anthropic() {}

    /**
     * 使用 API Key 创建 Anthropic 提供者实例。
     *
     * @param apiKey API Key
     * @return 提供者实例
     */
    public static ProviderInstance configure(String apiKey) {
        return configure(apiKey, null);
    }

    /**
     * 使用显式 API Key 与基础地址创建 Anthropic 提供者实例。
     *
     * @param apiKey  API Key
     * @param baseUrl 基础地址
     * @return 提供者实例
     */
    public static ProviderInstance configure(String apiKey, String baseUrl) {
        String resolvedUrl = baseUrl != null ? baseUrl : envOrDefault("ANTHROPIC_BASE_URL", DEFAULT_BASE_URL);

        Auth.AuthFn auth = Auth.optional(apiKey, "apiKey")
            .orElse(Auth.config("ANTHROPIC_API_KEY"))
            .header("x-api-key");

        Route route = new Route(
            "anthropic",
            new AnthropicTransform(),
            resolvedUrl,
            auth,
            MessageFormat.ANTHROPIC
        );
        return new ProviderInstance("anthropic", route);
    }

    /**
     * 读取环境变量，若为空则回退到默认值。
     */
    private static String envOrDefault(String envVar, String defaultValue) {
        String env = System.getenv(envVar);
        return (env != null && !env.isBlank()) ? env : defaultValue;
    }
}
