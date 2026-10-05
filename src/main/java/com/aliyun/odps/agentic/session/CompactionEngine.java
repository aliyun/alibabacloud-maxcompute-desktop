package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.*;
import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.ToolCallState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 上下文压缩引擎。
 *
 * <p>当上下文窗口逼近模型容量时，该引擎负责通过裁剪旧工具结果和 LLM 生成摘要来缩减消息体积。
 * 核心流程分为三步：裁剪（prune）、选择头尾分割点（select）和摘要生成（summarize）。
 */
public class CompactionEngine {

    private static final Logger log = LoggerFactory.getLogger(CompactionEngine.class);

    /** 裁剪阈值下限：低于此值则不值得裁剪。 */
    public static final int PRUNE_MINIMUM = 20_000;

    /** 最近上下文保护区大小（字符数）。 */
    public static final int PRUNE_PROTECT = 40_000;

    /** 工具输出最大字符数，超过后截断。 */
    public static final int TOOL_OUTPUT_MAX_CHARS = 2_000;

    /** 永远不会被裁剪的工具集合。 */
    public static final Set<String> PRUNE_PROTECTED_TOOLS = Set.of("skill");

    /** 默认保留的尾部对话轮次数。 */
    private static final int DEFAULT_TAIL_TURNS = 2;

    /** 尾部保留的最小 Token 预算。 */
    private static final int MIN_PRESERVE_RECENT_TOKENS = 2_000;

    /** 尾部保留的最大 Token 预算。 */
    private static final int MAX_PRESERVE_RECENT_TOKENS = 8_000;

    /**
     * 摘要模板。
     * 该模板定义了包含 7 个结构化段落的 Markdown 格式，供模型在压缩时填充。
     */
    public static final String SUMMARY_TEMPLATE = """
Output exactly the Markdown structure shown inside <template> and keep the section order unchanged. Do not include the <template> tags in your response.
<template>
## Goal
- [single-sentence task summary]

## Constraints & Preferences
- [user constraints, preferences, specs, or "(none)"]

## Progress
### Done
- [completed work or "(none)"]

### In Progress
- [current work or "(none)"]

### Blocked
- [blockers or "(none)"]

## Key Decisions
- [decision and why, or "(none)"]

## Next Steps
- [ordered next actions or "(none)"]

## Critical Context
- [important technical facts, errors, open questions, or "(none)"]

## Relevant Files
- [file or directory path: why it matters, or "(none)"]
</template>

Rules:
- Keep every section, even when empty.
- Use terse bullets, not prose paragraphs.
- Preserve exact file paths, commands, error strings, and identifiers when known.
- Do not mention the summary process or that context was compacted.""";

    /**
     * 压缩路径。
     */
    public enum CompactionRoute {
        /** 无需压缩。 */
        NONE,
        /** 仅截断工具结果。 */
        TRUNCATE_TOOL_RESULTS_ONLY,
        /** 需要完整压缩（摘要）。 */
        COMPACT
    }

    /**
     * 根据当前消息和模型判断是否需要压缩以及压缩方式。
     *
     * @param messages 当前消息列表
     * @param model 模型定义
     * @return 压缩路径
     */
    public CompactionRoute checkNeeded(List<Message> messages, Model model) {
        if (messages.isEmpty()) return CompactionRoute.NONE;

        // 粗略估算：4 字符约等于 1 Token
        long totalChars = messages.stream()
            .mapToLong(m -> {
                long chars = m.getTextContent().length();
                for (MessagePart part : m.parts()) {
                    if (part instanceof MessagePart.ToolPart tp) {
                        chars += getToolOutputLength(tp);
                    }
                }
                return chars;
            })
            .sum();

        long estimatedTokens = totalChars / 4;
        long contextLimit = model.limit().effectiveInputLimit();

        if (contextLimit <= 0) return CompactionRoute.NONE;

        long reserved = Math.min(20_000, model.limit().output() != null ? model.limit().output() : 8192);
        long usable = contextLimit - reserved;

        if (estimatedTokens >= usable) {
            return CompactionRoute.COMPACT;
        }

        // 检查工具结果本身是否已足够庞大以至于值得单独截断
        long toolResultChars = messages.stream()
            .mapToLong(m -> {
                long chars = 0;
                for (MessagePart part : m.parts()) {
                    if (part instanceof MessagePart.ToolPart tp) {
                        chars += getToolOutputLength(tp);
                    }
                }
                return chars;
            })
            .sum();

        if (toolResultChars > PRUNE_PROTECT) {
            return CompactionRoute.TRUNCATE_TOOL_RESULTS_ONLY;
        }

        return CompactionRoute.NONE;
    }

    // ── 阶段一：裁剪工具结果 ──

    /**
     * 裁剪旧工具结果以释放上下文空间。
     *
     * <p>算法从最新消息向前遍历，跳过最近 2 个用户轮次，然后累计超出保护区的工具输出并标记为已压缩。
     *
     * @param messages 当前消息列表
     * @return 裁剪后的消息列表
     */
    public List<Message> pruneToolResults(List<Message> messages) {
        if (messages.isEmpty()) return messages;

        int turns = 0;
        int totalEstimate = 0;
        int prunedEstimate = 0;
        List<PruneTarget> toPrune = new ArrayList<>();

        // 从最新消息向前遍历
        for (int msgIndex = messages.size() - 1; msgIndex >= 0; msgIndex--) {
            Message msg = messages.get(msgIndex);
            if (msg.role() == Role.USER) turns++;
            // 跳过最近 2 个用户轮次
            if (turns < 2) continue;
            // 遇到摘要消息停止
            if (msg.role() == Role.ASSISTANT && msg.isCompacted()) break;

            for (MessagePart part : msg.parts()) {
                if (!(part instanceof MessagePart.ToolPart tp)) continue;
                if (!(tp.state() instanceof ToolCallState.Completed completed)) continue;
                if (PRUNE_PROTECTED_TOOLS.contains(tp.tool())) continue;
                // 已经被压缩过则停止
                if (completed.time().isCompacted()) break;

                int estimate = completed.output() != null && completed.output().output() != null
                    ? completed.output().output().length() / 4
                    : 0;
                totalEstimate += estimate;
                if (totalEstimate > PRUNE_PROTECT) {
                    prunedEstimate += estimate;
                    toPrune.add(new PruneTarget(msgIndex, tp));
                }
            }
        }

        if (prunedEstimate <= PRUNE_MINIMUM) {
            log.debug("Prune skipped: only {} tokens would be pruned (minimum {})", prunedEstimate, PRUNE_MINIMUM);
            return messages;
        }

        Set<String> prunedCallIds = new HashSet<>();
        for (var target : toPrune) {
            prunedCallIds.add(target.toolPart.callID());
        }

        List<Message> result = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            boolean hasPrunedParts = msg.parts().stream()
                .anyMatch(p -> p instanceof MessagePart.ToolPart tp && prunedCallIds.contains(tp.callID()));
            if (!hasPrunedParts) {
                result.add(msg);
                continue;
            }
            List<MessagePart> newParts = new ArrayList<>();
            for (MessagePart part : msg.parts()) {
                if (part instanceof MessagePart.ToolPart tp && prunedCallIds.contains(tp.callID())
                    && tp.state() instanceof ToolCallState.Completed completed) {
                    var compactedTime = new ToolCallState.ToolTime(
                        completed.time().start(), completed.time().end(), System.currentTimeMillis());
                    var compactedOutput = new ToolCallState.ToolOutput(
                        completed.output() != null ? completed.output().title() : null,
                        "[Old tool result content cleared]");
                    newParts.add(new MessagePart.ToolPart(tp.tool(), tp.callID(),
                        new ToolCallState.Completed(completed.input(), completed.raw(), compactedOutput, compactedTime),
                        tp.metadata()));
                } else {
                    newParts.add(part);
                }
            }
            result.add(new Message(msg.id(), msg.sessionId(), msg.role(), msg.parentMessageId(),
                msg.tokens(), newParts, msg.agent(), msg.model(), msg.finish(), msg.error(),
                msg.summary(), msg.tools(), msg.cost(), msg.createdAt(), msg.hostMetadata()));
        }

        log.info("Pruned {} tool results, ~{} tokens freed", toPrune.size(), prunedEstimate);
        return result;
    }

    // ── 阶段二：准备压缩（选择头尾分割点） ──

    /**
     * 生成压缩方案但不执行 LLM 调用。
     *
     * @param messages 当前消息列表
     * @param auto 是否自动触发
     * @param overflow 是否由溢出触发
     * @return 压缩方案
     */
    public CompactionResult process(List<Message> messages, boolean auto, boolean overflow) {
        // 查找前一次摘要，用于增量更新
        String previousSummary = findPreviousSummary(messages);

        // 选择头尾分割点
        int tailStart = selectTailStart(messages);
        if (tailStart <= 0) {
            return new CompactionResult(messages, "stop", null);
        }

        List<Message> head = messages.subList(0, tailStart);
        List<Message> tail = messages.subList(tailStart, messages.size());

        String prompt = buildPrompt(previousSummary, head);
        String status = tail.isEmpty() ? "stop" : "continue";
        return new CompactionResult(head, tail, prompt, auto, overflow, previousSummary, status);
    }

    // ── 阶段三：调用 LLM 生成摘要并替换消息头部 ──

    /**
     * 执行完整的上下文压缩流程：选择分割点、调用 LLM 生成摘要、构建摘要消息。
     *
     * @param messages 当前消息列表
     * @param llmClient LLM 客户端
     * @param model 使用的模型
     * @param eventConsumer 事件消费者
     * @return 压缩后的消息列表
     */
    public List<Message> compactWithSummary(List<Message> messages, LLMClient llmClient,
                                             Model model, Consumer<AgentEvent> eventConsumer) {
        if (messages.size() <= 4) return messages;

        String previousSummary = findPreviousSummary(messages);
        int tailStart = selectTailStart(messages);
        if (tailStart <= 0) return messages;

        List<Message> head = messages.subList(0, tailStart);
        List<Message> tail = messages.subList(tailStart, messages.size());

        String prompt = buildPrompt(previousSummary, head);
        String summary = callSummaryLLM(prompt, llmClient, model);

        // 构建摘要消息
        Message summaryMsg = new Message(
            UUID.randomUUID().toString(),
            head.getFirst().sessionId(),
            Role.ASSISTANT,
            null, null,
            List.of(
                new MessagePart.TextPart(summary, true),
                new MessagePart.CompactionPart(true, false, tail.getFirst().id(), summary)
            ),
            "compaction",
            null, null, null, summary, null, null,
            head.getFirst().createdAt()
        );

        List<Message> result = new ArrayList<>();
        result.add(summaryMsg);
        result.addAll(tail);

        log.info("Compacted {} messages into summary + {} tail messages (previousSummary={})",
            head.size(), tail.size(), previousSummary != null ? "yes" : "no");

        return result;
    }

    // ── 辅助方法 ──

    /**
     * 从历史消息中查找最近一次压缩摘要文本。
     */
    private String findPreviousSummary(List<Message> messages) {
        String lastSummary = null;
        for (Message msg : messages) {
            if (msg.role() == Role.ASSISTANT && msg.isCompacted()) {
                String text = msg.getTextContent();
                if (text != null && !text.isBlank()) {
                    lastSummary = text.trim();
                }
            }
        }
        return lastSummary;
    }

    /**
     * 构建发送给 LLM 的压缩提示词。
     * 若存在前一次摘要，则要求模型增量更新而非从头重建。
     */
    private String buildPrompt(String previousSummary, List<Message> head) {
        String anchor;
        if (previousSummary != null && !previousSummary.isBlank()) {
            anchor = """
Update the anchored summary below using the conversation history above.
Preserve still-true details, remove stale details, and merge in the new facts.
<previous-summary>
%s
</previous-summary>""".formatted(previousSummary);
        } else {
            anchor = "Create a new anchored summary from the conversation history above.";
        }

        String context = buildTranscript(head);
        return String.join("\n\n", anchor, SUMMARY_TEMPLATE, context);
    }

    /**
     * 选择尾部保留起始索引。
     * 基于用户轮次边界和 Token 预算决定头尾分割点。
     */
    private int selectTailStart(List<Message> messages) {
        int tailTurns = DEFAULT_TAIL_TURNS;
        if (tailTurns <= 0) return 0;

        // 定位用户消息边界，跳过压缩消息
        List<Integer> userMessageIndices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i).role() == Role.USER) {
                boolean isCompaction = messages.get(i).parts().stream()
                    .anyMatch(p -> p instanceof MessagePart.CompactionPart);
                if (!isCompaction) {
                    userMessageIndices.add(i);
                }
            }
        }

        if (userMessageIndices.isEmpty()) return 0;

        long budget = MAX_PRESERVE_RECENT_TOKENS;

        int startIdx = Math.max(0, userMessageIndices.size() - tailTurns);
        int tailStartMessageIdx = userMessageIndices.get(startIdx);

        // 估算尾部 Token 数
        long tailTokens = 0;
        for (int i = tailStartMessageIdx; i < messages.size(); i++) {
            tailTokens += messages.get(i).getTextContent().length() / 4;
        }

        if (tailTokens <= budget) {
            return tailStartMessageIdx;
        }

        // 尝试缩小尾部以适应预算
        for (int i = startIdx + 1; i < userMessageIndices.size(); i++) {
            int candidateIdx = userMessageIndices.get(i);
            long candidateTokens = 0;
            for (int j = candidateIdx; j < messages.size(); j++) {
                candidateTokens += messages.get(j).getTextContent().length() / 4;
            }
            if (candidateTokens <= budget) {
                return candidateIdx;
            }
        }

        return userMessageIndices.getLast();
    }

    /**
     * 调用 LLM 生成压缩摘要。
     */
    private String callSummaryLLM(String prompt, LLMClient llmClient, Model model) {
        try {
            Model compactionModel = model;

            List<Map<String, Object>> summaryMessages = List.of(
                Map.of("role", "user", "content", prompt)
            );

            LlmRequest request = LlmRequest.of(
                compactionModel,
                List.of(),
                summaryMessages
            );

            StringBuilder result = new StringBuilder();
            llmClient.stream(request, event -> {
                if (event instanceof LLMEvent.TextDelta td) {
                    result.append(td.delta());
                }
            });

            String summary = result.toString().trim();
            if (summary.isEmpty()) {
                log.warn("LLM returned empty summary, using fallback");
                return buildFallbackSummary(prompt);
            }
            return summary;
        } catch (Exception e) {
            log.error("Failed to generate compaction summary via LLM", e);
            return buildFallbackSummary(prompt);
        }
    }

    /**
     * 当 LLM 不可用时生成回退摘要。
     */
    private String buildFallbackSummary(String transcript) {
        return """
## Goal
- Context compaction (LLM unavailable for summary)

## Constraints & Preferences
- (none)

## Progress
### Done
- Previous conversation history compacted

### In Progress
- (none)

### Blocked
- (none)

## Key Decisions
- (none)

## Next Steps
- Continue from where the conversation left off

## Critical Context
- Summary generated by fallback (LLM was unavailable)

## Relevant Files
- (none)""";
    }

    /**
     * 构建供压缩用的对话转录文本。
     */
    private String buildTranscript(List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        for (Message msg : messages) {
            String role = msg.role().name().toLowerCase();
            String content = msg.getTextContent();
            if (content != null && !content.isEmpty()) {
                sb.append(role).append(": ").append(content).append("\n\n");
            }
            // 包含工具使用摘要信息
            for (MessagePart part : msg.parts()) {
                if (part instanceof MessagePart.ToolPart tp
                    && tp.state() instanceof ToolCallState.Completed completed
                    && completed.output() != null && completed.output().output() != null) {
                    String output = completed.output().output();
                    if (output.length() > TOOL_OUTPUT_MAX_CHARS) {
                        output = output.substring(0, TOOL_OUTPUT_MAX_CHARS) + "\n...[truncated]";
                    }
                    sb.append("tool(").append(tp.tool()).append("): ").append(output).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    private long getToolOutputLength(MessagePart.ToolPart tp) {
        if (tp.state() instanceof ToolCallState.Completed completed
            && completed.output() != null && completed.output().output() != null) {
            return completed.output().output().length();
        }
        return 0;
    }

    /**
     * 压缩方案结果。
     *
     * @param head 待摘要的头部消息
     * @param tail 需要保留的尾部消息
     * @param prompt 压缩提示词
     * @param auto 是否自动触发
     * @param overflow 是否因溢出触发
     * @param previousSummary 前一次摘要
     * @param status 压缩状态
     */
    public record CompactionResult(
        List<Message> head,
        List<Message> tail,
        String prompt,
        boolean auto,
        boolean overflow,
        String previousSummary,
        String status
    ) {
        /**
         * 创建简化结果。
         *
         * @param allMessages 全部消息
         * @param status 状态
         * @param error 错误信息
         */
        public CompactionResult(List<Message> allMessages, String status, String error) {
            this(allMessages, List.of(), null, false, false, null, status);
        }

        /**
         * 合并头部和尾部消息。
         *
         * @return 合并后的消息列表
         */
        public List<Message> merged() {
            if (tail.isEmpty()) return head;
            List<Message> result = new ArrayList<>(head);
            result.addAll(tail);
            return result;
        }
    }

    private record PruneTarget(int msgIndex, MessagePart.ToolPart toolPart) {}
}
