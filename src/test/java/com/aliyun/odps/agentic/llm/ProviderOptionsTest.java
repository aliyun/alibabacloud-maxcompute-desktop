package com.aliyun.odps.agentic.llm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProviderOptionsTest {

    @Test
    void anthropicThinkingModelGetsThinkingOption() {
        ProviderOptions.ModelConfig config = new ProviderOptions.ModelConfig("anthropic", "claude-4-sonnet");
        Map<String, Object> opts = ProviderOptions.compute("anthropic", "claude-4-sonnet", config);
        assertTrue(opts.containsKey("anthropic"));
        @SuppressWarnings("unchecked")
        Map<String, Object> anthropic = (Map<String, Object>) opts.get("anthropic");
        @SuppressWarnings("unchecked")
        Map<String, Object> thinking = (Map<String, Object>) anthropic.get("thinking");
        assertEquals("enabled", thinking.get("type"));
        assertEquals(10000, thinking.get("budget_tokens"));
    }

    @Test
    void anthropicNonThinkingModelNoOption() {
        ProviderOptions.ModelConfig config = new ProviderOptions.ModelConfig("anthropic", "claude-3.5-haiku");
        Map<String, Object> opts = ProviderOptions.compute("anthropic", "claude-3.5-haiku", config);
        assertTrue(opts.isEmpty());
    }

    @Test
    void openAIReasoningModelGetsEffort() {
        ProviderOptions.ModelConfig config = new ProviderOptions.ModelConfig("openai", "o3")
            .withReasoningEffort("high");
        Map<String, Object> opts = ProviderOptions.compute("openai", "o3", config);
        @SuppressWarnings("unchecked")
        Map<String, Object> openai = (Map<String, Object>) opts.get("openai");
        assertEquals("high", openai.get("reasoningEffort"));
    }

    @Test
    void alibabaQwenReasoningModelGetsEnableThinking() {
        ProviderOptions.ModelConfig config = new ProviderOptions.ModelConfig("alibaba-cn", "qwen3-coder-plus");
        Map<String, Object> opts = ProviderOptions.compute("alibaba-cn", "qwen3-coder-plus", config);
        @SuppressWarnings("unchecked")
        Map<String, Object> openaiCompatible = (Map<String, Object>) opts.get("openaiCompatible");
        assertEquals(Boolean.TRUE, openaiCompatible.get("enableThinking"));
    }

    @Test
    void dashscopeAndBailianProviderIdsAlsoGetEnableThinking() {
        // dashscope（灵积）/ bailian（百炼）就是这套服务对外的另两个通行名字。只认字面
        // "alibaba" 的话，按真实服务名注册提供者的宿主永远拿不到这个开关 —— 请求侧一次都不
        // 下发 enable_thinking，思不思考完全由服务端默认值决定，而且关不掉。
        for (String providerId : new String[] {"dashscope", "bailian", "DashScope"}) {
            Map<String, Object> opts = ProviderOptions.compute(providerId, "qwen3-coder-plus",
                new ProviderOptions.ModelConfig(providerId, "qwen3-coder-plus"));
            @SuppressWarnings("unchecked")
            Map<String, Object> compatible = (Map<String, Object>) opts.get("openaiCompatible");
            assertNotNull(compatible, providerId + " must be recognised as an Alibaba provider");
            assertEquals(Boolean.TRUE, compatible.get("enableThinking"));
        }
    }

    @Test
    void explicitEnableThinkingFalseOverridesDefault() {
        // 三态：宿主显式关掉思考链时，必须原样带下去（交互式场景要的是首字延迟，不是思考链）。
        ProviderOptions.ModelConfig config =
            new ProviderOptions.ModelConfig("dashscope", "qwen3-coder-plus").withEnableThinking(false);
        Map<String, Object> opts = ProviderOptions.compute("dashscope", "qwen3-coder-plus", config);
        @SuppressWarnings("unchecked")
        Map<String, Object> compatible = (Map<String, Object>) opts.get("openaiCompatible");
        assertEquals(Boolean.FALSE, compatible.get("enableThinking"));
    }

    @Test
    void nonAlibabaProviderNeverGetsEnableThinking() {
        // 下发范围不能因为三态改造而放大：不认这个参数的提供者会直接拒请求。
        Map<String, Object> opts = ProviderOptions.compute("groq", "qwen3-coder-plus",
            new ProviderOptions.ModelConfig("groq", "qwen3-coder-plus").withEnableThinking(true));
        @SuppressWarnings("unchecked")
        Map<String, Object> compatible = (Map<String, Object>) opts.get("openaiCompatible");
        assertTrue(compatible == null || !compatible.containsKey("enableThinking"));
    }

    @Test
    void explicitAgentTemperatureWins() {
        Double temp = ProviderOptions.temperature("anthropic", "claude-4-sonnet", 0.5);
        assertEquals(0.5, temp, 0.001);
    }

    @Test
    void temperatureForNormalModel() {
        Double temp = ProviderOptions.temperature("anthropic", "claude-3.5-haiku", 0.5);
        assertEquals(0.5, temp, 0.001);
    }

    @Test
    void qwenGetsProviderDefaultTemperature() {
        Double temp = ProviderOptions.temperature("alibaba-cn", "qwen3-coder-plus", null);
        assertEquals(0.55, temp, 0.001);
    }

    @Test
    void noProviderDefaultTemperatureWhenNotSet() {
        assertNull(ProviderOptions.temperature("anthropic", "claude-3.5-haiku", null));
    }
}
