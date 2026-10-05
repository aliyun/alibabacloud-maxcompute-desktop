package com.aliyun.odps.agentic.llm;

/**
 * 模型定义，描述一个绑定到特定 {@link Route} 的 LLM 模型。
 *
 * @param id        发送给提供者的 API 模型标识
 * @param route     该模型绑定的路由，包含协议、端点和鉴权信息
 * @param limit     模型的 Token 限制
 * @param inputCaps 输入模态能力，为空时默认仅支持文本
 */
public record Model(
    String id,
    Route route,
    ModelLimit limit,
    InputCapabilities inputCaps
) {
    /**
     * 创建默认仅支持文本输入的模型对象。
     */
    public Model(String id, Route route, ModelLimit limit) {
        this(id, route, limit, InputCapabilities.TEXT_ONLY);
    }

    /**
     * 创建一个兼容旧调用方式的模型对象。
     *
     * @param providerId 提供者标识
     * @param apiId      API 模型标识
     * @param limit      Token 限制
     * @return 模型对象
     */
    public static Model of(String providerId, String apiId, ModelLimit limit) {
        MessageFormat format = "anthropic".equals(providerId) ? MessageFormat.ANTHROPIC : MessageFormat.OPENAI;
        Route route = new Route(providerId, null, "", Auth.none, format);
        return new Model(apiId, route, limit);
    }

    /**
     * 创建带输入能力信息的模型对象。
     *
     * @param providerId 提供者标识
     * @param apiId      API 模型标识
     * @param limit      Token 限制
     * @param inputCaps  输入模态能力
     * @return 模型对象
     */
    public static Model of(String providerId, String apiId, ModelLimit limit, InputCapabilities inputCaps) {
        MessageFormat format = "anthropic".equals(providerId) ? MessageFormat.ANTHROPIC : MessageFormat.OPENAI;
        Route route = new Route(providerId, null, "", Auth.none, format);
        return new Model(apiId, route, limit, inputCaps);
    }

    /**
     * 返回模型所属的提供者标识。
     *
     * @return 提供者标识；若路由为空则返回 {@code "unknown"}
     */
    public String providerId() {
        return route != null ? route.id() : "unknown";
    }

    /**
     * 返回发送给提供者的 API 模型标识。
     *
     * @return API 模型标识
     */
    public String apiId() {
        return id;
    }

    /**
     * 模型支持的输入模态能力。
     *
     * @param text  是否支持文本输入
     * @param image 是否支持图片输入
     * @param audio 是否支持音频输入
     * @param video 是否支持视频输入
     * @param pdf   是否支持 PDF 或文档输入
     */
    public record InputCapabilities(
        boolean text,
        boolean image,
        boolean audio,
        boolean video,
        boolean pdf
    ) {
        /** 默认的纯文本输入能力。 */
        public static final InputCapabilities TEXT_ONLY = new InputCapabilities(true, false, false, false, false);

        /** 支持文本和图片输入。 */
        public static final InputCapabilities WITH_IMAGE = new InputCapabilities(true, true, false, false, false);

        /** 支持文本、图片和 PDF 输入。 */
        public static final InputCapabilities WITH_IMAGE_AND_PDF = new InputCapabilities(true, true, false, false, true);

        /**
         * 检查是否支持指定模态。
         *
         * @param modality 模态名称，可为 {@code "text"}、{@code "image"}、{@code "audio"}、{@code "video"}、{@code "pdf"}
         * @return 支持时返回 {@code true}
         */
        public boolean supports(String modality) {
            return switch (modality) {
                case "text" -> text;
                case "image" -> image;
                case "audio" -> audio;
                case "video" -> video;
                case "pdf" -> pdf;
                default -> false;
            };
        }
    }
}
