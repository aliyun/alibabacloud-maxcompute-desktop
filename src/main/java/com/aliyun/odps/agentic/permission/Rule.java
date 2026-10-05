package com.aliyun.odps.agentic.permission;

/**
 * 权限规则 -- 将操作与模式进行匹配。
 *
 * <p>规则按顺序求值，第一条匹配的规则生效。
 * 模式支持 glob 风格的通配符（如 {@code "file:///**"}、{@code "bash://npm *"}）。
 *
 * @param permission 权限类别（如 {@code "file"}、{@code "bash"}、{@code "mcp"}）
 * @param pattern    glob 风格的匹配模式
 * @param action     匹配后执行的动作
 */
public record Rule(
    String permission,
    String pattern,
    Action action
) {
    /**
     * 创建一条 ALLOW 规则，使用通配符模式匹配指定权限类别。
     *
     * @param permission 权限类别
     * @return ALLOW 规则
     */
    public static Rule allow(String permission) {
        return new Rule(permission, "*", Action.ALLOW);
    }

    /**
     * 创建一条指定模式的 ALLOW 规则。
     *
     * @param permission 权限类别
     * @param pattern    匹配模式
     * @return ALLOW 规则
     */
    public static Rule allow(String permission, String pattern) {
        return new Rule(permission, pattern, Action.ALLOW);
    }

    /**
     * 创建一条 DENY 规则，使用通配符模式匹配指定权限类别。
     *
     * @param permission 权限类别
     * @return DENY 规则
     */
    public static Rule deny(String permission) {
        return new Rule(permission, "*", Action.DENY);
    }

    /**
     * 创建一条指定模式的 DENY 规则。
     *
     * @param permission 权限类别
     * @param pattern    匹配模式
     * @return DENY 规则
     */
    public static Rule deny(String permission, String pattern) {
        return new Rule(permission, pattern, Action.DENY);
    }

    /**
     * 创建一条 ASK 规则，使用通配符模式匹配指定权限类别。
     *
     * @param permission 权限类别
     * @return ASK 规则
     */
    public static Rule ask(String permission) {
        return new Rule(permission, "*", Action.ASK);
    }

    /**
     * 创建一条指定模式的 ASK 规则。
     *
     * @param permission 权限类别
     * @param pattern    匹配模式
     * @return ASK 规则
     */
    public static Rule ask(String permission, String pattern) {
        return new Rule(permission, pattern, Action.ASK);
    }
}
