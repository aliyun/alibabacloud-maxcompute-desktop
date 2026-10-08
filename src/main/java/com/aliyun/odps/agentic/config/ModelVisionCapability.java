package com.aliyun.odps.agentic.config;

/**
 * 模型的图片输入能力（多模态动态适配，2026-08-21）。
 *
 * <p>为什么必须三态而不是 boolean：DashScope 上同一 endpoint 的模型对图片有三种截然不同的反应
 * （实测 §1 P6b）：
 * <ul>
 *   <li>{@code VISION} —— 图片被消费（prompt_tokens 显著上涨）：qwen3.8-max / qwen3.8-27b /
 *       qwen3.7-plus / qwen3.5-plus</li>
 *   <li>{@code TEXT_ONLY} —— 两种子形态都归到这里：<b>显式 400</b>（qwen3.7-max）与
 *       <b>静默丢弃</b>（qwen3-max / glm-5.2 / glm-5 / deepseek-v4-pro，HTTP 200 但 prompt_tokens
 *       一字不涨——最危险的一种，模型对图完全无感却假装回答）</li>
 *   <li>{@code UNKNOWN} —— 探测被环境噪声打断（401/403/429/5xx/超时）。<b>永不落库</b>，
 *       且按"不发图"降级处理（fail-safe：宁可退化成纯文本，也不能把图发给可能静默丢弃的模型）</li>
 * </ul>
 */
public enum ModelVisionCapability {
    VISION,
    TEXT_ONLY,
    UNKNOWN;

    /** 只有确认 VISION 才允许把图片放进请求体。UNKNOWN 一律按不支持处理。 */
    public boolean canSendImages() {
        return this == VISION;
    }
}
