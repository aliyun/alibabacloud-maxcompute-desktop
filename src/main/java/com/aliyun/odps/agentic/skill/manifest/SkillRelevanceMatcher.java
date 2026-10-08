package com.aliyun.odps.agentic.skill.manifest;


import java.util.*;
import java.util.stream.Collectors;

/**
 * Pre-match 门控：从 SkillManifest.description 提取关键词，
 * 与用户消息做 contains 打分，判断 skill 是否与当前请求相关。
 *
 * <p>参考 LangChain4j {@code SimpleToolSearchStrategy}：
 * name 命中 +3, description 关键词命中 +1, 阈值 ≥2 视为匹配。
 *
 * <p>匹配结果决定注入策略：
 * <ul>
 *   <li>命中 → 全文注入 system prompt（P1）</li>
 *   <li>未命中 → catalog only（name + description + location），LLM 可自主 fileOps 加载</li>
 * </ul>
 */
public class SkillRelevanceMatcher {

    private static final int MATCH_THRESHOLD = 2;

    private static final int NAME_MATCH_SCORE = 3;

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "is", "are", "for", "and", "or", "to", "in",
        "of", "use", "not", "this", "that", "from", "with", "about",
        "can", "has", "was", "will", "all", "any", "its", "per", "via",
        "asks", "user", "when", "such", "also", "only", "used", "does"
    );

    private static final int MIN_KEYWORD_LENGTH = 3;

    public List<String> extractKeywords(String description) {
        if (description == null || description.isBlank()) return List.of();
        return Arrays.stream(description.toLowerCase(Locale.ROOT)
                .split("[\\s,;.()\\[\\]/:*、。，：；（）]+"))
            .map(String::trim)
            .filter(w -> w.length() >= MIN_KEYWORD_LENGTH)
            .filter(w -> !STOP_WORDS.contains(w))
            .distinct()
            .toList();
    }

    public int score(SkillManifest skill, String userMessage) {
        if (skill == null || userMessage == null || userMessage.isBlank()) return 0;

        String msg = userMessage.toLowerCase(Locale.ROOT);
        int score = 0;

        String name = skill.getName() != null ? skill.getName().toLowerCase(Locale.ROOT) : "";
        if (!name.isEmpty() && msg.contains(name)) {
            score += NAME_MATCH_SCORE;
        }

        for (String kw : extractKeywords(skill.getDescription())) {
            if (msg.contains(kw)) {
                score++;
            }
        }
        return score;
    }

    public Set<String> findRelevantSkillNames(List<SkillManifest> skills, String userMessage) {
        if (skills == null || skills.isEmpty() || userMessage == null || userMessage.isBlank()) {
            return Collections.emptySet();
        }
        return skills.stream()
            .filter(s -> score(s, userMessage) >= MATCH_THRESHOLD)
            .map(SkillManifest::getName)
            .collect(Collectors.toSet());
    }
}
