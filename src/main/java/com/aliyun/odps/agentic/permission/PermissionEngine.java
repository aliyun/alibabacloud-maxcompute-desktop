package com.aliyun.odps.agentic.permission;

import java.util.List;

/**
 * 权限求值引擎。
 * 根据配置的规则列表对操作请求进行求值，决定允许、拒绝或询问用户。
 */
public class PermissionEngine {

    private final List<Rule> rules;
    private final boolean disabled;

    public PermissionEngine(List<Rule> rules, boolean disabled) {
        this.rules = rules != null ? rules : List.of();
        this.disabled = disabled;
    }

    public PermissionEngine(List<Rule> rules) {
        this(rules, false);
    }

    /**
     * 求值指定操作是否被允许。
     * 规则按顺序检查，第一条匹配的规则生效；若无匹配则默认为 ASK。
     *
     * @param permission 权限类别
     * @param target     要匹配的操作目标
     * @return 应执行的动作
     */
    public Action evaluate(String permission, String target) {
        if (disabled) return Action.ALLOW;

        for (Rule rule : rules) {
            if (matchesPermission(rule.permission(), permission)
                && matchesPattern(rule.pattern(), target)) {
                return rule.action();
            }
        }
        return Action.ASK;
    }

    /**
     * 检查引擎是否已禁用（所有操作均允许）。
     *
     * @return 是否已禁用
     */
    public boolean disabled() {
        return disabled;
    }

    /** 匹配权限类别，支持精确匹配和通配符。 */
    private boolean matchesPermission(String rulePermission, String requestedPermission) {
        return "*".equals(rulePermission) || rulePermission.equals(requestedPermission);
    }

    /**
     * 使用 glob 风格通配符进行模式匹配。
     * 支持：{@code *}（单层匹配）、{@code **}（任意路径）、{@code ?}（单字符）。
     */
    private boolean matchesPattern(String pattern, String target) {
        if ("*".equals(pattern) || "**".equals(pattern)) return true;
        if (pattern.equals(target)) return true;

        // 将 glob 模式转换为正则表达式
        String regex = globToRegex(pattern);
        return target.matches(regex);
    }

    private String globToRegex(String glob) {
        StringBuilder sb = new StringBuilder();
        sb.append("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        sb.append(".*");
                        i++; // skip next *
                    } else {
                        sb.append("[^/]*");
                    }
                }
                case '?' -> sb.append(".");
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                    sb.append("\\").append(c);
                default -> sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }
}
