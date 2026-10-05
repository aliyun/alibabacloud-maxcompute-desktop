package com.aliyun.odps.agentic.session;

import com.aliyun.odps.agentic.llm.LLMClient;
import com.aliyun.odps.agentic.llm.LLMEvent;
import com.aliyun.odps.agentic.llm.LlmRequest;
import com.aliyun.odps.agentic.llm.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 会话标题生成器。
 *
 * <p>该类会基于首条用户消息调用模型生成简洁标题，并在失败时回退到本地截断策略。
 */
public class TitleGenerator {

    private static final Logger log = LoggerFactory.getLogger(TitleGenerator.class);

    private static final String FALLBACK_TITLE = "New Session";

    private static final String SYSTEM_PROMPT =
        "Generate a concise title (3-7 words) for a conversation that starts with the following message. "
        + "Return ONLY the title, no quotes, no punctuation at the end.";

    private TitleGenerator() {}

    /**
     * 根据首条用户消息生成短标题。
     *
     * @param userMessage 首条用户消息文本
     * @param llmClient 用于生成标题的 LLM 客户端
     * @param model 调用使用的模型
     * @return 非空标题字符串
     */
    public static String generateTitle(String userMessage, LLMClient llmClient, Model model) {
        if (userMessage == null || userMessage.isBlank()) {
            return FALLBACK_TITLE;
        }

        try {
            LlmRequest request = new LlmRequest(
                model,
                List.of(SYSTEM_PROMPT),
                List.of(Map.of("role", "user", "content", userMessage)),
                Map.of(),
                "none",
                null,
                50
            );

            StringBuilder titleBuilder = new StringBuilder();
            llmClient.stream(request, event -> {
                if (event instanceof LLMEvent.TextDelta td) {
                    titleBuilder.append(td.delta());
                }
            });

            String title = cleanTitle(titleBuilder.toString());
            if (!title.isEmpty()) {
                return title;
            }
        } catch (Exception e) {
            log.warn("LLM title generation failed, using fallback: {}", e.getMessage());
        }

        return fallbackTitle(userMessage);
    }

    /**
     * 清理模型生成的标题文本。
     *
     * @param raw 原始标题
     * @return 清理后的标题
     */
    static String cleanTitle(String raw) {
        if (raw == null) return "";
        String title = raw.strip();

        if (title.length() >= 2
            && ((title.startsWith("\"") && title.endsWith("\""))
                || (title.startsWith("'") && title.endsWith("'")))) {
            title = title.substring(1, title.length() - 1).strip();
        }

        title = title.replaceAll("[.!?]+$", "");

        return title.strip();
    }

    /**
     * 从用户消息首行生成回退标题。
     *
     * @param userMessage 用户消息文本
     * @return 回退标题
     */
    static String fallbackTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return FALLBACK_TITLE;
        }
        String firstLine = userMessage.lines().findFirst().orElse(userMessage).strip();
        if (firstLine.isEmpty()) {
            return FALLBACK_TITLE;
        }
        return firstLine.length() <= 80 ? firstLine : firstLine.substring(0, 77) + "...";
    }
}
