package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 提供者消息投影器集合。
 *
 * <p>负责将内部 {@link Message} 表示转换为更适合不同提供者 API 的输入消息结构。
 */
public final class Projectors {

    private Projectors() {}

    /**
     * 将消息投影为适合 Anthropic API 的形式。
     *
     * @param messages 原始消息列表
     * @return 投影后的消息列表
     */
    public static List<Message> projectForAnthropic(List<Message> messages) {
        return projectForProvider(messages, Provider.ANTHROPIC);
    }

    /**
     * 将消息投影为适合 OpenAI API 的形式。
     *
     * @param messages 原始消息列表
     * @return 投影后的消息列表
     */
    public static List<Message> projectForOpenAI(List<Message> messages) {
        return projectForProvider(messages, Provider.OPENAI);
    }

    private enum Provider { ANTHROPIC, OPENAI }

    private static List<Message> projectForProvider(List<Message> messages, Provider provider) {
        List<Message> result = new ArrayList<>();
        for (Message msg : messages) {
            // 过滤不应重新发送给模型的推理片段。
            List<MessagePart> filtered = msg.parts().stream()
                .filter(p -> !(p instanceof MessagePart.ReasoningPart))
                .collect(Collectors.toList());

            // 非系统消息至少需要保留一个内容块。
            if (filtered.isEmpty() && msg.role() != Role.SYSTEM) {
                filtered = List.of(new MessagePart.TextPart(""));
            }

            result.add(new Message(msg.id(), msg.sessionId(), msg.role(), msg.parentMessageId(),
                msg.tokens(), filtered, msg.agent(), msg.model(),
                msg.finish(), msg.error(), msg.summary(), msg.tools(), msg.cost(), msg.createdAt(),
                msg.hostMetadata()));
        }

        // Anthropic 要求工具结果与对应助手消息按顺序配对。
        if (provider == Provider.ANTHROPIC) {
            result = ensureToolResultPairing(result);
        }

        return result;
    }

    /**
     * 确保工具结果与对应助手消息的配对关系正确。
     * 当前实现只做占位校验，不修改消息顺序。
     *
     * @param messages 待检查消息列表
     * @return 原消息列表
     */
    private static List<Message> ensureToolResultPairing(List<Message> messages) {
        return messages;
    }
}
