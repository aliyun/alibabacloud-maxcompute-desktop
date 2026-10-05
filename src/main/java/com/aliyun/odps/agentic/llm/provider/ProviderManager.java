package com.aliyun.odps.agentic.llm.provider;

import com.aliyun.odps.agentic.llm.SmallModelFallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 提供者管理器 — 管理提供者发现、模型目录解析与 API Key 绑定的完整生命周期。
 *
 * <p>初始化流程：
 * <ol>
 *   <li>加载模型目录（来自 models.dev 快照）</li>
 *   <li>应用配置覆盖</li>
 *   <li>解析环境变量中的 API Key</li>
 *   <li>过滤已弃用模型</li>
 *   <li>构建最终提供者映射</li>
 * </ol>
 */
public final class ProviderManager {

    private static final Logger log = LoggerFactory.getLogger(ProviderManager.class);

    /** 活跃提供者，按提供者 ID 索引。 */
    private final Map<String, ProviderConfig> providers;

    /** 配置的模型覆盖。 */
    private final String configuredModel;

    /** 配置的小模型覆盖。 */
    private final String configuredSmallModel;

    /**
     * 私有构造 — 使用 {@link #create()} 或 {@link #create(Config)}。
     */
    private ProviderManager(
        Map<String, ProviderConfig> providers,
        String configuredModel,
        String configuredSmallModel
    ) {
        this.providers = Collections.unmodifiableMap(providers);
        this.configuredModel = configuredModel;
        this.configuredSmallModel = configuredSmallModel;
    }

    // ── 工厂方法 ──────────────────────────────────────────────────────

    /**
     * 使用默认目录和环境变量解析创建提供者管理器。
     *
     * @return 提供者管理器实例
     */
    public static ProviderManager create() {
        return create(Config.DEFAULT);
    }

    /**
     * 使用显式配置创建提供者管理器，合并目录、配置和环境变量。
     *
     * @param config 提供者配置
     * @return 提供者管理器实例
     */
    public static ProviderManager create(Config config) {
        var catalog = ModelCatalog.allProviders();
        var activeProviders = new LinkedHashMap<String, ProviderConfig>();

        var disabled = config.disabledProviders != null ? Set.copyOf(config.disabledProviders) : Set.<String>of();

        // 步骤 1：从目录加载（来自 models.dev）
        for (var entry : catalog.entrySet()) {
            String providerID = entry.getKey();
            if (disabled.contains(providerID)) continue;

            var provider = entry.getValue();

            // 步骤 2：检查环境变量中的 API Key
            String resolvedKey = resolveApiKey(provider.env());
            if (resolvedKey != null) {
                activeProviders.put(providerID, new ProviderConfig(
                    provider.id(), provider.name(), "env",
                    provider.env(), resolvedKey, provider.options(),
                    provider.models()
                ));
                log.info("Provider found via env: {}", providerID);
            }
        }

        // 步骤 3：应用配置覆盖
        if (config.providerOverrides != null) {
            for (var entry : config.providerOverrides.entrySet()) {
                String providerID = entry.getKey();
                if (disabled.contains(providerID)) continue;

                var override = entry.getValue();
                var existing = activeProviders.get(providerID);
                var catalogEntry = catalog.get(providerID);

                // 合并覆盖与现有或目录条目
                var base = existing != null ? existing : catalogEntry;
                if (base == null) {
                    log.warn("Config references unknown provider: {}", providerID);
                    continue;
                }

                // 合并模型
                var mergedModels = new LinkedHashMap<>(base.models());
                if (override.models() != null) {
                    mergedModels.putAll(override.models());
                }

                // 合并选项
                var mergedOptions = new LinkedHashMap<>(base.options());
                if (override.options() != null) {
                    mergedOptions.putAll(override.options());
                }

                String key = override.key() != null ? override.key() :
                    (base.key() != null ? base.key() : resolveApiKey(base.env()));

                activeProviders.put(providerID, new ProviderConfig(
                    providerID,
                    override.name() != null ? override.name() : base.name(),
                    "config",
                    override.env() != null ? override.env() : base.env(),
                    key,
                    Collections.unmodifiableMap(mergedOptions),
                    Collections.unmodifiableMap(mergedModels)
                ));
                log.info("Provider configured from config: {}", providerID);
            }
        }

        // 步骤 4：过滤已弃用模型
        for (var entry : new ArrayList<>(activeProviders.entrySet())) {
            var provider = entry.getValue();
            var filteredModels = new LinkedHashMap<String, ModelInfo>();
            for (var modelEntry : provider.models().entrySet()) {
                var model = modelEntry.getValue();
                if (!"deprecated".equals(model.status())) {
                    filteredModels.put(modelEntry.getKey(), model);
                }
            }

            // 移除无模型的提供者
            if (filteredModels.isEmpty()) {
                activeProviders.remove(entry.getKey());
                continue;
            }

            if (filteredModels.size() != provider.models().size()) {
                activeProviders.put(entry.getKey(), new ProviderConfig(
                    provider.id(), provider.name(), provider.source(),
                    provider.env(), provider.key(), provider.options(),
                    Collections.unmodifiableMap(filteredModels)
                ));
            }
        }

        return new ProviderManager(activeProviders, config.model, config.smallModel);
    }

    // ── 公开 API ──────────────────────────────────────────────────────

    /**
     * 解析模型字符串为提供者 ID 和模型 ID。
     *
     * @param model 格式：{@code "providerID/modelID"}（例如 {@code "anthropic/claude-sonnet-4-20250514"}）
     * @return 解析结果，包含 providerID 和 modelID
     */
    public static ParsedModel parseModel(String model) {
        int slash = model.indexOf('/');
        if (slash < 0) {
            // 无斜杠时尝试从模型 ID 推断提供者
            return new ParsedModel("", model);
        }
        String providerID = model.substring(0, slash);
        String modelID = model.substring(slash + 1);
        return new ParsedModel(providerID, modelID);
    }

    /**
     * 从模型字符串解析提供者和模型。
     * 组合 {@link #parseModel(String)} + {@link #getModel(String, String)} + 提供者查找。
     *
     * @param modelString 格式：{@code "providerID/modelID"}
     * @return 已解析的提供者和模型信息，未找到时返回空
     */
    public Optional<ResolvedModel> fromModel(String modelString) {
        var parsed = parseModel(modelString);
        if (parsed.providerID().isEmpty()) {
            // 在所有提供者中搜索此模型 ID
            for (var provider : providers.values()) {
                var model = provider.models().get(parsed.modelID());
                if (model != null) {
                    return Optional.of(new ResolvedModel(provider, model));
                }
            }
            return Optional.empty();
        }
        var provider = providers.get(parsed.providerID());
        if (provider == null) return Optional.empty();
        var model = provider.models().get(parsed.modelID());
        if (model == null) return Optional.empty();
        return Optional.of(new ResolvedModel(provider, model));
    }

    /**
     * 列出所有已配置的提供者。
     *
     * @return 不可修改的提供者映射
     */
    public Map<String, ProviderConfig> list() {
        return providers;
    }

    /**
     * {@link #list()} 的别名。
     */
    public Map<String, ProviderConfig> all() {
        return providers;
    }

    /**
     * 根据 ID 获取提供者。
     *
     * @param providerID 提供者标识
     * @return 提供者配置，不存在时返回空
     */
    public Optional<ProviderConfig> getProvider(String providerID) {
        return Optional.ofNullable(providers.get(providerID));
    }

    /**
     * 从指定提供者获取模型。
     *
     * @param providerID 提供者标识
     * @param modelID    模型标识
     * @return 模型信息，不存在时返回空
     */
    public Optional<ModelInfo> getModel(String providerID, String modelID) {
        var provider = providers.get(providerID);
        if (provider == null) return Optional.empty();
        return Optional.ofNullable(provider.models().get(modelID));
    }

    /**
     * 解析默认模型。
     *
     * <p>解析顺序：
     * <ol>
     *   <li>显式配置的模型设置</li>
     *   <li>第一个提供者中按优先级排序的第一个模型</li>
     * </ol>
     *
     * @return 已解析的模型，未找到时返回空
     */
    public Optional<ResolvedModel> defaultModel() {
        // 首先检查配置覆盖
        if (configuredModel != null && !configuredModel.isBlank()) {
            return fromModel(configuredModel);
        }

        // 查找第一个有模型的提供者，选择最佳模型
        for (var provider : providers.values()) {
            var sorted = sort(new ArrayList<>(provider.models().values()));
            if (!sorted.isEmpty()) {
                var model = sorted.getFirst();
                return Optional.of(new ResolvedModel(provider, model));
            }
        }
        return Optional.empty();
    }

    /**
     * 为指定提供者查找小型/快速模型。
     *
     * @param providerID 提供者标识
     * @return 小模型信息，未找到时返回空
     */
    public Optional<ModelInfo> getSmallModel(String providerID) {
        // 首先检查配置覆盖
        if (configuredSmallModel != null && !configuredSmallModel.isBlank()) {
            var parsed = parseModel(configuredSmallModel);
            return getModel(parsed.providerID(), parsed.modelID());
        }

        var provider = providers.get(providerID);
        if (provider == null) return Optional.empty();

        var candidates = new ArrayList<ModelInfo>();
        for (var model : provider.models().values()) {
            if (SmallModelFallback.isSmallModel(model.id())
                    || SmallModelFallback.isSmallModel(model.family())) {
                candidates.add(model);
            }
        }
        sort(candidates);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
    }

    /**
     * 按优先级对模型列表排序。
     *
     * <p>排序规则：
     * <ol>
     *   <li>显式覆盖但未填写日期的模型优先，其余按目录发布日期排序</li>
     *   <li>同日期时，latest 别名优先</li>
     *   <li>最后按 ID 倒序稳定排序</li>
     * </ol>
     *
     * @param models 待排序的模型列表
     * @return 排序后的列表（原地修改）
     */
    public static <T extends ModelInfo> List<T> sort(List<T> models) {
        models.sort(Comparator
            .<T, String>comparing(m -> m.releaseDate() == null || m.releaseDate().isBlank()
                ? "9999-12-31" : m.releaseDate(), Comparator.reverseOrder())
            .thenComparing(m -> m.id().contains("latest") ? 0 : 1)
            .thenComparing(ModelInfo::id, Comparator.reverseOrder())
        );
        return models;
    }

    /**
     * 使用已解析的模型信息创建 {@link ProviderInstance}，
     * 桥接提供者管理器与现有的 Anthropic/OpenAI/OpenAICompatible 外观。
     *
     * @param resolved 已解析的模型
     * @return 提供者实例，创建失败时返回空
     */
    public Optional<ProviderInstance> toProviderInstance(ResolvedModel resolved) {
        var provider = resolved.provider();
        var model = resolved.model();
        String apiKey = provider.key();

        return Optional.of(switch (provider.id()) {
            case ProviderConfig.ANTHROPIC -> Anthropic.configure(apiKey);
            case ProviderConfig.OPENAI -> OpenAI.configure(apiKey);
            default -> {
                // 其他提供者回退到 OpenAI 兼容模式
                String baseUrl = model.api().url();
                yield OpenAICompatible.configure(provider.id(), baseUrl, apiKey);
            }
        });
    }

    // ── 辅助方法 ──────────────────────────────────────────────────────

    /**
     * 从环境变量解析 API Key。
     */
    private static String resolveApiKey(List<String> envVars) {
        if (envVars == null) return null;
        for (String envVar : envVars) {
            String value = System.getenv(envVar);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    // ── 结果类型 ──────────────────────────────────────────────────────

    /**
     * 模型字符串解析结果。
     *
     * @param providerID 提供者标识
     * @param modelID    模型标识
     */
    public record ParsedModel(String providerID, String modelID) {}

    /**
     * 已解析的模型及其提供者 — 由 {@link #fromModel(String)} 和 {@link #defaultModel()} 返回。
     *
     * @param provider 提供者配置
     * @param model    模型信息
     */
    public record ResolvedModel(ProviderConfig provider, ModelInfo model) {
        /** 返回提供者标识。 */
        public String providerID() { return provider.id(); }
        /** 返回模型标识。 */
        public String modelID() { return model.id(); }
    }

    // ── 配置 ──────────────────────────────────────────────────────────

    /**
     * 管理器配置 — 驱动提供者初始化。
     * 对应 {@code cfg.provider}、{@code cfg.model}、{@code cfg.small_model}、{@code cfg.disabled_providers}。
     */
    public static final class Config {
        public static final Config DEFAULT = new Config();

        /** 默认模型字符串（provider/model 格式）。 */
        String model;

        /** 小模型覆盖。 */
        String smallModel;

        /** 来自配置文件的提供者覆盖。 */
        Map<String, ProviderConfig> providerOverrides;

        /** 已禁用的提供者 ID 列表。 */
        List<String> disabledProviders;

        /**
         * 设置默认模型。
         */
        public Config model(String model) {
            this.model = model;
            return this;
        }

        /**
         * 设置小模型覆盖。
         */
        public Config smallModel(String smallModel) {
            this.smallModel = smallModel;
            return this;
        }

        /**
         * 设置提供者覆盖映射。
         */
        public Config providerOverrides(Map<String, ProviderConfig> overrides) {
            this.providerOverrides = overrides;
            return this;
        }

        /**
         * 设置已禁用的提供者列表。
         */
        public Config disabledProviders(List<String> disabled) {
            this.disabledProviders = disabled;
            return this;
        }
    }
}
