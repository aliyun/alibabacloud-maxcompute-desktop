package com.aliyun.odps.agentic.llm;

/**
 * 模型的 Token 限制配置。
 *
 * @param context 最大上下文窗口大小
 * @param input   最大输入 Token 数，可为空，为空时回退到 {@code context}
 * @param output  最大输出 Token 数，可为空
 */
public record ModelLimit(
    int context,
    Integer input,
    Integer output
) {
    /**
     * 获取生效的输入 Token 上限；若未显式设置，则使用上下文窗口大小。
     *
     * @return 生效的输入 Token 上限
     */
    public int effectiveInputLimit() {
        return input != null ? input : context;
    }
}
