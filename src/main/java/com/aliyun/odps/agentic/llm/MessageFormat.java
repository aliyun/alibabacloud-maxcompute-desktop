package com.aliyun.odps.agentic.llm;

/**
 * 消息线协议格式。
 *
 * <p>用于决定内部消息在发送给不同提供者时采用的序列化结构。
 */
public enum MessageFormat {
    /** Anthropic 风格的消息格式。 */
    ANTHROPIC,
    /** OpenAI 风格的消息格式。 */
    OPENAI
}
