package com.aliyun.odps.agentic.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProviderMessageTransform}（803 行协议文件）的测试安全网。
 *
 * <p>此前该文件无任何测试——cache_control 断点、同角色合并、代理对冲符清理、
 * 工具调用 ID 清理都处在「改协议无网」的状态。本测试锁定这些关键协议行为。
 */
class ProviderMessageTransformTest {

    private static Map<String, Object> textMsg(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", text);
        return m;
    }

    // ── cache_control 断点 ──

    @Test
    void systemBlocksGetLongLivedCacheBreakpoint() {
        List<Map<String, Object>> system = new ArrayList<>();
        system.add(Map.of("type", "text", "text", "sys0", "_m", new LinkedHashMap<>()));
        // 用可变 map
        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", "sys0")));
        blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", "sys1")));
        blocks.add(new LinkedHashMap<>(Map.of("type", "text", "text", "sys2")));

        ProviderMessageTransform.applyCacheControlToSystemBlocks(blocks);

        // 前 2 个系统块拿到 1h 缓存断点
        for (int i = 0; i < 2; i++) {
            Object cc = blocks.get(i).get("cache_control");
            assertNotNull(cc, "system block " + i + " should have cache_control");
            @SuppressWarnings("unchecked")
            Map<String, Object> ccm = (Map<String, Object>) cc;
            assertEquals("ephemeral", ccm.get("type"));
            assertEquals("1h", ccm.get("ttl"), "stable system prefix should use 1h cache window");
        }
        // 第 3 个不设断点
        assertNull(blocks.get(2).get("cache_control"));
    }

    @Test
    void recentMessagesGetEphemeralCacheBreakpoint() {
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(textMsg("user", "first"));
        msgs.add(textMsg("assistant", "second"));
        msgs.add(textMsg("user", "third"));

        ProviderMessageTransform.applyCacheControlToRecentMessages(msgs);

        // 最近 2 条消息的最后一个内容块带 ephemeral cache_control
        // third(user) 和 second(assistant) 应被标记，first 不标
        Object last = msgs.get(2).get("content");
        assertTrue(last instanceof List<?>);
        @SuppressWarnings("unchecked")
        Map<String, Object> lastBlock = (Map<String, Object>) ((List<?>) last).get(0);
        assertEquals("ephemeral", ((Map<?, ?>) lastBlock.get("cache_control")).get("type"));
    }

    // ── 连续同角色消息合并 ──

    @Test
    void mergesConsecutiveSameRoleMessagesForAnthropic() {
        Model anthropic = anthropicModel();
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(textMsg("user", "a"));
        msgs.add(textMsg("user", "b"));
        msgs.add(textMsg("assistant", "c"));

        List<Map<String, Object>> merged =
            ProviderMessageTransform.mergeConsecutiveSameRoleMessages(msgs, anthropic);

        assertEquals(2, merged.size(), "两个相邻 user 应合并");
        assertEquals("a\n\nb", merged.get(0).get("content"));
        assertEquals("assistant", merged.get(1).get("role"));
    }

    @Test
    void doesNotMergeAlternatingRoles() {
        Model anthropic = anthropicModel();
        List<Map<String, Object>> msgs = new ArrayList<>();
        msgs.add(textMsg("user", "a"));
        msgs.add(textMsg("assistant", "b"));
        msgs.add(textMsg("user", "c"));

        List<Map<String, Object>> merged =
            ProviderMessageTransform.mergeConsecutiveSameRoleMessages(msgs, anthropic);
        assertEquals(3, merged.size(), "交替角色不应合并");
    }

    // ── 代理对冲符清理 ──

    @Test
    void sanitizesLoneSurrogatesInText() {
        List<Map<String, Object>> msgs = new ArrayList<>();
        // 含孤代理（lone surrogate）的非法 UTF-8 文本
        msgs.add(textMsg("user", "hello \uD83D world"));

        List<Map<String, Object>> out = ProviderMessageTransform.sanitizeAllSurrogates(msgs);
        String cleaned = (String) out.get(0).get("content");
        assertNotNull(cleaned);
        // 孤代理应被移除或替换，不再是非法序列
        assertFalse(cleaned.chars().anyMatch(c -> Character.isSurrogate((char) c)),
            "lone surrogates must be stripped");
    }

    // ── Claude 工具调用 ID 清理 ──

    @Test
    void scrubClaudeToolCallIdsSanitizesIds() {
        Model claude = claudeModel();
        Map<String, Object> toolUse = new LinkedHashMap<>();
        toolUse.put("type", "tool_use");
        toolUse.put("id", "call with spaces/slash\\");
        toolUse.put("name", "read");
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", new ArrayList<>(List.of(toolUse)));

        List<Map<String, Object>> out =
            ProviderMessageTransform.scrubClaudeToolCallIds(new ArrayList<>(List.of(msg)), claude);

        @SuppressWarnings("unchecked")
        Map<String, Object> part = (Map<String, Object>) ((List<?>) out.get(0).get("content")).get(0);
        String id = String.valueOf(part.get("id"));
        // Claude 工具 ID 只允许 [a-zA-Z0-9_-]，空格/斜杠/反斜杠应被清理
        assertTrue(id.matches("[a-zA-Z0-9_-]+"), "scrubbed id must match Claude's allowed charset, got: " + id);
    }

    // ── helpers ──

    private static Model anthropicModel() {
        Route route = new Route("anthropic", new AnthropicTransform(), "http://localhost",
            Auth.none, MessageFormat.ANTHROPIC);
        return route.model("claude-sonnet-4-20250514", new ModelLimit(200000, null, 4096));
    }

    private static Model claudeModel() {
        Route route = new Route("anthropic", new AnthropicTransform(), "http://localhost",
            Auth.none, MessageFormat.ANTHROPIC);
        return route.model("claude-3-5-sonnet-20241022", new ModelLimit(200000, null, 4096));
    }
}
