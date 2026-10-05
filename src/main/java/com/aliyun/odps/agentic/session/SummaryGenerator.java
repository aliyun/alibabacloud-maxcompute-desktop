package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;

import java.util.List;

/**
 * 会话摘要生成器。
 *
 * <p>用于从会话历史中提取标题和工具使用概览，生成便于展示的摘要信息。
 */
public class SummaryGenerator {

    /**
     * 根据首条用户消息生成会话标题。
     *
     * @param firstUserMessage 会话中的第一条用户消息
     * @return 生成的标题
     */
    public String generateTitle(Message firstUserMessage) {
        if (firstUserMessage == null) return "New Session";
        String text = firstUserMessage.getTextContent();
        if (text == null || text.isBlank()) return "New Session";
        if (text.length() <= 80) return text.trim();
        return text.substring(0, 77).trim() + "...";
    }

    /**
     * 统计会话中的工具使用情况并生成简要摘要。
     *
     * @param messages 会话消息列表
     * @return 面向展示的摘要文本
     */
    public String calculateDiffSummary(List<Message> messages) {
        if (messages == null || messages.isEmpty()) return "No changes.";

        int filesRead = 0;
        int filesWritten = 0;
        int filesEdited = 0;
        int patchesApplied = 0;
        int shellCommands = 0;
        int searches = 0;
        int otherTools = 0;

        for (Message msg : messages) {
            for (MessagePart part : msg.parts()) {
                if (part instanceof MessagePart.ToolPart tp) {
                    String tool = tp.tool();
                    switch (tool) {
                        case "read" -> filesRead++;
                        case "write" -> filesWritten++;
                        case "edit" -> filesEdited++;
                        case "apply_patch" -> patchesApplied++;
                        case "shell" -> shellCommands++;
                        case "glob", "grep" -> searches++;
                        default -> otherTools++;
                    }
                }
            }
        }

        StringBuilder sb = new StringBuilder();
        if (filesRead > 0) sb.append(filesRead).append(" file(s) read. ");
        if (filesWritten > 0) sb.append(filesWritten).append(" file(s) written. ");
        if (filesEdited > 0) sb.append(filesEdited).append(" file(s) edited. ");
        if (patchesApplied > 0) sb.append(patchesApplied).append(" patch(es) applied. ");
        if (shellCommands > 0) sb.append(shellCommands).append(" shell command(s). ");
        if (searches > 0) sb.append(searches).append(" search(es). ");
        if (otherTools > 0) sb.append(otherTools).append(" other tool call(s). ");

        String result = sb.toString().trim();
        return result.isEmpty() ? "No tool usage recorded." : result;
    }
}
