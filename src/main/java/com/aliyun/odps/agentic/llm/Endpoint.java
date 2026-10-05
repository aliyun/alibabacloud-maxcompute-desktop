package com.aliyun.odps.agentic.llm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 端点定义，描述单一路由的 URL 构造方式。
 *
 * @param baseURL 端点基础 URL，例如 {@code https://api.anthropic.com}
 * @param path    URL 路径
 * @param query   静态查询参数
 */
public record Endpoint(
    String baseURL,
    String path,
    Map<String, String> query
) {

    /**
     * 仅通过路径创建端点。
     *
     * @param pathValue 路径值
     * @return 端点对象
     */
    public static Endpoint path(String pathValue) {
        return new Endpoint(null, pathValue, null);
    }

    /**
     * 通过路径和基础 URL 创建端点。
     *
     * @param pathValue 路径值
     * @param baseURL   基础 URL
     * @return 端点对象
     */
    public static Endpoint path(String pathValue, String baseURL) {
        return new Endpoint(baseURL, pathValue, null);
    }

    /**
     * 将当前端点与补丁对象合并，补丁中的非空值会覆盖原值。
     *
     * @param patch 补丁端点
     * @return 合并后的端点
     */
    public Endpoint merge(Endpoint patch) {
        if (patch == null) return this;
        String mergedBase = patch.baseURL != null ? patch.baseURL : this.baseURL;
        String mergedPath = patch.path != null ? patch.path : this.path;
        Map<String, String> mergedQuery = mergeQuery(this.query, patch.query);
        return new Endpoint(mergedBase, mergedPath, mergedQuery);
    }

    /**
     * 渲染完整的 URL 字符串。
     *
     * @return 完整 URL
     */
    public String render() {
        String base = baseURL != null ? trimTrailingSlash(baseURL) : "";
        String p = path != null ? path : "";
        String url = base + p;
        if (query != null && !query.isEmpty()) {
            StringBuilder sb = new StringBuilder(url);
            sb.append(url.contains("?") ? '&' : '?');
            var it = query.entrySet().iterator();
            while (it.hasNext()) {
                var entry = it.next();
                sb.append(entry.getKey()).append('=').append(entry.getValue());
                if (it.hasNext()) sb.append('&');
            }
            url = sb.toString();
        }
        return url;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static Map<String, String> mergeQuery(Map<String, String> base, Map<String, String> patch) {
        if (patch == null) return base;
        if (base == null) return patch;
        var result = new LinkedHashMap<>(base);
        result.putAll(patch);
        return Map.copyOf(result);
    }
}
