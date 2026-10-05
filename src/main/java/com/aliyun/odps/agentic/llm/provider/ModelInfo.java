package com.aliyun.odps.agentic.llm.provider;

import java.util.Map;

/**
 * 模型元数据，描述一个模型的标识、能力、费用和限制。
 *
 * @param id           模型标识
 * @param providerID   提供者标识
 * @param name         可读模型名称
 * @param family       模型家族
 * @param api          API 路由信息
 * @param status       模型状态：{@code "active"}、{@code "beta"}、{@code "alpha"}、{@code "deprecated"}
 * @param cost         Token 定价信息
 * @param limit        Token 限制
 * @param capabilities 模型能力
 * @param options      提供者特定选项
 * @param headers      附加请求头
 * @param releaseDate  发布日期
 * @param variants     推理强度变体映射
 */
public record ModelInfo(
    String id,
    String providerID,
    String name,
    String family,
    ApiInfo api,
    String status,
    Cost cost,
    Limit limit,
    Capabilities capabilities,
    Map<String, Object> options,
    Map<String, String> headers,
    String releaseDate,
    Map<String, Map<String, Object>> variants
) {

    /**
     * API 路由信息。
     *
     * @param id   发送给提供者的 API 模型标识
     * @param url  提供者 API URL
     * @param npm  对应的 AI SDK npm 包名
     */
    public record ApiInfo(String id, String url, String npm) {}

    /**
     * Token 定价信息（每百万 Token 的价格）。
     *
     * @param input  输入 Token 单价
     * @param output 输出 Token 单价
     * @param cache  缓存读写价格
     */
    public record Cost(double input, double output, CacheCost cache) {

        /**
         * 缓存定价信息。
         *
         * @param read  缓存读取价格
         * @param write 缓存写入价格
         */
        public record CacheCost(double read, double write) {
            /** 零费用常量。 */
            public static final CacheCost ZERO = new CacheCost(0, 0);
        }

        /** 零费用常量。 */
        public static final Cost FREE = new Cost(0, 0, CacheCost.ZERO);
    }

    /**
     * Token 限制信息。
     *
     * @param context 最大上下文窗口大小
     * @param input   最大输入 Token 数，{@code null} 表示与上下文相同
     * @param output  最大输出 Token 数
     */
    public record Limit(int context, Integer input, int output) {

        /**
         * 获取生效的输入 Token 上限。
         *
         * @return 输入 Token 上限
         */
        public int effectiveInput() {
            return input != null ? input : context;
        }
    }

    /**
     * 模型能力描述。
     *
     * @param temperature 是否支持温度参数
     * @param reasoning   是否支持推理或思考模式
     * @param attachment  是否支持文件附件
     * @param toolcall    是否支持工具调用
     * @param input       输入模态
     * @param output      输出模态
     * @param interleaved 交织推理支持配置
     */
    public record Capabilities(
        boolean temperature,
        boolean reasoning,
        boolean attachment,
        boolean toolcall,
        Modalities input,
        Modalities output,
        Interleaved interleaved
    ) {
        /** 默认能力：仅文本、支持工具调用。 */
        public static final Capabilities DEFAULT = new Capabilities(
            false, false, false, true,
            Modalities.TEXT_ONLY, Modalities.TEXT_ONLY,
            Interleaved.DISABLED
        );
    }

    /**
     * 输入或输出模态。
     *
     * @param text  文本
     * @param audio 音频
     * @param image 图像
     * @param video 视频
     * @param pdf   PDF 或文档
     */
    public record Modalities(
        boolean text,
        boolean audio,
        boolean image,
        boolean video,
        boolean pdf
    ) {
        /** 仅文本模态。 */
        public static final Modalities TEXT_ONLY = new Modalities(true, false, false, false, false);
        /** 文本与图像模态。 */
        public static final Modalities TEXT_IMAGE = new Modalities(true, false, true, false, false);
        /** 文本、图像与 PDF 模态。 */
        public static final Modalities TEXT_IMAGE_PDF = new Modalities(true, false, true, false, true);

        /**
         * 检查是否支持指定模态。
         *
         * @param modality 模态名称
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

    /**
     * 交织推理配置。
     *
     * <p>可禁用、启用或指定字段名称。
     */
    public sealed interface Interleaved {
        Interleaved DISABLED = new Disabled();
        Interleaved ENABLED = new Enabled();

        /** 禁用交织推理。 */
        record Disabled() implements Interleaved {}
        /** 启用交织推理。 */
        record Enabled() implements Interleaved {}
        /**
         * 使用指定字段名来读取推理内容。
         *
         * @param field 字段名称
         */
        record WithField(String field) implements Interleaved {}

        /**
         * 判断交织推理是否已启用。
         *
         * @return 已启用时返回 {@code true}
         */
        default boolean isEnabled() {
            return !(this instanceof Disabled);
        }
    }

    /**
     * 将本对象的限制信息转换为 {@link com.aliyun.odps.agentic.llm.ModelLimit}。
     *
     * @return 通用的模型限制对象
     */
    public com.aliyun.odps.agentic.llm.ModelLimit toModelLimit() {
        return new com.aliyun.odps.agentic.llm.ModelLimit(
            limit.context(),
            limit.input(),
            limit.output()
        );
    }
}
