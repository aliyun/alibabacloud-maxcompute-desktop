package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;

import java.util.*;

/**
 * 上下文压缩消息选择器。
 *
 * <p>负责在消息列表中划分需要摘要的头部区段和必须保留的尾部区段，并可优先裁剪过大的工具结果。
 */
public final class CompactionSelector {

    private CompactionSelector() {}

    /** 默认保留的尾部消息数量。 */
    private static final int DEFAULT_TAIL_SIZE = 4;

    /**
     * 上下文压缩选择结果。
     *
     * @param head 需要被摘要的消息头部
     * @param tail 需要原样保留的消息尾部
     */
    public record Selection(
        List<Message> head,
        List<Message> tail
    ) {
        /**
         * 判断当前结果是否需要执行压缩。
         *
         * @return 需要压缩返回 {@code true}
         */
        public boolean shouldCompact() {
            return !head.isEmpty();
        }
    }

    /**
     * 使用默认尾部大小选择待压缩消息。
     *
     * @param messages 原始消息列表
     * @return 选择结果
     */
    public static Selection select(List<Message> messages) {
        return select(messages, DEFAULT_TAIL_SIZE);
    }

    /**
     * 使用指定尾部大小选择待压缩消息。
     *
     * @param messages 原始消息列表
     * @param tailSize 需要保留的尾部消息数量
     * @return 选择结果
     */
    public static Selection select(List<Message> messages, int tailSize) {
        if (messages.size() <= tailSize + 1) {
            return new Selection(List.of(), messages);
        }

        int splitPoint = messages.size() - tailSize;
        splitPoint = adjustSplitPoint(messages, splitPoint);

        List<Message> head = new ArrayList<>(messages.subList(0, splitPoint));
        List<Message> tail = new ArrayList<>(messages.subList(splitPoint, messages.size()));

        return new Selection(head, tail);
    }

    /**
     * 裁剪头部消息中输出过大的工具结果。
     *
     * @param messages 待处理消息列表
     * @param maxToolResultChars 工具结果最大字符数
     * @return 裁剪后的消息列表
     */
    public static List<Message> pruneLargeToolResults(List<Message> messages, int maxToolResultChars) {
        List<Message> result = new ArrayList<>();
        for (Message msg : messages) {
            List<MessagePart> prunedParts = new ArrayList<>();
            boolean changed = false;
            for (MessagePart part : msg.parts()) {
                if (part instanceof MessagePart.ToolResultPart trp) {
                    String output = trp.output();
                    if (output != null && output.length() > maxToolResultChars) {
                        String truncated = output.substring(0, maxToolResultChars) +
                            "\n... [truncated from " + output.length() + " chars]";
                        prunedParts.add(new MessagePart.ToolResultPart(
                            trp.callID(), trp.name(), truncated, trp.isError(), trp.metadata()
                        ));
                        changed = true;
                    } else {
                        prunedParts.add(part);
                    }
                } else {
                    prunedParts.add(part);
                }
            }
            if (changed) {
                result.add(new Message(msg.id(), msg.sessionId(), msg.role(), msg.parentMessageId(),
                    msg.tokens(), prunedParts, msg.agent(), msg.model(),
                    msg.finish(), msg.error(), msg.summary(), msg.tools(), msg.cost(), msg.createdAt(),
                    msg.hostMetadata()));
            } else {
                result.add(msg);
            }
        }
        return result;
    }

    /**
     * 调整分割点，避免切断一组关联的工具调用交换。
     *
     * @param messages 消息列表
     * @param splitPoint 初始分割点
     * @return 调整后的分割点
     */
    private static int adjustSplitPoint(List<Message> messages, int splitPoint) {
        for (int i = splitPoint; i > 1; i--) {
            Message msg = messages.get(i);
            if (msg.role() == Role.USER && hasToolResults(msg)) {
                if (i > 0 && messages.get(i - 1).role() == Role.ASSISTANT) {
                    splitPoint = Math.min(splitPoint, i - 1);
                    break;
                }
            }
        }
        return splitPoint;
    }

    /**
     * 判断消息是否包含工具结果片段。
     */
    private static boolean hasToolResults(Message msg) {
        return msg.parts().stream().anyMatch(p -> p instanceof MessagePart.ToolResultPart);
    }
}
