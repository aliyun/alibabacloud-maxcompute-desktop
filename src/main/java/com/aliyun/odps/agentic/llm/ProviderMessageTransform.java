package com.aliyun.odps.agentic.llm;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 提供者消息预处理器，在发送消息到 LLM 之前执行各项变换。
 *
 * <p>已实现的变换包括：
 * <ul>
 *   <li>Anthropic 缓存控制标记注入</li>
 *   <li>Claude 工具调用 ID 清理</li>
 *   <li>DeepSeek 推理内容注入</li>
 *   <li>Mistral/Devstral 工具调用 ID 截断与辅助消息插入</li>
 *   <li>连续同角色消息合并</li>
 *   <li>空内容过滤</li>
 *   <li>不支持的媒体模态替换</li>
 *   <li>代理对冲符清理</li>
 * </ul>
 */
public final class ProviderMessageTransform {

    private ProviderMessageTransform() {}

    // ── MIME 到模态映射 ─────────────────────────────────────────

    /**
     * 将 MIME 类型映射为输入模态标识。
     *
     * @param mime MIME 类型
     * @return 模态名称，或 {@code null}
     */
    public static String mimeToModality(String mime) {
        if (mime == null) return null;
        if (mime.startsWith("image/")) return "image";
        if (mime.startsWith("audio/")) return "audio";
        if (mime.startsWith("video/")) return "video";
        if ("application/pdf".equals(mime)) return "pdf";
        return null;
    }

    // ── 不支持的媒体模态过滤 ───────────────────────────────────

    /**
     * 将模型不支持的媒体内容块替换为错误提示文本。
     *
     * @param messages 提供者格式的消息列表
     * @param model    模型元数据
     * @return 替换后的消息列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> filterUnsupportedParts(
            List<Map<String, Object>> messages, Model model) {
        if (model == null || model.inputCaps() == null) {
            return messages;
        }

        return messages.stream().map(msg -> {
            if (!"user".equals(msg.get("role"))) {
                return msg;
            }

            Object content = msg.get("content");
            if (!(content instanceof List<?> contentList)) {
                return msg;
            }

            boolean changed = false;
            List<Object> newContent = new ArrayList<>();

            for (Object item : contentList) {
                if (!(item instanceof Map)) {
                    newContent.add(item);
                    continue;
                }

                Map<String, Object> part = (Map<String, Object>) item;
                String type = String.valueOf(part.get("type"));

                if (!"image".equals(type) && !"document".equals(type) && !"file".equals(type)) {
                    newContent.add(item);
                    continue;
                }

                // 检查空白或损坏的 base64 图片数据
                if ("image".equals(type)) {
                    Object source = part.get("source");
                    if (source instanceof Map<?, ?> sourceMap) {
                        String sourceType = String.valueOf(sourceMap.get("type"));
                        if ("base64".equals(sourceType)) {
                            String data = (String) sourceMap.get("data");
                            if (data == null || data.isEmpty()) {
                                Map<String, Object> errorBlock = new LinkedHashMap<>();
                                errorBlock.put("type", "text");
                                errorBlock.put("text",
                                    "ERROR: Image file is empty or corrupted. Please provide a valid image.");
                                newContent.add(errorBlock);
                                changed = true;
                                continue;
                            }
                        }
                    }
                }

                String mime = extractMimeType(part, type);
                String modality = mimeToModality(mime);
                if (modality == null) {
                    newContent.add(item);
                    continue;
                }

                if (model.inputCaps().supports(modality)) {
                    newContent.add(item);
                    continue;
                }

                String filename = part.containsKey("name")
                    ? "\"" + part.get("name") + "\""
                    : (part.containsKey("filename") ? "\"" + part.get("filename") + "\"" : null);
                String name = filename != null ? filename : modality;

                Map<String, Object> errorBlock = new LinkedHashMap<>();
                errorBlock.put("type", "text");
                errorBlock.put("text",
                    "ERROR: Cannot read " + name + " (this model does not support " + modality + " input). Inform the user.");
                newContent.add(errorBlock);
                changed = true;
            }

            if (!changed) {
                return msg;
            }

            Map<String, Object> newMsg = new LinkedHashMap<>(msg);
            newMsg.put("content", newContent);
            return newMsg;
        }).collect(Collectors.toList());
    }

    /**
     * 从内容块中提取 MIME 类型。
     */
    @SuppressWarnings("unchecked")
    private static String extractMimeType(Map<String, Object> part, String type) {
        if ("image".equals(type)) {
            Object source = part.get("source");
            if (source instanceof Map<?, ?> sourceMap) {
                Object mediaType = sourceMap.get("media_type");
                if (mediaType != null) return String.valueOf(mediaType);
            }
        }
        if (part.containsKey("media_type")) {
            return String.valueOf(part.get("media_type"));
        }
        if (part.containsKey("mediaType")) {
            return String.valueOf(part.get("mediaType"));
        }
        if ("document".equals(type)) {
            Object source = part.get("source");
            if (source instanceof Map<?, ?> sourceMap) {
                Object mediaType = sourceMap.get("media_type");
                if (mediaType != null) return String.valueOf(mediaType);
            }
        }
        return null;
    }

    // ── 代理对冲符清理 ──────────────────────────────────────────

    /**
     * 将文本中的孤立代理对冲符替换为 Unicode 替换字符。
     *
     * @param content 待清理的文本
     * @return 清理后的文本
     */
    public static String sanitizeSurrogates(String content) {
        if (content == null) return null;
        StringBuilder sb = new StringBuilder(content.length());
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 < content.length() && Character.isLowSurrogate(content.charAt(i + 1))) {
                    sb.append(c);
                    sb.append(content.charAt(i + 1));
                    i++;
                } else {
                    sb.append('�');
                }
            } else if (Character.isLowSurrogate(c)) {
                sb.append('�');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── Claude 工具调用 ID 清理 ───────────────────────────────────

    /**
     * 清理 Claude 模型的工具调用 ID，将非法字符替换为下划线。
     *
     * @param messages 提供者格式的消息列表
     * @param model    模型元数据
     * @return 清理后的消息列表
     */
    public static List<Map<String, Object>> scrubClaudeToolCallIds(
            List<Map<String, Object>> messages, Model model) {
        if (!model.apiId().toLowerCase().contains("claude")) {
            return messages;
        }
        return messages.stream()
            .map(msg -> scrubToolCallIdsInMessage(msg, id -> id.replaceAll("[^a-zA-Z0-9_\\-]", "_")))
            .collect(Collectors.toList());
    }

    // ── Mistral/Devstral 变换 ─────────────────────────────────────

    /**
     * 检查模型是否为 Mistral/Devstral。
     *
     * @param model 模型元数据
     * @return 是 Mistral/Devstral 时返回 {@code true}
     */
    public static boolean isMistral(Model model) {
        String providerId = model.providerId().toLowerCase();
        String apiId = model.apiId().toLowerCase();
        return "mistral".equals(providerId)
            || apiId.contains("mistral")
            || apiId.contains("devstral");
    }

    /**
     * 对 Mistral 模型执行工具调用 ID 截断和虚拟助手消息插入。
     *
     * @param messages 提供者格式的消息列表
     * @param model    模型元数据
     * @return 变换后的消息列表
     */
    public static List<Map<String, Object>> applyMistralTransforms(
            List<Map<String, Object>> messages, Model model) {
        if (!isMistral(model)) {
            return messages;
        }

        List<Map<String, Object>> scrubbed = messages.stream()
            .map(msg -> scrubToolCallIdsInMessage(msg, ProviderMessageTransform::mistralScrubId))
            .collect(Collectors.toList());

        List<Map<String, Object>> result = new ArrayList<>();
        for (int i = 0; i < scrubbed.size(); i++) {
            Map<String, Object> msg = scrubbed.get(i);
            result.add(msg);

            if (i + 1 < scrubbed.size()) {
                String role = String.valueOf(msg.get("role"));
                String nextRole = String.valueOf(scrubbed.get(i + 1).get("role"));

                boolean isToolMsg = "tool".equals(role) || isToolResultMessage(msg);
                if (isToolMsg && "user".equals(nextRole)) {
                    Map<String, Object> dummyAssistant = new LinkedHashMap<>();
                    dummyAssistant.put("role", "assistant");
                    dummyAssistant.put("content", List.of(Map.of("type", "text", "text", "Done.")));
                    result.add(dummyAssistant);
                }
            }
        }
        return result;
    }

    /**
     * Mistral 工具调用 ID 截断：仅保留前 9 个字母数字字符。
     */
    private static String mistralScrubId(String id) {
        String clean = id.replaceAll("[^a-zA-Z0-9]", "");
        if (clean.length() >= 9) {
            return clean.substring(0, 9);
        }
        return clean + "0".repeat(9 - clean.length());
    }

    // ── DeepSeek 推理内容注入 ────────────────────────────────────

    /**
     * 检查模型是否为 DeepSeek。
     *
     * @param model 模型元数据
     * @return 是 DeepSeek 时返回 {@code true}
     */
    public static boolean isDeepSeek(Model model) {
        return model.apiId().toLowerCase().contains("deepseek");
    }

    /**
     * 判断模型在后续轮次中是否应通过 {@code reasoning_content} 字段回放推理内容。
     *
     * @param model 模型元数据
     * @return 需要回放时返回 {@code true}
     */
    public static boolean usesReasoningContentReplay(Model model) {
        if (model == null) {
            return false;
        }
        String providerId = model.providerId().toLowerCase();
        String modelId = model.apiId().toLowerCase();
        return isDeepSeek(model)
            || providerId.contains("alibaba")
            || modelId.contains("qwen")
            || modelId.contains("qwq");
    }

    /**
     * 将 DeepSeek 助手历史消息规范化为消息级 {@code reasoning_content} 格式。
     *
     * @param messages 提供者格式的消息列表
     * @param model    模型元数据
     * @return 规范化后的消息列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> injectDeepSeekReasoning(
            List<Map<String, Object>> messages, Model model) {
        if (!isDeepSeek(model)) {
            return messages;
        }

        return messages.stream().map(msg -> {
            if (!"assistant".equals(msg.get("role"))) {
                return msg;
            }

            Map<String, Object> newMsg = new LinkedHashMap<>(msg);
            StringBuilder reasoningContent = new StringBuilder();
            Object existingReasoning = newMsg.get("reasoning_content");
            if (existingReasoning instanceof String reasoning && !reasoning.isEmpty()) {
                reasoningContent.append(reasoning);
            }

            Object content = newMsg.get("content");
            if (content instanceof List<?> contentList) {
                StringBuilder textContent = new StringBuilder();
                for (Object item : contentList) {
                    if (!(item instanceof Map<?, ?> partMap)) {
                        continue;
                    }
                    Object type = partMap.get("type");
                    if ("text".equals(type)) {
                        Object text = partMap.get("text");
                        if (text != null) {
                            textContent.append(text);
                        }
                    } else if ("reasoning".equals(type) || "thinking".equals(type)) {
                        Object text = partMap.get("text");
                        if (text == null) {
                            text = partMap.get("thinking");
                        }
                        if (text != null) {
                            reasoningContent.append(text);
                        }
                    }
                }
                newMsg.put("content", textContent.isEmpty() ? null : textContent.toString());
            }

            newMsg.put("reasoning_content", reasoningContent.toString());
            return newMsg;
        }).collect(Collectors.toList());
    }

    // ── Anthropic 空内容过滤 ───────────────────────────────────

    /**
     * 检查模型是否为 Anthropic。
     *
     * @param model 模型元数据
     * @return 是 Anthropic 时返回 {@code true}
     */
    public static boolean isAnthropic(Model model) {
        return "anthropic".equals(model.providerId())
            || model.apiId().toLowerCase().contains("claude")
            || model.apiId().toLowerCase().contains("anthropic");
    }

    /**
     * 过滤 Anthropic 不接受的空文本和空推理内容块。
     *
     * @param messages 提供者格式的消息列表
     * @param model    模型元数据
     * @return 过滤后的消息列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> filterEmptyContentForAnthropic(
            List<Map<String, Object>> messages, Model model) {
        if (!isAnthropic(model)) {
            return messages;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> msg : messages) {
            Object content = msg.get("content");

            if (content instanceof String s) {
                if (s.isEmpty()) {
                    continue;
                }
                result.add(msg);
                continue;
            }

            if (content instanceof List<?> contentList) {
                List<Map<String, Object>> filtered = new ArrayList<>();
                for (Object item : contentList) {
                    if (item instanceof Map) {
                        Map<String, Object> part = (Map<String, Object>) item;
                        String type = String.valueOf(part.get("type"));

                        if ("text".equals(type)) {
                            String text = String.valueOf(part.getOrDefault("text", ""));
                            if (!text.isEmpty()) {
                                filtered.add(part);
                            }
                        } else if ("reasoning".equals(type) || "thinking".equals(type)) {
                            String text = String.valueOf(part.getOrDefault("text",
                                part.getOrDefault("thinking", "")));
                            if (text.trim().length() > 0
                                || part.containsKey("signature")
                                || part.containsKey("redactedData")) {
                                filtered.add(part);
                            }
                        } else {
                            filtered.add(part);
                        }
                    }
                }

                if (filtered.isEmpty()) {
                    continue;
                }

                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", filtered);
                result.add(newMsg);
                continue;
            }

            result.add(msg);
        }

        return result;
    }

    // ── Anthropic 缓存控制标记 ─────────────────────────────────────

    /**
     * 为消息和系统块应用 Anthropic 提示词缓存所需的 {@code cache_control} 标记。
     *
     * @param messages       非系统消息列表
     * @param systemMessages 系统消息列表
     * @return 标记后的消息列表
     */
    public static List<Map<String, Object>> applyCacheControl(
            List<Map<String, Object>> messages, List<Map<String, Object>> systemMessages) {
        applyCacheControlToSystemBlocks(systemMessages);
        applyCacheControlToRecentMessages(messages);
        return messages;
    }

    /**
     * 对前 2 个系统块应用 {@code cache_control} 标记。
     *
     * <p>0.4.0：系统提示词前缀在整个会话中保持稳定，是会话里被反复读取最多的内容，
     * 因此对它使用 {@code ttl: "1h"}（Anthropic 支持的最长缓存窗口），
     * 把长会话的缓存命中窗口从默认 5 分钟拉长到 1 小时。代价是该前缀的缓存写
     * 单价更高（1h 缓存写 = 2× 基础输入价 vs 5m = 1.25×），但对稳定前缀而言，
     * 多次命中读带来的节省通常远超这一点写溢价。
     *
     * @param systemBlocks 系统块列表
     */
    public static void applyCacheControlToSystemBlocks(List<Map<String, Object>> systemBlocks) {
        Map<String, Object> longLivedCache = Map.of("type", "ephemeral", "ttl", "1h");
        for (int i = 0; i < Math.min(2, systemBlocks.size()); i++) {
            systemBlocks.get(i).put("cache_control", longLivedCache);
        }
    }

    /**
     * 对最近 2 条会话消息的最后一个内容块应用 {@code cache_control} 标记。
     *
     * @param messages 会话消息列表
     */
    @SuppressWarnings("unchecked")
    public static void applyCacheControlToRecentMessages(List<Map<String, Object>> messages) {
        int count = 0;
        for (int i = messages.size() - 1; i >= 0 && count < 2; i--) {
            Map<String, Object> msg = messages.get(i);
            Object content = msg.get("content");

            if (content == null) {
                continue;
            }

            if (content instanceof String textContent) {
                if (textContent.isEmpty()) {
                    continue;
                }
                Map<String, Object> textBlock = new LinkedHashMap<>();
                textBlock.put("type", "text");
                textBlock.put("text", textContent);
                textBlock.put("cache_control", Map.of("type", "ephemeral"));
                msg.put("content", new ArrayList<>(List.of(textBlock)));
                count++;
            } else if (content instanceof List<?> contentList) {
                if (contentList.isEmpty()) {
                    continue;
                }
                Object lastItem = contentList.get(contentList.size() - 1);
                if (lastItem instanceof Map) {
                    ((Map<String, Object>) lastItem).put("cache_control", Map.of("type", "ephemeral"));
                }
                count++;
            }
        }
    }

    // ── 全消息代理对冲符清理 ──────────────────────────────────

    /**
     * 清理所有消息中文本内容的代理对冲符。
     *
     * @param messages 提供者格式的消息列表
     * @return 清理后的消息列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> sanitizeAllSurrogates(List<Map<String, Object>> messages) {
        return messages.stream().map(msg -> {
            Map<String, Object> newMsg = new LinkedHashMap<>(msg);
            Object content = msg.get("content");

            if (content instanceof String s) {
                newMsg.put("content", sanitizeSurrogates(s));
            } else if (content instanceof List<?> parts) {
                List<Object> newParts = new ArrayList<>();
                for (Object part : parts) {
                    if (part instanceof Map) {
                        Map<String, Object> partMap = new LinkedHashMap<>((Map<String, Object>) part);
                        String type = String.valueOf(partMap.get("type"));
                        if ("text".equals(type) || "reasoning".equals(type) || "thinking".equals(type)) {
                            String text = (String) partMap.get("text");
                            if (text != null) {
                                partMap.put("text", sanitizeSurrogates(text));
                            }
                            String thinking = (String) partMap.get("thinking");
                            if (thinking != null) {
                                partMap.put("thinking", sanitizeSurrogates(thinking));
                            }
                        } else if ("tool_result".equals(type)) {
                            Object resultContent = partMap.get("content");
                            if (resultContent instanceof String s) {
                                partMap.put("content", sanitizeSurrogates(s));
                            }
                        }
                        newParts.add(partMap);
                    } else {
                        newParts.add(part);
                    }
                }
                newMsg.put("content", newParts);
            }

            return newMsg;
        }).collect(Collectors.toList());
    }

    // ── 连续同角色消息合并 ─────────────────────────────────

    /**
     * 合并连续的同角色消息。
     *
     * <p>Anthropic API 不允许相邻消息具有相同角色，此方法将它们合并为一条消息。
     *
     * @param messages 提供者格式的消息列表
     * @param model    模型元数据
     * @return 合并后的消息列表
     */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> mergeConsecutiveSameRoleMessages(
            List<Map<String, Object>> messages, Model model) {
        if (!isAnthropic(model)) {
            return messages;
        }
        if (messages == null || messages.size() <= 1) {
            return messages;
        }

        List<Map<String, Object>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>(messages.get(0)));

        for (int i = 1; i < messages.size(); i++) {
            Map<String, Object> current = messages.get(i);
            Map<String, Object> previous = result.get(result.size() - 1);

            String currentRole = String.valueOf(current.get("role"));
            String previousRole = String.valueOf(previous.get("role"));

            if (currentRole.equals(previousRole)) {
                Object prevContent = previous.get("content");
                Object currContent = current.get("content");

                Object merged = mergeContent(prevContent, currContent);
                previous.put("content", merged);
            } else {
                result.add(new LinkedHashMap<>(current));
            }
        }

        return result;
    }

    /**
     * 合并两个内容值。
     */
    @SuppressWarnings("unchecked")
    private static Object mergeContent(Object prevContent, Object currContent) {
        boolean prevIsString = prevContent instanceof String;
        boolean currIsString = currContent instanceof String;

        if (prevIsString && currIsString) {
            String prev = (String) prevContent;
            String curr = (String) currContent;
            if (prev.isEmpty()) return curr;
            if (curr.isEmpty()) return prev;
            return prev + "\n\n" + curr;
        }

        List<Object> prevList = contentToList(prevContent);
        List<Object> currList = contentToList(currContent);

        List<Object> merged = new ArrayList<>(prevList);
        merged.addAll(currList);
        return merged;
    }

    /**
     * 将内容值转换为内容块列表。
     */
    @SuppressWarnings("unchecked")
    private static List<Object> contentToList(Object content) {
        if (content instanceof List) {
            return new ArrayList<>((List<Object>) content);
        }
        if (content instanceof String s && !s.isEmpty()) {
            Map<String, Object> textBlock = new LinkedHashMap<>();
            textBlock.put("type", "text");
            textBlock.put("text", s);
            return new ArrayList<>(List.of(textBlock));
        }
        return new ArrayList<>();
    }

    // ── 组合变换入口 ─────────────────────────────────

    /**
     * 根据模型和提供者信息应用所有相关的消息变换。
     *
     * <p>这是主入口，应在构建请求体之前调用。
     *
     * @param messages 提供者格式的消息列表
     * @param model    模型元数据
     * @return 变换后的消息列表
     */
    public static List<Map<String, Object>> transformMessages(
            List<Map<String, Object>> messages, Model model) {
        // 0. 过滤不支持的媒体模态
        List<Map<String, Object>> result = filterUnsupportedParts(messages, model);

        // 1. 清理代理对冲符
        result = sanitizeAllSurrogates(result);

        // 2. 合并连续同角色消息
        if (isAnthropic(model)) {
            result = mergeConsecutiveSameRoleMessages(result, model);
        }

        // 3. 过滤空内容
        if (isAnthropic(model)) {
            result = filterEmptyContentForAnthropic(result, model);
        }

        // 4. Claude 工具调用 ID 清理
        if (model.apiId().toLowerCase().contains("claude")) {
            result = scrubClaudeToolCallIds(result, model);
        }

        // 5. Mistral/Devstral 变换
        if (isMistral(model)) {
            result = applyMistralTransforms(result, model);
            return result;
        }

        // 6. DeepSeek 推理内容注入
        if (isDeepSeek(model)) {
            result = injectDeepSeekReasoning(result, model);
        }

        return result;
    }

    // ── 辅助方法 ──────────────────────────────────────

    /**
     * 对单条消息中的工具调用 ID 应用指定的清理函数。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> scrubToolCallIdsInMessage(
            Map<String, Object> msg, java.util.function.Function<String, String> scrubber) {
        Object content = msg.get("content");
        String role = String.valueOf(msg.get("role"));

        if (content instanceof List<?> parts) {
            List<Object> newParts = new ArrayList<>();
            boolean changed = false;

            for (Object part : parts) {
                if (part instanceof Map) {
                    Map<String, Object> partMap = (Map<String, Object>) part;
                    String type = String.valueOf(partMap.get("type"));

                    if ("tool_use".equals(type) || "tool_call".equals(type)
                            || "tool-call".equals(type) || "tool-result".equals(type)) {
                        Map<String, Object> newPart = new LinkedHashMap<>(partMap);
                        if (newPart.containsKey("id")) {
                            newPart.put("id", scrubber.apply(String.valueOf(newPart.get("id"))));
                            changed = true;
                        }
                        if (newPart.containsKey("toolCallId")) {
                            newPart.put("toolCallId", scrubber.apply(String.valueOf(newPart.get("toolCallId"))));
                            changed = true;
                        }
                        if (newPart.containsKey("tool_use_id")) {
                            newPart.put("tool_use_id", scrubber.apply(String.valueOf(newPart.get("tool_use_id"))));
                            changed = true;
                        }
                        newParts.add(newPart);
                    } else if ("tool_result".equals(type)) {
                        Map<String, Object> newPart = new LinkedHashMap<>(partMap);
                        if (newPart.containsKey("tool_use_id")) {
                            newPart.put("tool_use_id", scrubber.apply(String.valueOf(newPart.get("tool_use_id"))));
                            changed = true;
                        }
                        newParts.add(newPart);
                    } else {
                        newParts.add(part);
                    }
                } else {
                    newParts.add(part);
                }
            }

            if (changed) {
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("content", newParts);
                return newMsg;
            }
        }

        if (msg.containsKey("tool_calls") && msg.get("tool_calls") instanceof List<?> toolCalls) {
            List<Object> newToolCalls = new ArrayList<>();
            boolean changed = false;
            for (Object tc : toolCalls) {
                if (tc instanceof Map) {
                    Map<String, Object> tcMap = new LinkedHashMap<>((Map<String, Object>) tc);
                    if (tcMap.containsKey("id")) {
                        tcMap.put("id", scrubber.apply(String.valueOf(tcMap.get("id"))));
                        changed = true;
                    }
                    newToolCalls.add(tcMap);
                } else {
                    newToolCalls.add(tc);
                }
            }
            if (changed) {
                Map<String, Object> newMsg = new LinkedHashMap<>(msg);
                newMsg.put("tool_calls", newToolCalls);
                return newMsg;
            }
        }

        if (msg.containsKey("tool_call_id")) {
            Map<String, Object> newMsg = new LinkedHashMap<>(msg);
            newMsg.put("tool_call_id", scrubber.apply(String.valueOf(msg.get("tool_call_id"))));
            return newMsg;
        }

        return msg;
    }

    /**
     * 判断消息是否为 Anthropic 格式的工具结果消息。
     */
    @SuppressWarnings("unchecked")
    private static boolean isToolResultMessage(Map<String, Object> msg) {
        Object content = msg.get("content");
        if (content instanceof List<?> parts) {
            return parts.stream()
                .filter(p -> p instanceof Map)
                .map(p -> (Map<String, Object>) p)
                .anyMatch(p -> "tool_result".equals(p.get("type")));
        }
        return false;
    }
}
