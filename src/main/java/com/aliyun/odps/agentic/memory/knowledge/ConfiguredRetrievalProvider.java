package com.aliyun.odps.agentic.memory.knowledge;

import com.aliyun.odps.agentic.config.AgentAdvancedSettings;
import com.aliyun.odps.agentic.operation.ModelOperationRunner;
import java.net.URI;
import java.util.Objects;
import java.util.function.Supplier;

/** Reads host-owned credentials and builds a retrieval client for the configured provider origin. */
public class ConfiguredRetrievalProvider implements KnowledgeRetrievalProvider {
    public record ConnectionSettings(boolean builtin,String apiUrl,String apiKey) {}
    private final Supplier<ConnectionSettings> settings;
    private final AgentAdvancedSettings advanced;
    private ModelOperationRunner operations=new ModelOperationRunner();
    public ConfiguredRetrievalProvider(Supplier<ConnectionSettings> settings,AgentAdvancedSettings advanced) {
        this.settings=Objects.requireNonNull(settings); this.advanced=advanced;
    }
    public void setModelOperations(ModelOperationRunner runner) { operations=Objects.requireNonNull(runner); }
    public Capability capability() { return capability(settings.get()); }
    private Capability capability(ConnectionSettings config) {
        if (config==null) return Capability.denied("尚未配置 AI 模型，请先在 设置 → AI 设置 中配置百炼 provider 与 API Key");
        if (config.builtin()) return Capability.denied("当前使用内置 AI 额度，不含向量检索凭据；请在 AI 设置中配置自己的百炼 API Key 后重试");
        if (config.apiKey()==null || config.apiKey().isBlank()) return Capability.denied("AI 设置中的 API Key 为空，请补全后重试");
        try {
            URI uri=URI.create(config.apiUrl());
            String host=uri.getHost();
            boolean bailian=host!=null && (host.equals("dashscope.aliyuncs.com") || host.endsWith(".dashscope.aliyuncs.com")
                || host.endsWith(".maas.aliyuncs.com"));
            if (!bailian || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo()!=null) {
                return Capability.denied("知识库检索模型目前仅支持百炼（DashScope）endpoint，当前 AI 设置为: "+config.apiUrl());
            }
            return Capability.ok();
        } catch (RuntimeException e) { return Capability.denied("知识库检索 endpoint 无效，请检查 AI 设置中的 API URL"); }
    }
    public DashScopeRetrievalClient client() {
        var config=settings.get(); var cap=capability(config);
        if (!cap.available()) throw new IllegalStateException(cap.reason());
        URI uri=URI.create(config.apiUrl());
        String origin=uri.getScheme()+"://"+uri.getRawAuthority();
        var client=new DashScopeRetrievalClient(config.apiKey(),origin);
        client.setModelOperations(operations); return client;
    }
    public String embeddingModel() { return advanced==null ? AgentAdvancedSettings.DEFAULT_EMBEDDING_MODEL : advanced.getEmbeddingModel(); }
    public String rerankModel() { return advanced==null ? AgentAdvancedSettings.DEFAULT_RERANK_MODEL : advanced.getRerankModel(); }
}
