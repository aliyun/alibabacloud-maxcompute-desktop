package com.aliyun.odps.agentic.model;

/**
 * Token 使用统计，支持在会话维度上累加。
 * 包含输入、输出、缓存读写等各类 Token 计数。
 *
 * @param input      非缓存输入 Token 数
 * @param output     输出 Token 数
 * @param reasoning  推理 Token 数
 * @param cacheRead  从缓存读取的 Token 数
 * @param cacheWrite 写入缓存的 Token 数
 * @param total      Token 总数
 */
public record Tokens(
    int input,
    int output,
    int reasoning,
    int cacheRead,
    int cacheWrite,
    int total
) {
    /**
     * 创建一个所有计数均为 0 的空 Token 统计对象。
     *
     * @return 空的 Token 统计
     */
    public static Tokens empty() {
        return new Tokens(0, 0, 0, 0, 0, 0);
    }

    /**
     * 将两个 Token 统计对象相加。
     *
     * @param other 另一个 Token 统计对象
     * @return 累加后的结果
     */
    public Tokens add(Tokens other) {
        return new Tokens(
            this.input + other.input,
            this.output + other.output,
            this.reasoning + other.reasoning,
            this.cacheRead + other.cacheRead,
            this.cacheWrite + other.cacheWrite,
            this.total + other.total
        );
    }

    /**
     * 根据原始 {@link com.aliyun.odps.agentic.llm.Usage} 创建 Token 统计对象。
     *
     * @param usage 原始 LLM 使用量数据
     * @return 转换后的 Token 统计
     */
    public static Tokens fromUsage(com.aliyun.odps.agentic.llm.Usage usage) {
        if (usage == null) return empty();
        int cacheRead = Math.max(0, usage.cacheReadInputTokens());
        int cacheWrite = Math.max(0, usage.cacheCreationInputTokens());
        int adjustedInput = Math.max(0, usage.inputTokens() - cacheRead - cacheWrite);
        int output = Math.max(0, usage.outputTokens());
        // reasoning 是 output 的**子集**（OpenAI 兼容口径下 completion_tokens_details.reasoning_tokens
        // 已经计在 completion_tokens 里），所以它单独上报、绝不计入 total —— 否则思维链一开总数就翻倍。
        // 此前这里硬写 0，导致 Usage.reasoningTokens() 明明从线上解析出来了却在最后一跳被丢掉，
        // Tokens.reasoning 这个组件因此没有任何生产者（add() 白加了它一份）。
        int reasoning = Math.max(0, Math.min(usage.reasoningTokens(), output));
        int total = usage.totalTokens();
        return new Tokens(adjustedInput, output, reasoning, cacheRead, cacheWrite, total);
    }
}
