package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;

import java.util.List;

/**
 * 表示一次手动或自动上下文压缩的结果。
 *
 * @param summaryMessage 生成的摘要消息；若未生成则为 {@code null}
 * @param messages 压缩后的消息列表
 * @param changed 本次压缩是否实际改变了消息集合
 * @param overflow 本次压缩是否由上下文溢出触发
 * @param reason 压缩触发原因
 * @param summary 摘要文本；若无摘要则为 {@code null}
 */
public record ManualCompactionResult(
    Message summaryMessage,
    List<Message> messages,
    boolean changed,
    boolean overflow,
    CompactionReason reason,
    String summary
) {}
