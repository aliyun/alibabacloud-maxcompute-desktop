package com.aliyun.odps.agentic.llm.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 模型目录 — 基于 models.dev 数据的硬编码快照。
 *
 * <p>提供与 models.dev 运行时获取相同的模型元数据，
 * 包括上下文窗口、输出限制、成本、能力等信息。
 * 目录数据从 models.dev/api.json 镜像填充。
 */
public final class ModelCatalog {

    private ModelCatalog() {}

    /** 所有已知提供者，按提供者 ID 索引。 */
    private static final Map<String, ProviderConfig> PROVIDERS;

    static {
        var providers = new LinkedHashMap<String, ProviderConfig>();

        // ── Anthropic ──────────────────────────────────────────────────
        providers.put(ProviderConfig.ANTHROPIC, buildAnthropicProvider());

        // ── OpenAI ─────────────────────────────────────────────────────
        providers.put(ProviderConfig.OPENAI, buildOpenAIProvider());

        PROVIDERS = Collections.unmodifiableMap(providers);
    }

    // ── 公开 API ──────────────────────────────────────────────────────

    /**
     * 获取目录中所有已知提供者。
     *
     * @return 不可修改的提供者映射
     */
    public static Map<String, ProviderConfig> allProviders() {
        return PROVIDERS;
    }

    /**
     * 根据 ID 获取提供者。
     *
     * @param providerID 提供者标识
     * @return 提供者配置，不存在时返回空
     */
    public static Optional<ProviderConfig> getProvider(String providerID) {
        return Optional.ofNullable(PROVIDERS.get(providerID));
    }

    /**
     * 跨所有提供者查找模型。
     *
     * @param modelID 模型标识
     * @return 模型信息，不存在时返回空
     */
    public static Optional<ModelInfo> getModel(String modelID) {
        for (var provider : PROVIDERS.values()) {
            var model = provider.models().get(modelID);
            if (model != null) return Optional.of(model);
        }
        return Optional.empty();
    }

    /**
     * 在指定提供者内查找模型。
     *
     * @param providerID 提供者标识
     * @param modelID    模型标识
     * @return 模型信息，不存在时返回空
     */
    public static Optional<ModelInfo> getModel(String providerID, String modelID) {
        var provider = PROVIDERS.get(providerID);
        if (provider == null) return Optional.empty();
        return Optional.ofNullable(provider.models().get(modelID));
    }

    /**
     * 列出指定提供者的所有模型。
     *
     * @param providerID 提供者标识
     * @return 模型映射，提供者不存在时返回空映射
     */
    public static Map<String, ModelInfo> getModelsForProvider(String providerID) {
        var provider = PROVIDERS.get(providerID);
        if (provider == null) return Map.of();
        return provider.models();
    }

    // ── Anthropic 模型 ────────────────────────────────────────────────

    private static ProviderConfig buildAnthropicProvider() {
        var models = new LinkedHashMap<String, ModelInfo>();
        String npm = "@ai-sdk/anthropic";
        String api = "https://api.anthropic.com/v1";
        String pid = ProviderConfig.ANTHROPIC;

        // Claude Opus 4.7
        models.put("claude-opus-4-7", anthropicModel(
            "claude-opus-4-7", "Claude Opus 4.7", "claude-opus", npm, api,
            1_000_000, 128_000, 5, 25, 0.5, 6.25,
            true, false, true, "2026-04-16"
        ));

        // Claude Opus 4.6
        models.put("claude-opus-4-6", anthropicModel(
            "claude-opus-4-6", "Claude Opus 4.6", "claude-opus", npm, api,
            1_000_000, 128_000, 5, 25, 0.5, 6.25,
            true, true, true, "2026-02-05"
        ));

        // Claude Sonnet 4.6
        models.put("claude-sonnet-4-6", anthropicModel(
            "claude-sonnet-4-6", "Claude Sonnet 4.6", "claude-sonnet", npm, api,
            1_000_000, 64_000, 3, 15, 0.3, 3.75,
            true, true, true, "2026-02-17"
        ));

        // Claude Opus 4.5
        models.put("claude-opus-4-5", anthropicModel(
            "claude-opus-4-5", "Claude Opus 4.5", "claude-opus", npm, api,
            200_000, 64_000, 5, 25, 0.5, 6.25,
            true, true, true, "2025-11-01"
        ));
        models.put("claude-opus-4-5-20251101", anthropicModel(
            "claude-opus-4-5-20251101", "Claude Opus 4.5", "claude-opus", npm, api,
            200_000, 64_000, 5, 25, 0.5, 6.25,
            true, true, true, "2025-11-01"
        ));

        // Claude Sonnet 4.5
        models.put("claude-sonnet-4-5", anthropicModel(
            "claude-sonnet-4-5", "Claude Sonnet 4.5", "claude-sonnet", npm, api,
            200_000, 64_000, 3, 15, 0.3, 3.75,
            true, true, true, "2025-09-29"
        ));
        models.put("claude-sonnet-4-5-20250929", anthropicModel(
            "claude-sonnet-4-5-20250929", "Claude Sonnet 4.5", "claude-sonnet", npm, api,
            200_000, 64_000, 3, 15, 0.3, 3.75,
            true, true, true, "2025-09-29"
        ));

        // Claude Opus 4.1
        models.put("claude-opus-4-1", anthropicModel(
            "claude-opus-4-1", "Claude Opus 4.1 (latest)", "claude-opus", npm, api,
            200_000, 32_000, 15, 75, 1.5, 18.75,
            true, true, true, "2025-08-05"
        ));
        models.put("claude-opus-4-1-20250805", anthropicModel(
            "claude-opus-4-1-20250805", "Claude Opus 4.1", "claude-opus", npm, api,
            200_000, 32_000, 15, 75, 1.5, 18.75,
            true, true, true, "2025-08-05"
        ));

        // Claude Sonnet 4 (original)
        models.put("claude-sonnet-4-0", anthropicModel(
            "claude-sonnet-4-0", "Claude Sonnet 4 (latest)", "claude-sonnet", npm, api,
            200_000, 64_000, 3, 15, 0.3, 3.75,
            true, true, true, "2025-05-22"
        ));
        models.put("claude-sonnet-4-20250514", anthropicModel(
            "claude-sonnet-4-20250514", "Claude Sonnet 4", "claude-sonnet", npm, api,
            200_000, 64_000, 3, 15, 0.3, 3.75,
            true, true, true, "2025-05-22"
        ));

        // Claude Opus 4 (original)
        models.put("claude-opus-4-0", anthropicModel(
            "claude-opus-4-0", "Claude Opus 4 (latest)", "claude-opus", npm, api,
            200_000, 32_000, 15, 75, 1.5, 18.75,
            true, true, true, "2025-05-22"
        ));
        models.put("claude-opus-4-20250514", anthropicModel(
            "claude-opus-4-20250514", "Claude Opus 4", "claude-opus", npm, api,
            200_000, 32_000, 15, 75, 1.5, 18.75,
            true, true, true, "2025-05-22"
        ));

        // Claude Haiku 4.5
        models.put("claude-haiku-4-5", anthropicModel(
            "claude-haiku-4-5", "Claude Haiku 4.5 (latest)", "claude-haiku", npm, api,
            200_000, 64_000, 1, 5, 0.1, 1.25,
            true, true, true, "2025-10-15"
        ));
        models.put("claude-haiku-4-5-20251001", anthropicModel(
            "claude-haiku-4-5-20251001", "Claude Haiku 4.5", "claude-haiku", npm, api,
            200_000, 64_000, 1, 5, 0.1, 1.25,
            true, true, true, "2025-10-01"
        ));

        // Claude 3.7 Sonnet
        models.put("claude-3-7-sonnet-20250219", anthropicModel(
            "claude-3-7-sonnet-20250219", "Claude Sonnet 3.7", "claude-sonnet", npm, api,
            200_000, 64_000, 3, 15, 0.3, 3.75,
            true, true, true, "2025-02-19"
        ));

        // Claude 3.5 Haiku
        models.put("claude-3-5-haiku-latest", anthropicModel(
            "claude-3-5-haiku-latest", "Claude Haiku 3.5 (latest)", "claude-haiku", npm, api,
            200_000, 8_192, 0.8, 4, 0.08, 1,
            false, true, false, "2024-10-22"
        ));
        models.put("claude-3-5-haiku-20241022", anthropicModel(
            "claude-3-5-haiku-20241022", "Claude Haiku 3.5", "claude-haiku", npm, api,
            200_000, 8_192, 0.8, 4, 0.08, 1,
            false, true, false, "2024-10-22"
        ));

        // Claude 3.5 Sonnet v2
        models.put("claude-3-5-sonnet-20241022", anthropicModel(
            "claude-3-5-sonnet-20241022", "Claude Sonnet 3.5 v2", "claude-sonnet", npm, api,
            200_000, 8_192, 3, 15, 0.3, 3.75,
            false, true, false, "2024-10-22"
        ));

        // Claude 3 Opus
        models.put("claude-3-opus-20240229", anthropicModel(
            "claude-3-opus-20240229", "Claude Opus 3", "claude-opus", npm, api,
            200_000, 4_096, 15, 75, 1.5, 18.75,
            false, true, false, "2024-02-29"
        ));

        return new ProviderConfig(
            pid, "Anthropic", "custom",
            List.of("ANTHROPIC_API_KEY"),
            null,
            Map.of(),
            Collections.unmodifiableMap(models)
        );
    }

    /**
     * 创建 Anthropic 模型条目的辅助方法。
     */
    private static ModelInfo anthropicModel(
        String id, String name, String family, String npm, String apiUrl,
        int context, int output, double costIn, double costOut,
        double cacheRead, double cacheWrite,
        boolean reasoning, boolean temperature, boolean attachment,
        String releaseDate
    ) {
        return new ModelInfo(
            id, ProviderConfig.ANTHROPIC, name, family,
            new ModelInfo.ApiInfo(id, apiUrl, npm),
            "active",
            new ModelInfo.Cost(costIn, costOut, new ModelInfo.Cost.CacheCost(cacheRead, cacheWrite)),
            new ModelInfo.Limit(context, null, output),
            new ModelInfo.Capabilities(
                temperature, reasoning, attachment, true,
                ModelInfo.Modalities.TEXT_IMAGE_PDF,
                ModelInfo.Modalities.TEXT_ONLY,
                ModelInfo.Interleaved.DISABLED
            ),
            Map.of(), Map.of(), releaseDate, Map.of()
        );
    }

    // ── OpenAI 模型 ───────────────────────────────────────────────────

    private static ProviderConfig buildOpenAIProvider() {
        var models = new LinkedHashMap<String, ModelInfo>();
        String npm = "@ai-sdk/openai";
        String api = "https://api.openai.com/v1";
        String pid = ProviderConfig.OPENAI;

        // GPT-5.5 Pro
        models.put("gpt-5.5-pro", openaiModel(
            "gpt-5.5-pro", "GPT-5.5 Pro", "gpt", npm, api,
            1_050_000, 128_000, null, 30, 180,
            true, false, "2026-05-01",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-5.5
        models.put("gpt-5.5", openaiModel(
            "gpt-5.5", "GPT-5.5", "gpt", npm, api,
            1_050_000, 128_000, null, 5, 30,
            true, false, "2026-05-01",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-5.4 Pro
        models.put("gpt-5.4-pro", openaiModel(
            "gpt-5.4-pro", "GPT-5.4 Pro", "gpt", npm, api,
            1_050_000, 128_000, null, 30, 180,
            true, false, "2026-03-05",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-5.4
        models.put("gpt-5.4", openaiModel(
            "gpt-5.4", "GPT-5.4", "gpt", npm, api,
            1_050_000, 128_000, 922_000, 2.5, 15,
            true, false, "2026-03-05",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-5.4 Mini
        models.put("gpt-5.4-mini", openaiModel(
            "gpt-5.4-mini", "GPT-5.4 Mini", "gpt-mini", npm, api,
            400_000, 128_000, null, 0.75, 4.5,
            true, false, "2026-03-17",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-5.4 Nano
        models.put("gpt-5.4-nano", openaiModel(
            "gpt-5.4-nano", "GPT-5.4 Nano", "gpt-nano", npm, api,
            400_000, 128_000, null, 0.2, 1.25,
            true, false, "2026-03-17",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-5.2 Pro
        models.put("gpt-5.2-pro", openaiModel(
            "gpt-5.2-pro", "GPT-5.2 Pro", "gpt", npm, api,
            400_000, 128_000, null, 21, 168,
            true, false, "2025-12-04",
            ModelInfo.Modalities.TEXT_IMAGE
        ));

        // GPT-5.2
        models.put("gpt-5.2", openaiModel(
            "gpt-5.2", "GPT-5.2", "gpt", npm, api,
            400_000, 128_000, null, 1.75, 14,
            true, false, "2025-12-04",
            ModelInfo.Modalities.TEXT_IMAGE
        ));

        // GPT-5.1
        models.put("gpt-5.1", openaiModel(
            "gpt-5.1", "GPT-5.1", "gpt", npm, api,
            400_000, 128_000, null, 1.25, 10,
            true, false, "2025-11-13",
            ModelInfo.Modalities.TEXT_IMAGE
        ));

        // GPT-5
        models.put("gpt-5", openaiModel(
            "gpt-5", "GPT-5", "gpt", npm, api,
            400_000, 128_000, 272_000, 1.25, 10,
            true, false, "2025-08-07",
            ModelInfo.Modalities.TEXT_IMAGE
        ));

        // GPT-5 Pro
        models.put("gpt-5-pro", openaiModel(
            "gpt-5-pro", "GPT-5 Pro", "gpt", npm, api,
            400_000, 272_000, null, 15, 120,
            true, false, "2025-08-07",
            ModelInfo.Modalities.TEXT_IMAGE
        ));

        // GPT-5 Mini
        models.put("gpt-5-mini", openaiModel(
            "gpt-5-mini", "GPT-5 Mini", "gpt-mini", npm, api,
            400_000, 128_000, 272_000, 0.25, 2,
            true, false, "2025-08-07",
            ModelInfo.Modalities.TEXT_IMAGE
        ));

        // GPT-5 Nano
        models.put("gpt-5-nano", openaiModel(
            "gpt-5-nano", "GPT-5 Nano", "gpt-nano", npm, api,
            400_000, 128_000, 272_000, 0.05, 0.4,
            true, false, "2025-08-07",
            ModelInfo.Modalities.TEXT_IMAGE
        ));

        // GPT-4.1
        models.put("gpt-4.1", openaiModel(
            "gpt-4.1", "GPT-4.1", "gpt", npm, api,
            1_047_576, 32_768, null, 2, 8,
            false, true, "2025-04-14",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-4.1 Mini
        models.put("gpt-4.1-mini", openaiModel(
            "gpt-4.1-mini", "GPT-4.1 Mini", "gpt-mini", npm, api,
            1_047_576, 32_768, null, 0.4, 1.6,
            false, true, "2025-04-14",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-4.1 Nano
        models.put("gpt-4.1-nano", openaiModel(
            "gpt-4.1-nano", "GPT-4.1 Nano", "gpt-nano", npm, api,
            1_047_576, 32_768, null, 0.1, 0.4,
            false, true, "2025-04-14",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-4o
        models.put("gpt-4o", openaiModel(
            "gpt-4o", "GPT-4o", "gpt", npm, api,
            128_000, 16_384, null, 2.5, 10,
            false, true, "2024-05-13",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // GPT-4o Mini
        models.put("gpt-4o-mini", openaiModel(
            "gpt-4o-mini", "GPT-4o Mini", "gpt-mini", npm, api,
            128_000, 16_384, null, 0.15, 0.6,
            false, true, "2024-07-18",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // o4-mini
        models.put("o4-mini", openaiModel(
            "o4-mini", "o4-mini", "o-mini", npm, api,
            200_000, 100_000, null, 1.1, 4.4,
            true, false, "2025-04-16",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // o3
        models.put("o3", openaiModel(
            "o3", "o3", "o", npm, api,
            200_000, 100_000, null, 2, 8,
            true, false, "2025-04-16",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // o3-pro
        models.put("o3-pro", openaiModel(
            "o3-pro", "o3-pro", "o", npm, api,
            200_000, 100_000, null, 20, 80,
            true, false, "2025-06-10",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // o3-mini
        models.put("o3-mini", openaiModel(
            "o3-mini", "o3-mini", "o-mini", npm, api,
            200_000, 100_000, null, 1.1, 4.4,
            true, false, "2024-12-20",
            ModelInfo.Modalities.TEXT_ONLY
        ));

        // o1
        models.put("o1", openaiModel(
            "o1", "o1", "o", npm, api,
            200_000, 100_000, null, 15, 60,
            true, false, "2024-12-17",
            ModelInfo.Modalities.TEXT_IMAGE_PDF
        ));

        // o1-mini
        models.put("o1-mini", openaiModel(
            "o1-mini", "o1-mini", "o-mini", npm, api,
            128_000, 65_536, null, 1.1, 4.4,
            true, false, "2024-09-12",
            ModelInfo.Modalities.TEXT_ONLY
        ));

        return new ProviderConfig(
            pid, "OpenAI", "custom",
            List.of("OPENAI_API_KEY"),
            null,
            Map.of(),
            Collections.unmodifiableMap(models)
        );
    }

    /**
     * 创建 OpenAI 模型条目的辅助方法。
     */
    private static ModelInfo openaiModel(
        String id, String name, String family, String npm, String apiUrl,
        int context, int output, Integer input,
        double costIn, double costOut,
        boolean reasoning, boolean temperature,
        String releaseDate,
        ModelInfo.Modalities inputModalities
    ) {
        return new ModelInfo(
            id, ProviderConfig.OPENAI, name, family,
            new ModelInfo.ApiInfo(id, apiUrl, npm),
            "active",
            new ModelInfo.Cost(costIn, costOut, ModelInfo.Cost.CacheCost.ZERO),
            new ModelInfo.Limit(context, input, output),
            new ModelInfo.Capabilities(
                temperature, reasoning, false, true,
                inputModalities,
                ModelInfo.Modalities.TEXT_ONLY,
                ModelInfo.Interleaved.DISABLED
            ),
            Map.of(), Map.of(), releaseDate, Map.of()
        );
    }
}
