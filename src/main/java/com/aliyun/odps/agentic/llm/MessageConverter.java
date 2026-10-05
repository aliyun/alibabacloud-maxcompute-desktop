package com.aliyun.odps.agentic.llm;

import com.aliyun.odps.agentic.model.Message;
import com.aliyun.odps.agentic.model.MessagePart;
import com.aliyun.odps.agentic.model.Role;
import com.aliyun.odps.agentic.model.ToolCallState;

import java.util.*;

/**
 * 将内部 {@link Message} 对象转换为提供者特定格式的转换器。
 *
 * <p>支持两种输出格式：
 * <ul>
 *   <li><b>Anthropic</b> — 系统消息独立传递，内容块使用类型鉴别器，
 *       {@code tool_use}/{@code tool_result} 块，{@code cache_control} 标记</li>
 *   <li><b>OpenAI</b> — 系统消息作为普通消息，助手消息使用 {@code tool_calls} 数组，
 *       工具结果使用 {@code tool} 角色</li>
 * </ul>
 *
 * <p>工具结果格式化规则：
 * <ul>
 *   <li>已完成工具：输出字符串或包含文本与附件的内容数组</li>
 *   <li>错误工具：错误文本字符串</li>
 *   <li>被中断工具：{@code "[Tool execution was interrupted]"}</li>
 *   <li>已压缩工具：{@code "[Old tool result content cleared]"}</li>
 * </ul>
 */
public class MessageConverter {

    private static final String INTERRUPTED_MESSAGE = "[Tool execution was interrupted]";
    private static final String COMPACTED_MESSAGE = "[Old tool result content cleared]";

    /**
     * 将消息列表转换为 Anthropic 提供者格式。
     *
     * <p>系统消息会被排除（它们通过独立的 system 参数传递）。
     * 转换完成后应用提供者特定的变换管线（空内容过滤、工具 ID 清理等）。
     *
     * @param messages 内部消息列表
     * @param model    模型元数据（用于应用变换）
     * @return Anthropic API 格式的消息列表
     */
    public static List<Map<String, Object>> toAnthropicMessages(List<Message> messages, Model model) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Message msg : messages) {
            if (msg.role() == Role.SYSTEM) {
                continue;
            }

            Map<String, Object> formatted = new LinkedHashMap<>();
            formatted.put("role", mapRoleForAnthropic(msg.role()));

            List<Map<String, Object>> contentParts = new ArrayList<>();

            for (MessagePart part : msg.parts()) {
                if (part instanceof MessagePart.TextPart tp) {
                    Map<String, Object> textBlock = new LinkedHashMap<>();
                    textBlock.put("type", "text");
                    textBlock.put("text", tp.text());
                    contentParts.add(textBlock);
                } else if (part instanceof MessagePart.ToolCallPart tcp) {
                    Map<String, Object> toolUseBlock = new LinkedHashMap<>();
                    toolUseBlock.put("type", "tool_use");
                    toolUseBlock.put("id", tcp.callID());
                    toolUseBlock.put("name", tcp.name());
                    toolUseBlock.put("input", parseJson(tcp.input()));
                    contentParts.add(toolUseBlock);
                } else if (part instanceof MessagePart.ToolResultPart trp) {
                    Map<String, Object> toolResultBlock = new LinkedHashMap<>();
                    toolResultBlock.put("type", "tool_result");
                    toolResultBlock.put("tool_use_id", trp.callID());
                    toolResultBlock.put("content", formatToolResultContent(trp));
                    if (trp.isError()) {
                        toolResultBlock.put("is_error", true);
                    }
                    contentParts.add(toolResultBlock);
                } else if (part instanceof MessagePart.ToolPart tp) {
                    // 将统一 ToolPart 转换为 Anthropic 对应的内容块
                    addToolPartAsAnthropic(tp, contentParts, msg.role());
                } else if (part instanceof MessagePart.ReasoningPart rp) {
                    Map<String, Object> thinkingBlock = new LinkedHashMap<>();
                    thinkingBlock.put("type", "thinking");
                    thinkingBlock.put("thinking", rp.text());
                    contentParts.add(thinkingBlock);
                } else if (part instanceof MessagePart.ImagePart ip) {
                    contentParts.add(buildAnthropicImageBlock(ip));
                } else if (part instanceof MessagePart.DocumentPart dp) {
                    contentParts.add(buildAnthropicDocumentBlock(dp));
                }
                // 跳过 StepStartPart、StepFinishPart、SnapshotPart 等元数据部分
            }

            if (contentParts.size() == 1 && "text".equals(contentParts.getFirst().get("type"))) {
                formatted.put("content", contentParts.getFirst().get("text"));
            } else if (!contentParts.isEmpty()) {
                formatted.put("content", contentParts);
            } else {
                formatted.put("content", "");
            }

            result.add(formatted);
        }

        // 应用提供者特定的变换管线
        if (model != null) {
            result = ProviderMessageTransform.transformMessages(result, model);
        }

        return result;
    }

    /**
     * 将消息列表转换为 Anthropic 格式的便捷重载（不指定模型）。
     */
    public static List<Map<String, Object>> toAnthropicMessages(List<Message> messages) {
        return toAnthropicMessages(messages, null);
    }

    /**
     * 将消息列表转换为 OpenAI Chat Completions 格式。
     *
     * <p>系统消息作为 {@code role: "system"} 的普通消息包含在内。
     * 工具调用使用助手消息上的 {@code tool_calls} 数组格式，
     * 工具结果使用 {@code role: "tool"} 和 {@code tool_call_id}。
     *
     * @param messages 内部消息列表
     * @param model    模型元数据（用于应用变换）
     * @return OpenAI Chat Completions 格式的消息列表
     */
    public static List<Map<String, Object>> toOpenAIMessages(List<Message> messages, Model model) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Message msg : messages) {
            if (msg.role() == Role.SYSTEM) {
                Map<String, Object> sysMsg = new LinkedHashMap<>();
                sysMsg.put("role", "system");
                sysMsg.put("content", msg.getTextContent());
                result.add(sysMsg);
                continue;
            }

            if (msg.role() == Role.ASSISTANT) {
                Map<String, Object> assistantMsg = buildOpenAIAssistantMessage(msg, model);
                result.add(assistantMsg);
                continue;
            }

            // 用户角色 — 可能包含普通内容或工具结果
            List<MessagePart.ToolResultPart> toolResults = new ArrayList<>();
            List<MessagePart.ToolPart> toolParts = new ArrayList<>();
            List<Map<String, Object>> userContentParts = new ArrayList<>();

            for (MessagePart part : msg.parts()) {
                if (part instanceof MessagePart.ToolResultPart trp) {
                    toolResults.add(trp);
                } else if (part instanceof MessagePart.ToolPart tp) {
                    if (tp.state() instanceof ToolCallState.Completed
                        || tp.state() instanceof ToolCallState.Error) {
                        toolParts.add(tp);
                    }
                } else if (part instanceof MessagePart.TextPart tp) {
                    Map<String, Object> textBlock = new LinkedHashMap<>();
                    textBlock.put("type", "text");
                    textBlock.put("text", tp.text());
                    userContentParts.add(textBlock);
                } else if (part instanceof MessagePart.ImagePart ip) {
                    userContentParts.add(buildOpenAIImageBlock(ip));
                } else if (part instanceof MessagePart.DocumentPart dp) {
                    // OpenAI 不原生支持文档内容块，转换为文本占位符
                    Map<String, Object> textBlock = new LinkedHashMap<>();
                    textBlock.put("type", "text");
                    String name = dp.name() != null ? "\"" + dp.name() + "\"" : "document";
                    textBlock.put("text", "[Attached " + dp.mimeType() + ": " + name + "]");
                    userContentParts.add(textBlock);
                }
            }

            // 输出工具结果消息（OpenAI 使用独立的 "tool" 角色消息）
            for (MessagePart.ToolResultPart trp : toolResults) {
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", trp.callID());
                toolMsg.put("content", formatToolResultContent(trp));
                result.add(toolMsg);
            }

            // 输出来自 ToolPart 的工具结果
            for (MessagePart.ToolPart tp : toolParts) {
                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("tool_call_id", tp.callID());
                toolMsg.put("content", formatToolPartResultContent(tp));
                result.add(toolMsg);
            }

            // 如果存在非工具内容，输出用户内容消息
            if (!userContentParts.isEmpty()) {
                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                if (userContentParts.size() == 1 && "text".equals(userContentParts.getFirst().get("type"))) {
                    userMsg.put("content", userContentParts.getFirst().get("text"));
                } else {
                    userMsg.put("content", userContentParts);
                }
                result.add(userMsg);
            } else if (toolResults.isEmpty() && toolParts.isEmpty()) {
                // 无任何内容 — 输出空用户消息
                Map<String, Object> userMsg = new LinkedHashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", msg.getTextContent());
                result.add(userMsg);
            }
        }

        // 应用提供者特定的变换管线
        if (model != null) {
            result = ProviderMessageTransform.transformMessages(result, model);
        }

        return result;
    }

    /**
     * 从消息列表中提取系统消息，用于 Anthropic 的独立 system 参数。
     *
     * @param messages 内部消息列表
     * @return 系统提示词片段列表
     */
    public static List<String> extractSystemPrompts(List<Message> messages) {
        List<String> system = new ArrayList<>();
        for (Message msg : messages) {
            if (msg.role() == Role.SYSTEM) {
                String text = msg.getTextContent();
                if (text != null && !text.isEmpty()) {
                    system.add(text);
                }
            }
        }
        return system;
    }

    // ── 私有辅助方法 ─────────────────────────────────────────────────

    /**
     * 从 {@link Message} 构建 OpenAI 格式的助手消息，将工具调用提取到顶层 {@code tool_calls} 数组中。
     */
    private static Map<String, Object> buildOpenAIAssistantMessage(Message msg, Model model) {
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");

        boolean captureReasoning = model != null && ProviderMessageTransform.usesReasoningContentReplay(model);
        StringBuilder textContent = new StringBuilder();
        StringBuilder reasoningContent = new StringBuilder();
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (MessagePart part : msg.parts()) {
            if (part instanceof MessagePart.TextPart tp) {
                textContent.append(tp.text());
            } else if (part instanceof MessagePart.ReasoningPart rp) {
                if (captureReasoning && rp.text() != null) {
                    reasoningContent.append(rp.text());
                }
            } else if (part instanceof MessagePart.ToolCallPart tcp) {
                Map<String, Object> toolCall = new LinkedHashMap<>();
                toolCall.put("id", tcp.callID());
                toolCall.put("type", "function");
                toolCall.put("function", Map.of(
                    "name", tcp.name(),
                    "arguments", tcp.input() != null ? tcp.input() : ""
                ));
                toolCalls.add(toolCall);
            } else if (part instanceof MessagePart.ToolPart tp) {
                // 助手消息上的 ToolPart 表示工具调用
                Map<String, Object> toolCall = new LinkedHashMap<>();
                toolCall.put("id", tp.callID());
                toolCall.put("type", "function");

                String input = "";
                if (tp.state() instanceof ToolCallState.Running r && r.raw() != null) {
                    input = r.raw();
                } else if (tp.state() instanceof ToolCallState.Completed c && c.raw() != null) {
                    input = c.raw();
                } else if (tp.state() instanceof ToolCallState.Error e && e.raw() != null) {
                    input = e.raw();
                } else if (tp.state() instanceof ToolCallState.Pending p && p.raw() != null) {
                    input = p.raw();
                }

                toolCall.put("function", Map.of(
                    "name", tp.tool(),
                    "arguments", input
                ));
                toolCalls.add(toolCall);
            }
            // OpenAI 格式跳过 StepStartPart 等
        }

        if (textContent.length() > 0) {
            assistantMsg.put("content", textContent.toString());
        } else {
            assistantMsg.put("content", null);
        }

        if (captureReasoning && !reasoningContent.isEmpty()) {
            assistantMsg.put("reasoning_content", reasoningContent.toString());
        }

        if (!toolCalls.isEmpty()) {
            assistantMsg.put("tool_calls", toolCalls);
        }

        return assistantMsg;
    }

    /**
     * 将统一的 {@link MessagePart.ToolPart} 添加为 Anthropic 内容块。
     * 助手消息上生成 {@code tool_use} 块，用户消息上生成 {@code tool_result} 块。
     */
    private static void addToolPartAsAnthropic(MessagePart.ToolPart tp,
            List<Map<String, Object>> contentParts, Role role) {
        if (role == Role.ASSISTANT) {
            // 助手侧：tool_use 块
            Map<String, Object> toolUseBlock = new LinkedHashMap<>();
            toolUseBlock.put("type", "tool_use");
            toolUseBlock.put("id", tp.callID());
            toolUseBlock.put("name", tp.tool());

            String rawInput = "";
            if (tp.state() instanceof ToolCallState.Running r && r.raw() != null) {
                rawInput = r.raw();
            } else if (tp.state() instanceof ToolCallState.Completed c && c.raw() != null) {
                rawInput = c.raw();
            } else if (tp.state() instanceof ToolCallState.Error e && e.raw() != null) {
                rawInput = e.raw();
            } else if (tp.state() instanceof ToolCallState.Pending p && p.raw() != null) {
                rawInput = p.raw();
            }
            toolUseBlock.put("input", parseJson(rawInput));
            contentParts.add(toolUseBlock);
        } else {
            // 用户侧：tool_result 块
            Map<String, Object> toolResultBlock = new LinkedHashMap<>();
            toolResultBlock.put("type", "tool_result");
            toolResultBlock.put("tool_use_id", tp.callID());
            toolResultBlock.put("content", formatToolPartResultContent(tp));

            if (tp.state() instanceof ToolCallState.Error) {
                toolResultBlock.put("is_error", true);
            }
            contentParts.add(toolResultBlock);
        }
    }

    /**
     * 格式化 {@link MessagePart.ToolResultPart} 的工具结果内容。
     */
    private static String formatToolResultContent(MessagePart.ToolResultPart trp) {
        return trp.output() != null ? trp.output() : "";
    }

    /**
     * 格式化统一 {@link MessagePart.ToolPart} 的工具结果内容。
     *
     * <p>根据工具状态返回不同内容：已完成返回输出、错误返回错误文本、
     * 运行中/待处理返回中断消息、已压缩返回清除消息。
     */
    private static String formatToolPartResultContent(MessagePart.ToolPart tp) {
        if (tp.state() instanceof ToolCallState.Completed c) {
            // 检查是否已压缩
            if (c.time() != null && c.time().isCompacted()) {
                return COMPACTED_MESSAGE;
            }
            if (c.output() != null) {
                return c.output().output() != null ? c.output().output() : "";
            }
            return "";
        } else if (tp.state() instanceof ToolCallState.Error e) {
            return e.error() != null ? e.error() : "";
        } else if (tp.state() instanceof ToolCallState.Running
                   || tp.state() instanceof ToolCallState.Pending) {
            return INTERRUPTED_MESSAGE;
        }
        return "";
    }

    /**
     * 将内部角色映射为 Anthropic 角色字符串。
     */
    private static String mapRoleForAnthropic(Role role) {
        return switch (role) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case SYSTEM -> "system"; // Anthropic 消息中不应出现
        };
    }

    /**
     * 尝试将 JSON 字符串解析为对象，解析失败时返回空 Map。
     */
    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }

    // ── 图片/文档内容块构建器 ─────────────────────────────────

    /**
     * 从 {@link MessagePart.ImagePart} 构建 Anthropic 图片内容块。
     *
     * <ul>
     *   <li>base64: {@code { type: "image", source: { type: "base64", media_type: "...", data: "..." } }}</li>
     *   <li>URL: {@code { type: "image", source: { type: "url", url: "..." } }}</li>
     * </ul>
     */
    private static Map<String, Object> buildAnthropicImageBlock(MessagePart.ImagePart ip) {
        Map<String, Object> imageBlock = new LinkedHashMap<>();
        imageBlock.put("type", "image");

        if (ip.isBase64()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "base64");
            source.put("media_type", ip.mimeType());
            source.put("data", ip.data());
            imageBlock.put("source", source);
        } else {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("type", "url");
            source.put("url", ip.url());
            imageBlock.put("source", source);
        }

        return imageBlock;
    }

    /**
     * 从 {@link MessagePart.DocumentPart} 构建 Anthropic 文档内容块。
     *
     * <p>输出格式：
     * {@code { type: "document", source: { type: "base64", media_type: "...", data: "..." } }}
     */
    private static Map<String, Object> buildAnthropicDocumentBlock(MessagePart.DocumentPart dp) {
        Map<String, Object> docBlock = new LinkedHashMap<>();
        docBlock.put("type", "document");

        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        source.put("media_type", dp.mimeType());
        source.put("data", dp.data());
        docBlock.put("source", source);

        if (dp.name() != null) {
            docBlock.put("name", dp.name());
        }

        return docBlock;
    }

    /**
     * 从 {@link MessagePart.ImagePart} 构建 OpenAI {@code image_url} 内容块。
     *
     * <ul>
     *   <li>base64: {@code { type: "image_url", image_url: { url: "data:mime;base64,data" } }}</li>
     *   <li>URL: {@code { type: "image_url", image_url: { url: "https://..." } }}</li>
     * </ul>
     */
    private static Map<String, Object> buildOpenAIImageBlock(MessagePart.ImagePart ip) {
        Map<String, Object> imageBlock = new LinkedHashMap<>();
        imageBlock.put("type", "image_url");

        String imageUrl;
        if (ip.isBase64()) {
            imageUrl = "data:" + ip.mimeType() + ";base64," + ip.data();
        } else {
            imageUrl = ip.url();
        }

        imageBlock.put("image_url", Map.of("url", imageUrl));
        return imageBlock;
    }
}
