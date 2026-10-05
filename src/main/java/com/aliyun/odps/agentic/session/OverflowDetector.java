package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.Model;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.ToolCallState;

import java.util.List;

/**
 * 上下文溢出检测器。
 *
 * <p>根据模型上下文窗口、预留缓冲区和消息估算 Token 数，判断下一次请求是否可能超出限制。
 */
public class OverflowDetector {

    /** 上下文压缩预留缓冲区。 */
    public static final int COMPACTION_BUFFER = 20_000;

    /**
     * 计算模型可安全使用的输入 Token 预算。
     *
     * @param model 模型定义
     * @return 可用输入 Token 数
     */
    public static long usable(Model model) {
        long context = model.limit().context();
        if (context == 0) return 0;

        long reserved = Math.min(COMPACTION_BUFFER,
            model.limit().output() != null ? model.limit().output() : 8192);

        if (model.limit().effectiveInputLimit() > 0) {
            return Math.max(0, model.limit().effectiveInputLimit() - reserved);
        }
        return Math.max(0, context - reserved);
    }

    /**
     * 判断消息集合是否会溢出模型上下文窗口。
     *
     * @param messages 消息列表
     * @param model 模型定义
     * @return 会溢出返回 {@code true}
     */
    public boolean isOverflow(List<Message> messages, Model model) {
        long context = model.limit().context();
        if (context == 0) return false;

        // 采用较粗略的 4 字符约等于 1 Token 的估算方式。
        int estimatedTokens = messages.stream()
            .mapToInt(m -> {
                int chars = m.getTextContent().length();
                for (MessagePart part : m.parts()) {
                    if (part instanceof MessagePart.ToolPart tp) {
                        if (tp.state() instanceof ToolCallState.Completed completed && completed.output() != null) {
                            chars += completed.output().output() != null ? completed.output().output().length() : 0;
                        }
                    }
                }
                return chars;
            })
            .sum() / 4;

        return estimatedTokens >= usable(model);
    }
}
