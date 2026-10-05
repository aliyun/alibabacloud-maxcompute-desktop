package com.aliyun.odps.agentic.llm.provider;

import com.aliyun.odps.agentic.llm.Auth;
import com.aliyun.odps.agentic.llm.DeepSeekTransform;
import com.aliyun.odps.agentic.llm.MessageFormat;
import com.aliyun.odps.agentic.llm.OpenAICompatibleTransform;
import com.aliyun.odps.agentic.llm.ProviderTransform;
import com.aliyun.odps.agentic.llm.Route;

/**
 * OpenAI 兼容提供者门面。
 *
 * <p>用于为通用兼容接口或预设兼容提供者快速构建路由实例。
 */
public final class OpenAICompatible {

    private OpenAICompatible() {}

    /**
     * 配置一个通用 OpenAI 兼容提供者。
     *
     * @param provider 提供者标识
     * @param baseUrl  基础地址
     * @param apiKey   API Key
     * @return 提供者实例
     */
    public static ProviderInstance configure(String provider, String baseUrl, String apiKey) {
        ProviderTransform protocol = new OpenAICompatibleTransform(provider);
        Auth.AuthFn auth = Auth.optional(apiKey, "apiKey").bearer();

        Route route = new Route(
            provider,
            protocol,
            baseUrl,
            auth,
            MessageFormat.OPENAI
        );
        return new ProviderInstance(provider, route);
    }

    // ── 预设配置 ───────────────────────────────────────────────────────

    /**
     * 创建 DeepSeek 提供者实例。
     */
    public static ProviderInstance deepseek(String apiKey) {
        Auth.AuthFn auth = Auth.optional(apiKey, "apiKey")
            .orElse(Auth.config("DEEPSEEK_API_KEY"))
            .bearer();
        Route route = new Route(
            "deepseek",
            new DeepSeekTransform(),
            "https://api.deepseek.com/v1/chat/completions",
            auth,
            MessageFormat.OPENAI
        );
        return new ProviderInstance("deepseek", route);
    }

    /**
     * 创建 Groq 提供者实例。
     */
    public static ProviderInstance groq(String apiKey) {
        return fromProfile("groq", "https://api.groq.com/openai/v1/chat/completions",
            apiKey, "GROQ_API_KEY");
    }

    /**
     * 创建 Fireworks 提供者实例。
     */
    public static ProviderInstance fireworks(String apiKey) {
        return fromProfile("fireworks", "https://api.fireworks.ai/inference/v1/chat/completions",
            apiKey, "FIREWORKS_API_KEY");
    }

    /**
     * 创建 Together AI 提供者实例。
     */
    public static ProviderInstance togetherai(String apiKey) {
        return fromProfile("togetherai", "https://api.together.xyz/v1/chat/completions",
            apiKey, "TOGETHER_API_KEY");
    }

    /**
     * 创建 Cerebras 提供者实例。
     */
    public static ProviderInstance cerebras(String apiKey) {
        return fromProfile("cerebras", "https://api.cerebras.ai/v1/chat/completions",
            apiKey, "CEREBRAS_API_KEY");
    }

    /**
     * 根据预设配置创建兼容提供者实例。
     */
    private static ProviderInstance fromProfile(String provider, String baseUrl,
                                                 String apiKey, String envVar) {
        Auth.AuthFn auth = Auth.optional(apiKey, "apiKey")
            .orElse(Auth.config(envVar))
            .bearer();
        Route route = new Route(
            provider,
            new OpenAICompatibleTransform(provider),
            baseUrl,
            auth,
            MessageFormat.OPENAI
        );
        return new ProviderInstance(provider, route);
    }
}
