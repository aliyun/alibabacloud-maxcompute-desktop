package com.aliyun.odps.agentic.llm;

/**
 * LLM 响应中的 Token 使用情况。
 *
 * @param inputTokens              提示词消耗的 Token 数
 * @param outputTokens             模型生成的 Token 数
 * @param cacheReadInputTokens     从缓存读取的输入 Token 数
 * @param cacheCreationInputTokens 写入缓存的输入 Token 数
 * @param reasoningTokens          推理内容消耗的输出 Token 数
 */
public record Usage(
    int inputTokens,
    int outputTokens,
    int cacheReadInputTokens,
    int cacheCreationInputTokens,
    int reasoningTokens
) {
    /**
     * 创建不带推理 Token 统计的兼容构造方法。
     */
    public Usage(int inputTokens, int outputTokens, int cacheReadInputTokens, int cacheCreationInputTokens) {
        this(inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, 0);
    }

    /**
     * 计算输入与输出 Token 的总数。
     *
     * @return Token 总数
     */
    public int totalTokens() {
        return inputTokens + outputTokens;
    }
}
