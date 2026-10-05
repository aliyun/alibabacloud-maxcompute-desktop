package com.aliyun.odps.agentic.llm;

import java.util.Map;

/**
 * 路由默认配置，表示会应用到该路由每次请求上的静态参数。
 *
 * @param headers         在鉴权执行前附加的静态请求头
 * @param limits          模型 Token 限制覆盖项
 * @param providerOptions 提供者特定选项
 */
public record RouteDefaults(
    Map<String, String> headers,
    ModelLimit limits,
    Map<String, Object> providerOptions
) {
    /** 空默认配置。 */
    public static final RouteDefaults EMPTY = new RouteDefaults(null, null, null);

    /**
     * 将当前默认配置与补丁合并，补丁中的值优先。
     *
     * @param patch 补丁配置
     * @return 合并后的默认配置
     */
    public RouteDefaults merge(RouteDefaults patch) {
        if (patch == null) return this;
        return new RouteDefaults(
            mergeHeaders(this.headers, patch.headers),
            patch.limits != null ? patch.limits : this.limits,
            mergeProviderOptions(this.providerOptions, patch.providerOptions)
        );
    }

    private static Map<String, String> mergeHeaders(Map<String, String> base, Map<String, String> patch) {
        if (patch == null || patch.isEmpty()) return base;
        if (base == null || base.isEmpty()) return patch;
        var result = new java.util.LinkedHashMap<>(base);
        result.putAll(patch);
        return Map.copyOf(result);
    }

    private static Map<String, Object> mergeProviderOptions(Map<String, Object> base, Map<String, Object> patch) {
        if (patch == null || patch.isEmpty()) return base;
        if (base == null || base.isEmpty()) return patch;
        var result = new java.util.LinkedHashMap<>(base);
        result.putAll(patch);
        return Map.copyOf(result);
    }
}
