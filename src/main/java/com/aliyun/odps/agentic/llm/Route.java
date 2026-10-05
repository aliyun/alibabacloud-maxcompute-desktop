package com.aliyun.odps.agentic.llm;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.aliyun.odps.agentic.model.Message;

import java.net.http.HttpRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 路由定义，将协议、端点、鉴权和消息格式组合为不可变配置单元。
 *
 * @param id            提供者标识
 * @param protocol      协议转换器，负责构建请求与解析流式响应
 * @param baseUrl       请求端点 URL
 * @param auth          鉴权管线，用于向请求追加认证请求头
 * @param messageFormat 消息序列化格式
 * @param defaults      路由级默认配置
 */
public record Route(
    String id,
    @JsonIgnore ProviderTransform protocol,
    String baseUrl,
    @JsonIgnore Auth.AuthFn auth,
    MessageFormat messageFormat,
    @JsonIgnore RouteDefaults defaults
) {

    /**
     * 紧凑构造方法；当鉴权或默认配置为空时填充默认值。
     */
    public Route {
        if (auth == null) auth = Auth.none;
        if (defaults == null) defaults = RouteDefaults.EMPTY;
    }

    /**
     * 创建不显式传入默认配置的路由对象。
     */
    public Route(String id, ProviderTransform protocol, String baseUrl,
                 Auth.AuthFn auth, MessageFormat messageFormat) {
        this(id, protocol, baseUrl, auth, messageFormat, RouteDefaults.EMPTY);
    }

    /**
     * 基于当前路由创建绑定模型。
     *
     * @param modelId 模型标识
     * @param limit   模型限制
     * @return 绑定到当前路由的模型
     */
    public Model model(String modelId, ModelLimit limit) {
        return new Model(modelId, this, limit);
    }

    /**
     * 创建一个派生路由，可替换基础 URL 与鉴权函数。
     *
     * @param newBaseUrl 新基础 URL
     * @param newAuth    新鉴权函数
     * @return 派生后的路由
     */
    public Route with(String newBaseUrl, Auth.AuthFn newAuth) {
        return new Route(
            id,
            protocol,
            newBaseUrl != null ? newBaseUrl : baseUrl,
            newAuth != null ? newAuth : auth,
            messageFormat,
            defaults
        );
    }

    /**
     * 创建一个派生路由，并合并新的默认配置。
     *
     * @param newBaseUrl  新基础 URL
     * @param newAuth     新鉴权函数
     * @param newDefaults 新默认配置
     * @return 派生后的路由
     */
    public Route with(String newBaseUrl, Auth.AuthFn newAuth, RouteDefaults newDefaults) {
        return new Route(
            id,
            protocol,
            newBaseUrl != null ? newBaseUrl : baseUrl,
            newAuth != null ? newAuth : auth,
            messageFormat,
            newDefaults != null ? defaults.merge(newDefaults) : defaults
        );
    }

    /**
     * 通过补丁对象创建完整派生路由。
     *
     * @param patch 路由补丁
     * @return 派生后的路由
     */
    public Route with(RoutePatch patch) {
        return new Route(
            patch.id() != null ? patch.id() : id,
            patch.protocol() != null ? patch.protocol() : protocol,
            patch.baseUrl() != null ? patch.baseUrl() : baseUrl,
            patch.auth() != null ? patch.auth() : auth,
            patch.messageFormat() != null ? patch.messageFormat() : messageFormat,
            patch.defaults() != null ? defaults.merge(patch.defaults()) : defaults
        );
    }

    /**
     * 将内部 {@link Message} 列表转换为提供者线协议格式。
     *
     * @param messages 内部消息列表
     * @param model    模型信息
     * @return 提供者格式消息列表
     */
    public List<Map<String, Object>> convertMessages(List<Message> messages, Model model) {
        return switch (messageFormat) {
            case ANTHROPIC -> MessageConverter.toAnthropicMessages(messages, model);
            case OPENAI -> MessageConverter.toOpenAIMessages(messages, model);
        };
    }

    /**
     * 结合协议转换器与鉴权管线构建最终认证请求。
     *
     * @param request LLM 请求
     * @return 已附带鉴权信息的 HTTP 请求
     * @throws Exception 构建请求失败时抛出
     */
    public HttpRequest buildAuthenticatedRequest(LlmRequest request) throws Exception {
        HttpRequest baseRequest = protocol.buildHttpRequest(request, baseUrl, "");
        return applyAuth(baseRequest);
    }

    /**
     * 将鉴权管线应用到已有 HTTP 请求上，并替换鉴权相关请求头。
     */
    private HttpRequest applyAuth(HttpRequest base) {
        Set<String> AUTH_HEADER_NAMES = Set.of("authorization", "x-api-key");

        Map<String, String> baseHeaders = new LinkedHashMap<>();
        for (var entry : base.headers().map().entrySet()) {
            if (!AUTH_HEADER_NAMES.contains(entry.getKey().toLowerCase())) {
                if (!entry.getValue().isEmpty()) {
                    baseHeaders.put(entry.getKey(), entry.getValue().getFirst());
                }
            }
        }

        Map<String, String> authedHeaders;
        try {
            var authInput = new Auth.AuthInput(
                base.method(), base.uri().toString(), "", baseHeaders);
            authedHeaders = auth.apply(authInput);
        } catch (Auth.MissingCredentialError e) {
            authedHeaders = baseHeaders;
        }

        var builder = HttpRequest.newBuilder().uri(base.uri());
        for (var entry : authedHeaders.entrySet()) {
            builder.header(entry.getKey(), entry.getValue());
        }
        var bodyPublisher = base.bodyPublisher()
            .orElse(HttpRequest.BodyPublishers.noBody());
        builder.method(base.method(), bodyPublisher);
        base.timeout().ifPresent(builder::timeout);

        return builder.build();
    }

    // ── Route.make ───────────────────────────────────────────────────────

    /**
     * 路由补丁对象，用于不可变派生。
     *
     * @param id            提供者标识
     * @param protocol      协议转换器
     * @param baseUrl       基础 URL
     * @param auth          鉴权函数
     * @param messageFormat 消息格式
     * @param defaults      默认配置
     */
    public record RoutePatch(
        String id,
        ProviderTransform protocol,
        String baseUrl,
        Auth.AuthFn auth,
        MessageFormat messageFormat,
        RouteDefaults defaults
    ) {
        /**
         * 创建一个所有字段均为空的补丁对象。
         *
         * @return 空补丁对象
         */
        public static RoutePatch of() {
            return new RoutePatch(null, null, null, null, null, null);
        }
    }

    /**
     * 创建基础路由对象。
     *
     * @param id            提供者标识
     * @param protocol      协议转换器
     * @param baseUrl       基础 URL
     * @param auth          鉴权函数
     * @param messageFormat 消息格式
     * @return 路由对象
     */
    public static Route make(String id, ProviderTransform protocol, String baseUrl,
                             Auth.AuthFn auth, MessageFormat messageFormat) {
        return new Route(id, protocol, baseUrl, auth, messageFormat, RouteDefaults.EMPTY);
    }

    /**
     * 创建带默认配置的路由对象。
     *
     * @param id            提供者标识
     * @param protocol      协议转换器
     * @param baseUrl       基础 URL
     * @param auth          鉴权函数
     * @param messageFormat 消息格式
     * @param defaults      默认配置
     * @return 路由对象
     */
    public static Route make(String id, ProviderTransform protocol, String baseUrl,
                             Auth.AuthFn auth, MessageFormat messageFormat,
                             RouteDefaults defaults) {
        return new Route(id, protocol, baseUrl, auth, messageFormat, defaults);
    }
}
